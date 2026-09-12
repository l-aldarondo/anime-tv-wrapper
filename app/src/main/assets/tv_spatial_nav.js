/**
 * AnimeTV Spatial Navigation Engine (Version 2.0 - Universal Spotlight Edition)
 * Transforms any web catalog or video player page (SoloLatino, 9Anime, GogoAnime,
 * AnimeFlix, AnimeYT, JKAnime) into a responsive, high-contrast 10-foot TV experience.
 */
(function() {
    if (window.__AnimeTvSpatialNavLoaded) return;
    window.__AnimeTvSpatialNavLoaded = true;

    // ── 1. Comprehensive Interactive Selectors (Cards + Players + Servers + Episodes)
    const TARGET_SELECTORS = [
        // Video Player Containers & Big Play Buttons
        '#player',
        '#main-player-wrap',
        '#player-frame',
        '.player-embed',
        '.wb_-playerarea',
        '#DisplayContent',
        '.mg3-player',
        '#megaplay-player',
        '.video-content',
        '.jwplayer',
        '.plyr',
        '.player_conte',
        '.azaku-player-container',
        '.vjs-big-play-button',
        '.play-button',
        '.play-button-overlay',
        '.btn-play',
        '#play-button',
        '#player-play',
        '.plyr__control--overlaid',
        '.playbtn',
        '[data-plyr="play"]',
        '#fakePlayer',
        '.jw-display-icon-container',
        'video',

        // Server Selection Tabs & Buttons
        '[data-server-btn]',
        '[data-lang-tab]',
        '.server-btn',
        '.player-tab',
        'button[data-server]',
        '.servers-list li',
        '.server',
        '.server-item',
        '.nav-tabs li a',
        '.servers a',
        '.server-item a',

        // Episode Lists & Navigation
        '.episodes-list a',
        '.episodes-nav a',
        '.episodios a',
        '.episodes a',
        'ul.anime-page__episode-list li a',
        '.btn-next',
        '.btn-prev',
        'a[href*="/episodio-"]',
        'a[href*="/episode-"]',
        'a[href*="/ep-"]',
        'a.ep-item',

        // Anime Catalog Cards
        '.flw-item',
        '.film_list-wrap .flw-item',
        '.last_episodes ul.items li',
        'ul.items li',
        'article',
        '.anime-item',
        '.anime__item',
        '.film-poster',
        '.film-list .flw-item',
        '.poster-media',
        '.media-card',
        'a.item',
        'a[href*="/serie/"]',
        'a[href*="/pelicula/"]',
        'a[href*="/anime/"]',
        'a[href*="/watch/"]',
        'a[href*="/ver/"]',
        'a[href*="/episode/"]',
        'a.film-poster-ahref'
    ];

    let currentFocusedElement = null;
    let floatingSpotlight = null;

    // ── 2. Universal TV Spotlight Cursor (Immune to overflow:hidden) ───────────
    function ensureSpotlight() {
        if (!floatingSpotlight || !document.body.contains(floatingSpotlight)) {
            const old = document.getElementById('animetv-tv-focus-cursor');
            if (old) old.remove();

            floatingSpotlight = document.createElement('div');
            floatingSpotlight.id = 'animetv-tv-focus-cursor';
            floatingSpotlight.style.cssText = [
                'position: fixed',
                'top: -9999px',
                'left: -9999px',
                'width: 0px',
                'height: 0px',
                'pointer-events: none',
                'z-index: 2147483647',
                'border: 3.5px solid #00E5FF',
                'outline: 2px solid #FFFFFF',
                'border-radius: 10px',
                'box-shadow: 0 0 30px rgba(0, 229, 255, 0.95), 0 0 12px #ffffff, inset 0 0 15px rgba(0, 229, 255, 0.3)',
                'transition: top 0.12s cubic-bezier(0.2, 0, 0, 1), left 0.12s cubic-bezier(0.2, 0, 0, 1), width 0.12s ease, height 0.12s ease, opacity 0.1s ease',
                'opacity: 0',
                'box-sizing: border-box'
            ].join(' !important;') + ' !important;';

            (document.body || document.documentElement).appendChild(floatingSpotlight);
        }
        return floatingSpotlight;
    }

    function getCardVisualContainer(el) {
        if (!el) return el;
        // If element is inside an anime card container, frame the entire visual card!
        const cardParent = el.closest('.flw-item, article, .film-poster, .anime-item, .anime__item, .poster-media, .media-card, .last_episodes ul.items li, #main-player-wrap, #player-frame, .player-embed, .wb_-playerarea');
        return cardParent || el;
    }

    function updateSpotlightPosition() {
        if (!currentFocusedElement || !document.body.contains(currentFocusedElement)) {
            if (floatingSpotlight) floatingSpotlight.style.opacity = '0';
            return;
        }

        const spotlight = ensureSpotlight();
        const visualTarget = getCardVisualContainer(currentFocusedElement);
        const rect = visualTarget.getBoundingClientRect();

        if (rect.width < 10 || rect.height < 10) {
            spotlight.style.opacity = '0';
            return;
        }

        const pad = 4;
        spotlight.style.top = (rect.top - pad) + 'px';
        spotlight.style.left = (rect.left - pad) + 'px';
        spotlight.style.width = (rect.width + pad * 2) + 'px';
        spotlight.style.height = (rect.height + pad * 2) + 'px';
        spotlight.style.opacity = '1';
    }

    window.addEventListener('scroll', updateSpotlightPosition, { passive: true });
    window.addEventListener('resize', updateSpotlightPosition, { passive: true });

    // ── 3. Spatial Node Discovery ─────────────────────────────────────────────
    function getEligibleElements() {
        const elements = document.querySelectorAll(TARGET_SELECTORS.join(', '));
        const eligible = [];
        const seen = new Set();

        for (let i = 0; i < elements.length; i++) {
            const el = elements[i];
            // Skip top bar headers, ads, or completely hidden nodes
            if (el.closest('#header, header, .top-bar, .navbar, .site-header, #animetv-topbar, .modal-vast, #tutorialOverlay')) {
                continue;
            }

            const style = window.getComputedStyle(el);
            if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') {
                continue;
            }

            const rect = el.getBoundingClientRect();
            if (rect.width < 25 || rect.height < 25) continue;

            // Normalize to clickable target or the container itself
            let target = el;
            if (el.tagName.toLowerCase() !== 'a' && el.tagName.toLowerCase() !== 'button' && el.tagName.toLowerCase() !== 'video') {
                const childBtn = el.querySelector('a, button, video');
                if (childBtn && childBtn.getBoundingClientRect().width > 20) {
                    target = childBtn;
                }
            }

            if (!seen.has(target)) {
                seen.add(target);
                eligible.push({
                    element: target,
                    rect: target.getBoundingClientRect()
                });
            }
        }
        return eligible;
    }

    // ── 4. Focus Management ───────────────────────────────────────────────────
    function setFocus(el) {
        if (!el) return;
        if (currentFocusedElement && currentFocusedElement !== el) {
            currentFocusedElement.classList.remove('animetv-focused-card');
        }
        currentFocusedElement = el;
        el.classList.add('animetv-focused-card');

        try {
            el.focus({ preventScroll: true });
        } catch(e) {}

        // Smoothly bring element into center of TV viewport
        el.scrollIntoView({
            behavior: 'smooth',
            block: 'center',
            inline: 'center'
        });

        updateSpotlightPosition();
    }

    function findInitialFocus() {
        const items = getEligibleElements();
        if (items.length === 0) return null;

        // On watch/episode pages, prioritize the main video player or big play button!
        const playerItem = items.find(i => {
            const tag = i.element.tagName.toLowerCase();
            const cls = i.element.className || '';
            const id = i.element.id || '';
            return tag === 'video' || id.includes('player') || cls.includes('play') || cls.includes('player');
        });

        if (playerItem && playerItem.rect.top >= -50 && playerItem.rect.bottom <= window.innerHeight + 200) {
            return playerItem.element;
        }

        // Otherwise find card closest to top-left of the visible viewport
        let best = items[0];
        let bestDist = Infinity;
        for (let i = 0; i < items.length; i++) {
            const r = items[i].rect;
            if (r.top >= -50 && r.left >= 0) {
                const dist = r.top * 2 + r.left;
                if (dist < bestDist) {
                    bestDist = dist;
                    best = items[i];
                }
            }
        }
        return best.element;
    }

    // ── 5. Cardinal Direction Snapping ────────────────────────────────────────
    function navigate(direction) {
        const items = getEligibleElements();
        if (items.length === 0) {
            if (direction === 'up' && window.AndroidBridge && window.AndroidBridge.onSpatialTopReached) {
                window.AndroidBridge.onSpatialTopReached();
            }
            return false;
        }

        if (!currentFocusedElement || !document.body.contains(currentFocusedElement)) {
            const init = findInitialFocus();
            if (init) setFocus(init);
            return true;
        }

        const currentRect = currentFocusedElement.getBoundingClientRect();
        const curCenterX = currentRect.left + currentRect.width / 2;
        const curCenterY = currentRect.top + currentRect.height / 2;

        let bestCandidate = null;
        let bestScore = Infinity;

        for (let i = 0; i < items.length; i++) {
            const cand = items[i];
            if (cand.element === currentFocusedElement) continue;

            const r = cand.rect;
            const candCenterX = r.left + r.width / 2;
            const candCenterY = r.top + r.height / 2;

            const dx = candCenterX - curCenterX;
            const dy = candCenterY - curCenterY;

            let primaryDist = 0;
            let secondaryDist = 0;
            let isValidDirection = false;

            if (direction === 'right') {
                if (dx > 10) {
                    isValidDirection = true;
                    primaryDist = dx;
                    secondaryDist = Math.abs(dy);
                }
            } else if (direction === 'left') {
                if (dx < -10) {
                    isValidDirection = true;
                    primaryDist = -dx;
                    secondaryDist = Math.abs(dy);
                }
            } else if (direction === 'down') {
                if (dy > 12) {
                    isValidDirection = true;
                    primaryDist = dy;
                    secondaryDist = Math.abs(dx);
                }
            } else if (direction === 'up') {
                if (dy < -12) {
                    isValidDirection = true;
                    primaryDist = -dy;
                    secondaryDist = Math.abs(dx);
                }
            }

            if (isValidDirection) {
                // Weight cross-axis displacement heavily so row alignment is preserved
                const score = primaryDist + secondaryDist * 2.8;
                if (score < bestScore) {
                    bestScore = score;
                    bestCandidate = cand.element;
                }
            }
        }

        if (bestCandidate) {
            setFocus(bestCandidate);
            return true;
        } else {
            // Reached boundary
            if (direction === 'up') {
                // Top row reached -> notify Android to move focus to Top Bar!
                if (window.AndroidBridge && window.AndroidBridge.onSpatialTopReached) {
                    window.AndroidBridge.onSpatialTopReached();
                    return true;
                }
            }
            return false;
        }
    }

    // ── 6. Remote OK Click & Play Trigger ─────────────────────────────────────
    function clickFocused() {
        if (!currentFocusedElement || !document.body.contains(currentFocusedElement)) {
            const init = findInitialFocus();
            if (init) setFocus(init);
            return false;
        }

        const el = currentFocusedElement;
        const spotlight = ensureSpotlight();

        // Pulsing feedback animation
        el.classList.add('animetv-clicked-pulse');
        spotlight.style.transform = 'scale(0.96)';
        spotlight.style.borderColor = '#00E676';
        spotlight.style.boxShadow = '0 0 35px #00E676';

        setTimeout(() => {
            if (el) el.classList.remove('animetv-clicked-pulse');
            if (spotlight) {
                spotlight.style.transform = 'scale(1)';
                spotlight.style.borderColor = '#00E5FF';
                spotlight.style.boxShadow = '0 0 30px rgba(0, 229, 255, 0.95), 0 0 12px #ffffff';
            }
        }, 250);

        // If target is or belongs to player/video, ensure playback initiates
        const isPlayerArea = el.closest('#player, #main-player-wrap, #player-frame, .player-embed, .wb_-playerarea, video, .plyr') !== null;

        try {
            el.click();
            if (isPlayerArea && window.AnimeTvPlayer) {
                setTimeout(() => {
                    try { window.AnimeTvPlayer.togglePlay(); } catch(e) {}
                }, 150);
            }
            return true;
        } catch(e) {
            if (isPlayerArea && window.AnimeTvPlayer) {
                window.AnimeTvPlayer.togglePlay();
                return true;
            }
            return false;
        }
    }

    // ── 7. Public API Exposed to Android WebView ──────────────────────────────
    window.AnimeTvSpatialNav = {
        navigate: navigate,
        click: clickFocused,
        focusInitial: function() {
            const init = findInitialFocus();
            if (init) setFocus(init);
        },
        clearFocus: function() {
            if (currentFocusedElement) {
                currentFocusedElement.classList.remove('animetv-focused-card');
                currentFocusedElement = null;
            }
            if (floatingSpotlight) {
                floatingSpotlight.style.opacity = '0';
            }
        },
        getFocusedElement: function() {
            return currentFocusedElement;
        }
    };

    // Auto-focus on initial load once items are populated
    function autoInit() {
        setTimeout(function() {
            const init = findInitialFocus();
            if (init) setFocus(init);
        }, 500);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', autoInit);
    } else {
        autoInit();
    }
})();
