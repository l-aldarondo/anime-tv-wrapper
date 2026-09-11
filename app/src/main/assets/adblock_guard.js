/**
 * AnimeTV Ultra-Lightweight AdBlock & Overlay Neutralizer
 * Injected into every page and frame to eliminate popups, fake verification modals,
 * click-jacking bait, and deceptive overlays.
 */
(function () {
    try {
        var isTop = (window === window.top);

        // 1. Neutralize SoloLatino ad payload script element
        function neutralizeSlAds() {
            try {
                var sl = document.getElementById('__sl_ads');
                if (sl && sl.textContent !== '{"h":"","b":""}') {
                    sl.textContent = '{"h":"","b":""}';
                }
            } catch (e) {}
        }
        neutralizeSlAds();

        // 2. Completely suppress window.open
        try {
            Object.defineProperty(window, 'open', {
                value: function (url) {
                    console.log('AnimeTV Lite: Blocked window.open -> ' + url);
                    return null;
                },
                writable: false,
                configurable: false
            });
        } catch (e) {
            window.open = function () { return null; };
        }

        // 3. Neutralize programmatic anchor clickjacking
        try {
            var origClick = HTMLAnchorElement.prototype.click;
            HTMLAnchorElement.prototype.click = function () {
                var target = this.getAttribute('target') || '';
                var href = this.getAttribute('href') || '';
                if (target === '_blank' || (href && href.indexOf('javascript:') === -1 && href.indexOf('#') !== 0 && !isAllowedInternalLink(href))) {
                    console.log('AnimeTV Lite: Blocked anchor programmatic click -> ' + href);
                    return;
                }
                return origClick.apply(this, arguments);
            };
        } catch (e) {}

        function isAllowedInternalLink(u) {
            if (!u || u === '#' || u.indexOf('/') === 0 || u.indexOf('#') === 0 || u.indexOf('javascript:') === 0) return true;
            var curHost = window.location.hostname;
            return (curHost && u.indexOf(curHost) !== -1) ||
                   u.indexOf('9anime') !== -1 ||
                   u.indexOf('gogoanime') !== -1 ||
                   u.indexOf('sololatino') !== -1 ||
                   u.indexOf('animeflix') !== -1 ||
                   u.indexOf('animeyt') !== -1 ||
                   u.indexOf('jkanime') !== -1 ||
                   u.indexOf('pelisserieshoy') !== -1 ||
                   u.indexOf('embed69') !== -1 ||
                   u.indexOf('voe.sx') !== -1 ||
                   u.indexOf('mega') !== -1;
        }

        // 4. Capture-phase click interceptor
        document.addEventListener('click', function (e) {
            var el = e.target;
            while (el && el !== document.body && el !== document.documentElement) {
                if (el.tagName === 'A') {
                    var href = el.getAttribute('href') || '';
                    var target = el.getAttribute('target') || '';
                    if (target === '_blank' || !isAllowedInternalLink(href)) {
                        e.preventDefault();
                        e.stopPropagation();
                        console.log('AnimeTV Lite: Suppressed external popup link click -> ' + href);
                        return false;
                    }
                }
                el = el.parentElement;
            }
        }, true);

        // 5. Overlay, Bait, and Popup Purging
        function purgeOverlays() {
            neutralizeSlAds();

            // Direct rogue elements
            var badElements = document.querySelectorAll(
                'iframe[src*="adsterra"], iframe[src*="monetag"], iframe[src*="popads"], iframe[src*="propeller"], ' +
                'iframe[src*="furudloof"], iframe[src*="belchlipin"], iframe[src*="doubleclick"], ' +
                'div[data-area], .wrapper[data-area], div[class*="popup"], div[class*="popunder"], ' +
                '.modal-vast, #modal.modal-vast, .tutorial-overlay, [class*="adblock" i], [id*="adblock" i], ' +
                '[class*="antiadblock" i], [class*="ads-modal" i], [class*="ad-warning" i], .D1BnW, [class*="D1BnW"]'
            );
            for (var i = 0; i < badElements.length; i++) {
                try { badElements[i].remove(); } catch (e) {}
            }

            // High z-index transparent overlay neutralizer
            try {
                var allEls = document.querySelectorAll('body *');
                for (var j = 0; j < allEls.length; j++) {
                    var el = allEls[j];
                    if (!el || el.tagName === 'VIDEO' || el.tagName === 'IFRAME') continue;
                    var cs = window.getComputedStyle(el);
                    var z = parseInt(cs.zIndex || '0', 10) || 0;
                    if ((cs.position === 'fixed' || cs.position === 'absolute') && z > 100000) {
                        var rect = el.getBoundingClientRect();
                        if (rect.width >= window.innerWidth * 0.7 && rect.height >= window.innerHeight * 0.7) {
                            // Transparent bait overlay covering the player -> neutralize pointer events
                            el.style.setProperty('pointer-events', 'none', 'important');
                        }
                    }
                }
            } catch (e) {}
        }

        purgeOverlays();
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', purgeOverlays);
        }
        setInterval(purgeOverlays, 1500);

        // 6. Native HTML5 Video Detection & Remote Control Bridge
        function getAllVideos() {
            var vids = Array.prototype.slice.call(document.querySelectorAll('video'));
            return vids;
        }

        window.__animePlayerBridge = {
            toggle: function () {
                var vids = getAllVideos();
                for (var i = 0; i < vids.length; i++) {
                    if (vids[i].paused) vids[i].play(); else vids[i].pause();
                }
                relayCommandToFrames({ action: 'toggle' });
            },
            play: function () {
                var vids = getAllVideos();
                for (var i = 0; i < vids.length; i++) vids[i].play();
                relayCommandToFrames({ action: 'play' });
            },
            pause: function () {
                var vids = getAllVideos();
                for (var i = 0; i < vids.length; i++) vids[i].pause();
                relayCommandToFrames({ action: 'pause' });
            },
            seek: function (secs) {
                var vids = getAllVideos();
                for (var i = 0; i < vids.length; i++) {
                    vids[i].currentTime = Math.max(0, vids[i].currentTime + secs);
                }
                relayCommandToFrames({ action: 'seek', seconds: secs });
            },
            skipIntro: function () {
                var vids = getAllVideos();
                for (var i = 0; i < vids.length; i++) {
                    vids[i].currentTime = Math.max(0, vids[i].currentTime + 85);
                }
                relayCommandToFrames({ action: 'seek', seconds: 85 });
            },
            nextEpisode: function () {
                var nextBtn = document.querySelector('.next-episode, .btn-next, a[rel="next"], .naveps .next, a.next, #next-ep');
                if (nextBtn) { try { nextBtn.click(); } catch (e) {} }
            }
        };

        function relayCommandToFrames(data) {
            var frames = document.querySelectorAll('iframe');
            for (var f = 0; f < frames.length; f++) {
                try { frames[f].contentWindow.postMessage(data, '*'); } catch (e) {}
            }
        }

        window.addEventListener('message', function (e) {
            try {
                var data = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
                if (!data) return;
                var action = data.action || data.command;
                if (action === 'toggle') window.__animePlayerBridge.toggle();
                else if (action === 'play') window.__animePlayerBridge.play();
                else if (action === 'pause') window.__animePlayerBridge.pause();
                else if (action === 'seek') {
                    var s = Number(data.seconds || 0);
                    var vids = getAllVideos();
                    for (var i = 0; i < vids.length; i++) vids[i].currentTime = Math.max(0, vids[i].currentTime + s);
                }
            } catch (e) {}
        });

        // Notify Android of video state
        document.addEventListener('play', function () {
            try { if (window.AndroidBridge && window.AndroidBridge.onVideoPlay) window.AndroidBridge.onVideoPlay(); } catch (e) {}
        }, true);
        document.addEventListener('pause', function () {
            try { if (window.AndroidBridge && window.AndroidBridge.onVideoPause) window.AndroidBridge.onVideoPause(); } catch (e) {}
        }, true);

    } catch (e) {
        console.error('AnimeTV Guard error: ', e);
    }
})();
