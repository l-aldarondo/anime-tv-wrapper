// Runs in every frame of every page (all_frames: true, see manifest.json) - including
// cross-origin embed iframes (embed69/vidhide/morencius/etc), which is the whole reason
// this app moved off WebView: evaluateJavascript() could only ever reach the top document,
// so ad-wall purging and player control inside a cross-origin iframe used to require
// re-fetching that iframe's HTML via HttpURLConnection and rewriting it in place - a fragile
// trick that broke outright on providers with anti-bot detection (morencius.com). A real
// content script just runs natively inside each frame's own origin instead.
(function () {
    try {
        var isTop = (window === window.top);

        // 1. Completely neutralize window.open (every frame)
        try {
            Object.defineProperty(window, 'open', {
                value: function (url) {
                    console.log('AnimeTV: Suppressed window.open -> ' + url);
                    return null;
                },
                writable: false,
                configurable: false
            });
        } catch (e) {
            window.open = function (url) {
                console.log('AnimeTV: Suppressed window.open fallback -> ' + url);
                return null;
            };
        }

        function isInternalLink(u) {
            if (!u || u === '#' || u.indexOf('/') === 0 || u.indexOf('#') === 0 || u.indexOf('javascript:') === 0) return true;
            var curHost = window.location.hostname;
            return (curHost && u.indexOf(curHost) !== -1) ||
                   u.indexOf('9anime.or.at') !== -1 ||
                   u.indexOf('gogoanime.by') !== -1 ||
                   u.indexOf('sololatino.net') !== -1 ||
                   u.indexOf('animeflix.team') !== -1 ||
                   u.indexOf('9animes.me.uk') !== -1 ||
                   u.indexOf('animeyt.cc') !== -1 ||
                   u.indexOf('jkanime.net') !== -1 ||
                   u.indexOf('mytsumi.com') !== -1 ||
                   u.indexOf('bysesukior.com') !== -1 ||
                   u.indexOf('embed69.org') !== -1 ||
                   u.indexOf('pelisserieshoy.com') !== -1 ||
                   u.indexOf('mediafire.com') !== -1 ||
                   u.indexOf('morencius.com') !== -1 ||
                   u.indexOf('audinifer.com') !== -1 ||
                   u.indexOf('cloudwindow-route.com') !== -1 ||
                   u.indexOf('minochinos.com') !== -1 ||
                   u.indexOf('ghbrisk.com') !== -1 ||
                   u.indexOf('bysedikamoum.com') !== -1 ||
                   u.indexOf('voe.sx') !== -1 ||
                   u.indexOf('gofile.io') !== -1 ||
                   u.indexOf('sololatino.co') !== -1;
        }

        // 2. Neutralize anchor programmatic click-jacking
        try {
            var origClick = HTMLAnchorElement.prototype.click;
            HTMLAnchorElement.prototype.click = function () {
                var href = this.getAttribute('href') || '';
                var target = this.getAttribute('target') || '';
                if (target === '_blank' || !isInternalLink(href)) {
                    console.log('AnimeTV: Blocked anchor click -> ' + href);
                    return;
                }
                return origClick.apply(this, arguments);
            };
        } catch (e) {}

        // 3. Capture-phase click interceptor (blocks external popup tabs & redirects)
        document.addEventListener('click', function (e) {
            var el = e.target;
            while (el && el !== document.body) {
                if (el.tagName === 'A') {
                    var href = el.getAttribute('href') || '';
                    var target = el.getAttribute('target') || '';
                    if (target === '_blank' || !isInternalLink(href)) {
                        e.preventDefault();
                        e.stopPropagation();
                        console.log('AnimeTV: Suppressed external popup link click -> ' + href);
                        return false;
                    }
                }
                el = el.parentElement;
            }
        }, true);

        // SoloLatino serves its ad payload as a <script id="__sl_ads" type="application/json">
        // element the page reads by id and JSON.parses directly - clearing window.__sl_ads does
        // nothing, the element's own content has to be cleared, and re-cleared since a server
        // switch re-renders it with a fresh payload.
        function neutralizeSlAdsElement() {
            try {
                var slAdsEl = document.getElementById('__sl_ads');
                if (slAdsEl && slAdsEl.textContent !== '{"h":"","b":""}') {
                    slAdsEl.textContent = '{"h":"","b":""}';
                }
            } catch (e) {}
        }

        // Any <img> whose source was blocked at the network level (uBlock Origin) still leaves
        // the browser's broken-image placeholder behind - hide any image that fails to load.
        try {
            document.addEventListener('error', function (e) {
                if (e.target && e.target.tagName === 'IMG') {
                    try { e.target.style.setProperty('display', 'none', 'important'); } catch (err) {}
                }
            }, true);
        } catch (e) {}

        // A near-fullscreen/full-player-sized element with no distinguishing content is
        // ambiguous - some players legitimately use a transparent full-cover div as their own
        // click-to-play/gesture-unlock layer, and deleting it outright has been observed to leave
        // the player with no way to start playback at all. Only delete small, clearly-decorative
        // bait cards; for anything close to full player size, just strip pointer-events so a real
        // tap passes through to whatever real control sits beneath it.
        function neutralizeOverlay(el) {
            try {
                var rect = el.getBoundingClientRect();
                var coversMost = rect.width >= window.innerWidth * 0.75 && rect.height >= window.innerHeight * 0.75;
                if (coversMost) {
                    el.style.setProperty('pointer-events', 'none', 'important');
                } else {
                    el.remove();
                }
            } catch (e) {}
        }

        // Remove adblock-detection warning walls, generic click-catcher overlays, and rotating ad
        // creatives. Runs in every frame - this used to be two separate, drifting copies (one for
        // the top document, one injected per sanitized iframe); now there's exactly one.
        function purgeOverlays() {
            neutralizeSlAdsElement();
            // Remove fake robot verification / notification / loading / APK download prompts
            var botCards = document.querySelectorAll('div, section, dialog');
            for (var bc = 0; bc < botCards.length; bc++) {
                var bcTxt = (botCards[bc].innerText || '').toLowerCase();
                if ((bcTxt.indexOf('not a robot') !== -1 || bcTxt.indexOf('kindly verify') !== -1 || bcTxt.indexOf('robot') !== -1 || bcTxt.indexOf('need to "allow"') !== -1 || bcTxt.indexOf('need to allow') !== -1 || (bcTxt.indexOf('loading...') !== -1 && bcTxt.indexOf('allow') !== -1) || bcTxt.indexOf("we're ready") !== -1 || bcTxt.indexOf('file_download.apk') !== -1 || (bcTxt.indexOf('.apk') !== -1 && bcTxt.indexOf('download') !== -1)) && (bcTxt.indexOf('attention') !== -1 || bcTxt.indexOf('cancel') !== -1 || bcTxt.indexOf('allow') !== -1 || bcTxt.indexOf('continue') !== -1 || bcTxt.indexOf('ok') !== -1 || bcTxt.indexOf('download') !== -1)) {
                    try { botCards[bc].remove(); } catch (e) {}
                }
            }

            // Rogue elements attached directly to <html> (fake robot modals, skip-ad overlays)
            var directBad = document.querySelectorAll('html > iframe, html > div, .D1BnW, [class*="D1BnW"]');
            for (var db = 0; db < directBad.length; db++) {
                try { directBad[db].remove(); } catch (e) {}
            }

            // Known ad-network iframes & popup/popunder wrappers (never touches player/video iframes)
            var badElements = document.querySelectorAll('iframe[src*="furudloof"], iframe[src*="belchlipin"], iframe[src*="adsterra"], iframe[src*="monetag"], iframe[src*="popads"], iframe[src*="propeller"], iframe[src*="buildsstate"], iframe[src*="oxserver"], iframe[src*="excavatenearbywand"], iframe[src*="doubleclick"], div[data-area], .wrapper[data-area], div[class*="popup"], div[class*="popunder"], #modal.modal-vast, .modal-vast, .tutorial-overlay, div[class*="tutorial"], [class*="adblock" i], [id*="adblock" i], [class*="ad-block" i], [class*="antiadblock" i], [class*="disable-ad" i], [class*="social-bar" i], [class*="sociallocker" i], [class*="ads-modal" i], [class*="ad-modal" i], [class*="ad-warning" i]');
            for (var i = 0; i < badElements.length; i++) {
                try { badElements[i].remove(); } catch (e) {}
            }

            // Adblock-detection warning walls and clickbait ad banners, by keyword ("this video has
            // popups/ads", "disable your ad blocker", the green "Click this button" banner, etc.)
            try {
                var adWallTextRe = /anuncio|publicidad|adblock|ad.?block|ventana.?emergente|ventanas.?emergentes|pop.?up|bloqueador|desactiva.*(ad|anuncio)|click this button|click here to continue|you.?ve won|you have won|claim your (prize|reward)|verify you.?re human/i;
                var textCandidates = document.querySelectorAll('div, section, aside, p, a, button');
                for (var tc = 0; tc < textCandidates.length; tc++) {
                    var tEl = textCandidates[tc];
                    if (!tEl || tEl.tagName === 'VIDEO' || (tEl.querySelector && tEl.querySelector('video'))) continue;
                    var tTxt = (tEl.innerText || tEl.textContent || '').trim();
                    if (!tTxt || tTxt.length > 400 || !adWallTextRe.test(tTxt)) continue;
                    var tCs = window.getComputedStyle(tEl);
                    var tZ = parseInt(tCs.zIndex || '0', 10) || 0;
                    if (tCs.position === 'fixed' || tCs.position === 'absolute' || tZ > 100 || tEl.offsetWidth > window.innerWidth * 0.5) {
                        try { tEl.remove(); } catch (e) {}
                    }
                }
            } catch (e) {}

            // Generic ad interstitial/modal detector: rather than chase every new ad creative's
            // exact wording, catch the STRUCTURE most of them share - a large fixed/absolute overlay
            // darkening most of the screen with a prominent generic call-to-action button.
            try {
                var adCtaRe = /^(continue|continuar|discover|explore|claim|unlock|get started|watch now|start now|download now|install now|join now|sign up|allow|next)$/i;
                var modalCandidates = document.querySelectorAll('div');
                for (var am = 0; am < modalCandidates.length; am++) {
                    var mEl = modalCandidates[am];
                    if (!mEl || (mEl.querySelector && mEl.querySelector('video'))) continue;
                    if (mEl.id === 'trailer-modal' || mEl.id === 'auth-modal' || (mEl.className && mEl.className.toString().indexOf('auth-modal') !== -1)) continue;
                    var mCs = window.getComputedStyle(mEl);
                    if (mCs.position !== 'fixed' && mCs.position !== 'absolute') continue;
                    var mRect = mEl.getBoundingClientRect();
                    if (mRect.width < window.innerWidth * 0.7 || mRect.height < window.innerHeight * 0.5) continue;
                    var ctaEls = mEl.querySelectorAll('button, a, [role="button"]');
                    var hasAdCta = false;
                    for (var ci = 0; ci < ctaEls.length; ci++) {
                        var ctaTxt = (ctaEls[ci].innerText || '').trim();
                        if (adCtaRe.test(ctaTxt)) { hasAdCta = true; break; }
                    }
                    if (hasAdCta) {
                        try { mEl.remove(); } catch (e) {}
                    }
                }
            } catch (e) {}

            // Smaller ad cards: a positioned card with 2+ short generic buttons/links plus a short
            // marketing-style blurb, not wrapping the actual player - too generically-worded to
            // safely text-match ("More", "Close"), but reliable by structure.
            try {
                var cardCandidates = document.querySelectorAll('div');
                for (var ac = 0; ac < cardCandidates.length; ac++) {
                    var cEl = cardCandidates[ac];
                    if (!cEl || (cEl.querySelector && (cEl.querySelector('video') || cEl.querySelector('iframe')))) continue;
                    if (cEl.id === 'trailer-modal' || cEl.id === 'auth-modal' || (cEl.className && cEl.className.toString().indexOf('auth-modal') !== -1)) continue;
                    var cCs = window.getComputedStyle(cEl);
                    if (cCs.position !== 'fixed' && cCs.position !== 'absolute') continue;
                    var cRect = cEl.getBoundingClientRect();
                    if (cRect.width < 150 || cRect.height < 80) continue;
                    var cActions = cEl.querySelectorAll(':scope > button, :scope > a, :scope > div > button, :scope > div > a');
                    if (cActions.length < 2) continue;
                    var cTxt = (cEl.innerText || '').trim();
                    if (cTxt.length < 15 || cTxt.length > 250) continue;
                    try { cEl.remove(); } catch (e) {}
                }
            } catch (e) {}

            // "Nuked z-index" bait overlays: a position:fixed element (src-less iframe or plain div)
            // with a z-index right at the 32-bit signed int max, directly on top of the real player.
            // Size still decides delete vs. neutralize - one observed instance turned out to be the
            // player's own full-cover click-to-play layer, not an ad.
            try {
                var extremeZEls = document.querySelectorAll('body *');
                for (var ez = 0; ez < extremeZEls.length; ez++) {
                    var eEl = extremeZEls[ez];
                    if (!eEl || eEl.tagName === 'VIDEO' || (eEl.querySelector && eEl.querySelector('video'))) continue;
                    var eCs = window.getComputedStyle(eEl);
                    var eZ = parseInt(eCs.zIndex || '0', 10) || 0;
                    if ((eCs.position === 'fixed' || eCs.position === 'absolute') && eZ > 2000000000) {
                        neutralizeOverlay(eEl);
                    }
                }
            } catch (e) {}

            // Auto-dismiss tutorial steps (e.g. embed69 "Cambiar Idioma") and misc dialogs/cookies
            var skipBtns = document.querySelectorAll('button, a, div');
            for (var sk = 0; sk < skipBtns.length; sk++) {
                var otxt = (skipBtns[sk].innerText || '').trim().toLowerCase();
                if (otxt === 'omitir') {
                    try { skipBtns[sk].click(); } catch (e) {}
                }
            }
            var dismissBtns = document.querySelectorAll('button[data-aniyt-cookie-dismiss], [data-abismo-login-dialog] .close, [aria-label="Cerrar"]');
            for (var d = 0; d < dismissBtns.length; d++) {
                try { dismissBtns[d].click(); } catch (e) {}
            }
            var allBtns = document.querySelectorAll('button');
            for (var b = 0; b < allBtns.length; b++) {
                if (allBtns[b].innerText && allBtns[b].innerText.trim().toLowerCase() === 'entendido') {
                    try { allBtns[b].click(); } catch (e) {}
                }
            }

            // SoloLatino: dismiss the auth modal and visually de-emphasize paid-tier server buttons
            // (Premium/VIP) - no auto-clicking (see git history: every attempt at that caused a
            // reload loop or interrupted an already-playing free server). The user picks their own
            // server from the visible buttons, same as always; this only makes the paid ones look
            // less like the right choice.
            if (window.location.hostname.indexOf('sololatino.net') !== -1) {
                var authM = document.getElementById('auth-modal') || document.querySelector('.auth-modal');
                if (authM) { try { authM.remove(); } catch (e) {} }

                var paidTierRe = /premium|\bvip\b/i;
                var allServerBtns = document.querySelectorAll('button[data-server-btn], .server-btn');
                for (var s = 0; s < allServerBtns.length; s++) {
                    var bTxt = (allServerBtns[s].innerText || '').trim();
                    if (paidTierRe.test(bTxt)) {
                        try { allServerBtns[s].style.setProperty('opacity', '0.45', 'important'); } catch (e) {}
                    }
                }
            }

            // NOTE: this used to auto-click .play-button-overlay / force-call
            // showPlayerInterface() here. purgeOverlays() re-runs on every DOM mutation plus a
            // 4s safety poll, so that synthetic click could fire repeatedly while the real
            // player library (jwplayer/plyr/etc.) was still mid-initialization - racing its own
            // setup and leaving a player with no working play button. Confirmed via isolation
            // testing (disabling this whole content script fixed playback where it was otherwise
            // broken) that this - not uBlock, not GeckoView, not the site itself - was the actual
            // cause. Removed; the user taps the real play button like on desktop.
            var vastModal = document.getElementById('modal');
            if (vastModal && (vastModal.classList.contains('modal-vast') || vastModal.className.indexOf('vast') !== -1)) {
                try { vastModal.remove(); } catch (e) {}
            }

            if (isTop) {
                // Hide main page Watch Now carousel/slider (both 9anime and gogoanime)
                var carousels = document.querySelectorAll('.owl-carousel, .carousel-wrap, #carousel, .slider-movies, .top-slider, .slidtop');
                for (var c = 0; c < carousels.length; c++) {
                    carousels[c].style.setProperty('display', 'none', 'important');
                    carousels[c].style.setProperty('height', '0px', 'important');
                }

                // Remove '9anime is back' announcement banner
                var banners = document.querySelectorAll('.ts-announcement, .ts-announcement-general, div[class*="ts-announcement"], div[class*="announcement"], .notice-bar, .domain-alert, #notice');
                for (var b2 = 0; b2 < banners.length; b2++) {
                    try { banners[b2].remove(); } catch (e) {}
                }

                // Ensure the found iframe player has allowfullscreen
                var ifr = findPlayerIframe();
                if (ifr) {
                    if (!ifr.hasAttribute('allowfullscreen')) {
                        ifr.setAttribute('allowfullscreen', 'true');
                    }
                    var allow = ifr.getAttribute('allow') || '';
                    if (allow.indexOf('fullscreen') === -1) {
                        ifr.setAttribute('allow', 'autoplay; fullscreen *; encrypted-media *');
                    }
                }

                // Neutralize target="_blank" external anchors
                var blankAnchors = document.querySelectorAll('a[target="_blank"]');
                for (var a = 0; a < blankAnchors.length; a++) {
                    var anc = blankAnchors[a];
                    var h = anc.getAttribute('href') || '';
                    if (!isInternalLink(h)) {
                        anc.removeAttribute('target');
                        anc.setAttribute('href', 'javascript:void(0)');
                    }
                }
            }
        }

        purgeOverlays();

        // React to DOM insertions immediately instead of polling on a fixed interval, which
        // shortens the "ad flash" window from up to 1s down to effectively 0.
        try {
            var purgeScheduled = false;
            var scheduledPurge = function () {
                if (purgeScheduled) return;
                purgeScheduled = true;
                var run = function () {
                    purgeScheduled = false;
                    purgeOverlays();
                };
                if (window.requestAnimationFrame) requestAnimationFrame(run);
                else setTimeout(run, 16);
            };
            var observerTarget = document.documentElement || document.body;
            if (observerTarget && window.MutationObserver) {
                var mo = new MutationObserver(scheduledPurge);
                mo.observe(observerTarget, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['style', 'class', 'src']
                });
            }
        } catch (e) {}

        // Safety-net slow poll in case something evades the MutationObserver
        setInterval(purgeOverlays, 4000);

        // ---- Player control - each frame controls whatever local <video>/JWPlayer/Artplayer/Plyr
        // instance is actually in that frame's own document ----

        function getMedia() {
            return document.querySelector('video') || document.querySelector('audio');
        }
        function getPlayBtn() {
            return document.getElementById('azakuPlayButton') ||
                   document.querySelector('.azaku-player-button') ||
                   document.querySelector('.play-button-overlay') ||
                   document.querySelector('.plyr__control--overlaid') ||
                   document.querySelector('button[data-plyr="play"]') ||
                   document.querySelector('.play-btn') ||
                   document.querySelector('.play-button') ||
                   document.querySelector('.jw-display-icon-display') ||
                   document.querySelector('.jw-icon-playback') ||
                   document.querySelector('.art-state') ||
                   document.querySelector('.art-control-playAndPause');
        }
        function getJw() {
            try {
                if (window.jwplayer && typeof window.jwplayer === 'function') {
                    return window.jwplayer();
                }
            } catch (e) {}
            return null;
        }
        function getArt() {
            try {
                if (window.art && typeof window.art.play === 'function') {
                    return window.art;
                }
            } catch (e) {}
            return null;
        }

        function doPlay() {
            var jw = getJw();
            if (jw && typeof jw.play === 'function') {
                try { jw.play(); } catch (e) {}
            }
            var art = getArt();
            if (art) {
                try { art.play(); } catch (e) {}
            }
            var v = getMedia();
            var btn = getPlayBtn();
            if (v) {
                try {
                    if (v.plyr && typeof v.plyr.play === 'function') v.plyr.play();
                    else v.play().catch(function () {});
                } catch (e) {
                    try { v.play(); } catch (e2) {}
                }
            }
            if (btn && (!v || v.paused)) {
                try { btn.click(); } catch (e) {}
            }
        }

        function doPause() {
            var jw = getJw();
            if (jw && typeof jw.pause === 'function') {
                try { jw.pause(); } catch (e) {}
            }
            var art = getArt();
            if (art) {
                try { art.pause(); } catch (e) {}
            }
            var v = getMedia();
            if (v) {
                try {
                    if (v.plyr && typeof v.plyr.pause === 'function') v.plyr.pause();
                } catch (e) {}
                try { v.pause(); } catch (e) {}
                if (!v.paused) {
                    var btn = document.querySelector('button[data-plyr="play"]');
                    if (btn) try { btn.click(); } catch (e) {}
                }
            }
        }

        var lastToggleTime = 0;
        function doToggle() {
            var now = Date.now();
            if (now - lastToggleTime < 300) return;
            lastToggleTime = now;

            var jw = getJw();
            if (jw && typeof jw.getState === 'function') {
                try {
                    var state = jw.getState();
                    if (state === 'playing') doPause(); else doPlay();
                    return;
                } catch (e) {}
            }
            var art = getArt();
            if (art) {
                try {
                    if (art.playing) art.pause(); else art.play();
                    return;
                } catch (e) {}
            }
            var v = getMedia();
            if (v) {
                if (v.paused) doPlay(); else doPause();
            } else {
                var btn = getPlayBtn();
                if (btn) btn.click();
            }
        }

        function doSeek(seconds) {
            var jw = getJw();
            if (jw && typeof jw.getPosition === 'function' && typeof jw.seek === 'function') {
                try {
                    var pos = jw.getPosition();
                    var dur = typeof jw.getDuration === 'function' ? jw.getDuration() : 999999;
                    jw.seek(Math.max(0, Math.min(dur, pos + seconds)));
                    return;
                } catch (e) {}
            }
            var art = getArt();
            if (art && (typeof art.seek === 'function' || typeof art.currentTime === 'number')) {
                try {
                    if (typeof art.seek === 'function') art.seek(Math.max(0, (art.currentTime || 0) + seconds));
                    else art.currentTime = Math.max(0, art.currentTime + seconds);
                    return;
                } catch (e) {}
            }
            var v = getMedia();
            if (!v) return;
            try {
                if (v.plyr && typeof v.plyr.currentTime !== 'undefined') {
                    v.plyr.currentTime = Math.max(0, Math.min(v.plyr.duration || 999999, v.plyr.currentTime + seconds));
                } else {
                    v.currentTime = Math.max(0, Math.min(v.duration || 999999, v.currentTime + seconds));
                }
            } catch (e) {
                try { v.currentTime += seconds; } catch (err) {}
            }
        }

        // A command reaches a frame either via a nested-iframe postMessage cascade (below) or,
        // for the top frame only, relayed from native through the extension's own message channel
        // (see the bottom of this file) - act on this frame's own player, then cascade to children.
        function handleCommand(data) {
            var subframes = document.querySelectorAll('iframe');
            for (var k = 0; k < subframes.length; k++) {
                try { subframes[k].contentWindow.postMessage(data, '*'); } catch (err) {}
            }
            var action = data.action || data.type || data.command;
            if (action === 'toggle' || action === 'play-pause') {
                doToggle();
            } else if (action === 'play') {
                doPlay();
            } else if (action === 'pause') {
                doPause();
            } else if (action === 'seek') {
                var secs = Number(data.seconds != null ? data.seconds : data.value);
                if (!isNaN(secs)) doSeek(secs);
            } else if (action === 'next-episode') {
                var nextBtn = document.querySelector('.next-episode, .btn-next, a[rel="next"], .naveps .next, a.next');
                if (nextBtn) { try { nextBtn.click(); } catch (e) {} }
            } else if (action === 'prev-episode') {
                var prevBtn = document.querySelector('.prev-episode, .btn-prev, a[rel="prev"], .naveps .prev, a.prev');
                if (prevBtn) { try { prevBtn.click(); } catch (e) {} }
            }
        }

        window.addEventListener('message', function (e) {
            try {
                var data = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
                if (!data) return;
                if (data.action || data.command || data.type === 'toggle' || data.type === 'play' || data.type === 'pause' || data.type === 'seek') {
                    handleCommand(data);
                    return;
                }
                // Play-detection postMessage from nested players (plyr/megacloud style)
                if (data.event === 'play' || data.event === 'playing' || data.status === 'playing') {
                    notifyPlay();
                }
            } catch (ignore) {}
        }, false);

        function isSoloLatinoPage() {
            var h = (window.location.hostname || '').toLowerCase();
            return h.indexOf('sololatino') !== -1 || h.indexOf('pelisserieshoy') !== -1 || h.indexOf('embed69') !== -1;
        }

        function notifyPlay() {
            // Disabled auto-fullscreen for SoloAnime / SoloStream per user request
            if (isSoloLatinoPage()) return;

            try { browser.runtime.sendMessage({ type: 'anime-video-play' }); } catch (e) {}
            if (isTop && window.expandPlayerFullscreen) {
                window.expandPlayerFullscreen(true);
            }
        }
        document.addEventListener('play', notifyPlay, true);
        document.addEventListener('playing', notifyPlay, true);
        // NOTE: an earlier version of this also treated any click/touchstart inside an embed
        // iframe as a play signal - that was a workaround from the old WebView-based
        // architecture, where evaluateJavascript() couldn't see real play/playing events firing
        // inside a cross-origin iframe at all, only a same-origin top document. A content script
        // has no such restriction (it runs natively inside the iframe's own origin), so it can
        // just listen for the real event directly. Keeping the click/touchstart proxy on top of
        // that made every tap on the player - including the very first tap that starts it -
        // immediately fire the fullscreen request before the video had actually started,
        // confirmed on-device: the page visually snapped into a broken "fullscreen" layout and
        // playback never started.

        function findPlayerWrap() {
            return document.querySelector('.player-wrap') ||
                   document.querySelector('.wb_-playerarea') ||
                   document.getElementById('player-embed') ||
                   document.querySelector('.player-embed') ||
                   document.querySelector('#player') ||
                   document.querySelector('#player-container') ||
                   document.querySelector('.video-content') ||
                   document.querySelector('#main-player-wrap') ||
                   document.querySelector('#player-section') ||
                   document.querySelector('.mg-3mb3d') ||
                   document.querySelector('.mg3-player') ||
                   document.querySelector('.azaku-player-container') ||
                   document.querySelector('.player_conte');
        }
        function findPlayerIframe() {
            return document.getElementById('iframe-embed') ||
                   document.querySelector('.player-embed iframe') ||
                   document.querySelector('iframe.player-iframe') ||
                   document.querySelector('iframe[src*="player"]') ||
                   document.querySelector('iframe[src*="embed"]') ||
                   document.querySelector('iframe[src*="megaplay"]') ||
                   document.getElementById('player-frame') ||
                   document.querySelector('iframe[src*="mytsumi"]') ||
                   document.querySelector('iframe[src*="embed69"]') ||
                   document.querySelector('iframe[src*="xupalace"]') ||
                   document.querySelector('iframe#iframePlayer') ||
                   document.querySelector('iframe.player_conte') ||
                   document.querySelector('iframe[src*="jkplayer"]');
        }

        // Double-tap toggles fullscreen. In the top document this is scoped to the player-wrap
        // area only (otherwise it'd hijack double-taps on unrelated page content); inside an
        // embed iframe the whole frame IS the player, so no scoping is needed there.
        // touch-action:manipulation (applied below) plus preventDefault() here keep this from
        // also triggering the engine's native double-tap-to-zoom on the same gesture. Guarded so
        // a re-injected script never stacks a second listener and fires the toggle twice per tap
        // (content scripts only run once per frame load, but this mirrors the old guard anyway).
        if (!window.__animeDblTapInstalled) {
            window.__animeDblTapInstalled = true;
            var lastPlayerTap = 0;
            document.addEventListener('touchend', function (e) {
                if (isTop) {
                    var wrap = findPlayerWrap();
                    if (!wrap) return;
                    try { wrap.style.setProperty('touch-action', 'manipulation'); } catch (ignore) {}
                    var t = e.target;
                    var insideWrap = false;
                    while (t) { if (t === wrap) { insideWrap = true; break; } t = t.parentElement; }
                    if (!insideWrap) return;
                }
                var now = Date.now();
                if (now - lastPlayerTap < 350) {
                    e.preventDefault();
                    lastPlayerTap = 0;
                    try { browser.runtime.sendMessage({ type: 'anime-doubletap' }); } catch (err) {}
                } else {
                    lastPlayerTap = now;
                }
            }, { passive: false, capture: true });
        }
        if (!isTop) {
            try { document.documentElement.style.setProperty('touch-action', 'manipulation'); } catch (e) {}
        }

        if (isTop) {
            // Expose fullscreen expand/collapse - called from notifyPlay() above and from the
            // native bridge below.
            window.expandPlayerFullscreen = function (enable) {
                var wrap = findPlayerWrap();
                var ifr = findPlayerIframe();
                if (enable) {
                    if (wrap) wrap.classList.add('animetv-fullscreen-wrap');
                    if (ifr) {
                        try {
                            if (ifr.requestFullscreen) ifr.requestFullscreen().catch(function () {});
                            else if (ifr.webkitRequestFullscreen) ifr.webkitRequestFullscreen();
                        } catch (e) {}
                    }
                } else {
                    if (wrap) wrap.classList.remove('animetv-fullscreen-wrap');
                    try {
                        if (document.exitFullscreen) document.exitFullscreen().catch(function () {});
                        else if (document.webkitExitFullscreen) document.webkitExitFullscreen();
                    } catch (e) {}
                }
            };

            // Commands relayed from native (play/pause/seek/fullscreen) - see background.js.
            if (typeof browser !== 'undefined' && browser.runtime && browser.runtime.onMessage) {
                browser.runtime.onMessage.addListener(function (message) {
                    if (!message) return;
                    if (message.type === 'anime-player-command') {
                        handleCommand(message.payload || {});
                    } else if (message.type === 'anime-set-fullscreen') {
                        window.expandPlayerFullscreen(!!message.enabled);
                    }
                });
            }
        }
    } catch (e) {
        console.error('Anime TV Page Patches error: ', e);
    }
})();
