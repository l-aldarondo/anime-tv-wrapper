/**
 * AnimeTV Player Controller & Decoy Annihilator
 * Handles TV remote media transport (Seek +/-10s, Play/Pause, Fullscreen),
 * cross-iframe postMessage communication, and fake/decoy player elimination.
 */
(function() {
    if (window.__AnimeTvPlayerControllerLoaded) return;
    window.__AnimeTvPlayerControllerLoaded = true;

    // ── 1. Decoy & Fake Player Annihilator (AnimeYT, JKAnime, etc.) ───────────
    function neutralizeDecoys() {
        // Target common decoy player wrappers and fake SVG overlays
        const fakeSelectors = [
            '#fake-player',
            '.fake-player',
            '.decoy-player',
            '#decoy-player',
            '.video-fake-play',
            '.btn-fake-play',
            'div[id*="fake_player"]',
            'div[class*="fake-player"]',
            '.play-fake'
        ];

        document.querySelectorAll(fakeSelectors.join(', ')).forEach(el => {
            try {
                el.style.setProperty('display', 'none', 'important');
                el.style.setProperty('pointer-events', 'none', 'important');
            } catch(e) {}
        });

        // If a real play button exists underneath, trigger it directly
        const realPlayBtn = document.querySelector('.play-button-overlay, .plyr__control--overlaid, .jw-display-icon-container, #player-play, .vjs-big-play-button');
        if (realPlayBtn && !window.__autoPlayTriggered) {
            // Only auto-trigger once per page load if user has navigated to an episode
            if (window.location.href.includes('/episodio') || window.location.href.includes('/episode') || window.location.href.includes('/watch/')) {
                // Wait slightly for player initialization
                setTimeout(() => {
                    try {
                        realPlayBtn.click();
                        window.__autoPlayTriggered = true;
                    } catch(e) {}
                }, 300);
            }
        }
    }

    // Run decoy neutralizer immediately and observe DOM changes
    neutralizeDecoys();
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', neutralizeDecoys);
    }
    const decoyObserver = new MutationObserver(neutralizeDecoys);
    try {
        decoyObserver.observe(document.documentElement || document.body, { childList: true, subtree: true });
    } catch(e) {}

    // ── 2. Video Element Detection & Transport ────────────────────────────────
    function getActiveVideo() {
        return document.querySelector('video');
    }

    function seekRelative(seconds) {
        let handled = false;
        const vid = getActiveVideo();
        if (vid && !isNaN(vid.duration)) {
            vid.currentTime = Math.max(0, Math.min(vid.duration, vid.currentTime + seconds));
            handled = true;
        }

        // Forward command to all child iframes in case player is nested
        document.querySelectorAll('iframe').forEach(f => {
            try {
                f.contentWindow.postMessage({ type: 'animetv-seek', delta: seconds }, '*');
            } catch(e) {}
        });

        return handled;
    }

    function togglePlayPause() {
        let handled = false;
        const vid = getActiveVideo();
        if (vid) {
            if (vid.paused) {
                vid.play().catch(()=>{});
            } else {
                vid.pause();
            }
            handled = true;
        }

        document.querySelectorAll('iframe').forEach(f => {
            try {
                f.contentWindow.postMessage({ type: 'animetv-play-pause' }, '*');
            } catch(e) {}
        });

        return handled;
    }

    function toggleFullscreen() {
        const vid = getActiveVideo();
        if (vid && vid.requestFullscreen) {
            vid.requestFullscreen().catch(()=>{});
            return true;
        }
        const plyr = document.querySelector('.plyr');
        if (plyr && plyr.requestFullscreen) {
            plyr.requestFullscreen().catch(()=>{});
            return true;
        }

        document.querySelectorAll('iframe').forEach(f => {
            try {
                f.contentWindow.postMessage({ type: 'animetv-fullscreen' }, '*');
            } catch(e) {}
        });
        return false;
    }

    // ── 3. Cross-Frame postMessage Listener (for embedded players) ───────────
    window.addEventListener('message', function(event) {
        if (!event.data || typeof event.data !== 'object') return;
        const data = event.data;

        if (data.type === 'animetv-seek' && typeof data.delta === 'number') {
            const vid = getActiveVideo();
            if (vid && !isNaN(vid.duration)) {
                vid.currentTime = Math.max(0, Math.min(vid.duration, vid.currentTime + data.delta));
            }
        } else if (data.type === 'animetv-play-pause') {
            const vid = getActiveVideo();
            if (vid) {
                if (vid.paused) vid.play().catch(()=>{});
                else vid.pause();
            }
        } else if (data.type === 'animetv-fullscreen') {
            const vid = getActiveVideo();
            if (vid && vid.requestFullscreen) {
                vid.requestFullscreen().catch(()=>{});
            } else {
                const fsBtn = document.querySelector('[data-plyr="fullscreen"], .vjs-fullscreen-control, .jw-icon-fullscreen');
                if (fsBtn) fsBtn.click();
            }
        }
    });

    // ── 4. Video State Reporting to AndroidBridge ─────────────────────────────
    function setupVideoListeners(vid) {
        if (!vid || vid.__animeTvBound) return;
        vid.__animeTvBound = true;

        vid.addEventListener('play', function() {
            try {
                if (window.AndroidBridge && window.AndroidBridge.onVideoPlay) {
                    window.AndroidBridge.onVideoPlay();
                }
                // Notify parent frames
                if (window.parent && window.parent !== window) {
                    window.parent.postMessage({ type: 'animetv-video-state', state: 'play' }, '*');
                }
            } catch(e) {}
        });

        vid.addEventListener('pause', function() {
            try {
                if (window.AndroidBridge && window.AndroidBridge.onVideoPause) {
                    window.AndroidBridge.onVideoPause();
                }
                if (window.parent && window.parent !== window) {
                    window.parent.postMessage({ type: 'animetv-video-state', state: 'pause' }, '*');
                }
            } catch(e) {}
        });
    }

    // Watch for dynamically added videos
    setInterval(function() {
        const vid = getActiveVideo();
        if (vid) setupVideoListeners(vid);
    }, 1000);

    // Expose API
    window.AnimeTvPlayer = {
        seek: seekRelative,
        togglePlay: togglePlayPause,
        toggleFullscreen: toggleFullscreen,
        isVideoActive: function() {
            const vid = getActiveVideo();
            return !!(vid && !vid.paused && vid.currentTime > 0);
        }
    };
})();
