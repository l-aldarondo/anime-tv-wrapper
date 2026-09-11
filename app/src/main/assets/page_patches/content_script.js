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

        // SoloLatino serves its ad payload as a <script id="__sl_ads" type="application/json">
        // element the page reads by id and JSON.parses directly - clearing window.__sl_ads does
        // nothing, the element's own content has to be cleared, and re-cleared since a server
        // switch re-renders it with a fresh payload. Defined and called FIRST, before anything
        // else in this file, and outside of purgeOverlays()'s later passes: the page's own inline
        // script reads this element and synchronously injects real <script> tags from its payload
        // exactly once (guarded by a one-shot flag on the page's side), so this only has one
        // chance to win the race - run_at: document_start (manifest.json) gives it the earliest
        // possible shot. purgeOverlays() still calls this again on its own schedule below in case
        // the element is re-rendered later (e.g. on a server-tab switch).
        function neutralizeSlAdsElement() {
            try {
                var slAdsEl = document.getElementById('__sl_ads');
                if (slAdsEl && slAdsEl.textContent !== '{"h":"","b":""}') {
                    slAdsEl.textContent = '{"h":"","b":""}';
                }
            } catch (e) {}
        }
        neutralizeSlAdsElement();

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

        // 1b. Neutralize Disqus embed-script injection (9anime.or.at episode/watch pages). The
        // page's own inline script does d.head.appendChild(s) synchronously as the HTML parser
        // reaches it - that dispatches the network request to disqus.com immediately, before any
        // MutationObserver callback (including purgeOverlays() below) gets a chance to react, so
        // CSS display:none on the comment container alone can't stop it. Overriding appendChild/
        // insertBefore here (document_start, before the page's own scripts run) wins the race.
        try {
            var origAppendChild = Node.prototype.appendChild;
            Node.prototype.appendChild = function (node) {
                if (node && node.tagName === 'SCRIPT' && typeof node.src === 'string' && node.src.indexOf('disqus.com/embed.js') !== -1) {
                    console.log('AnimeTV: Suppressed Disqus embed.js injection -> ' + node.src);
                    return node;
                }
                return origAppendChild.call(this, node);
            };
            var origInsertBefore = Node.prototype.insertBefore;
            Node.prototype.insertBefore = function (node, ref) {
                if (node && node.tagName === 'SCRIPT' && typeof node.src === 'string' && node.src.indexOf('disqus.com/embed.js') !== -1) {
                    console.log('AnimeTV: Suppressed Disqus embed.js injection -> ' + node.src);
                    return node;
                }
                return origInsertBefore.call(this, node, ref);
            };
        } catch (e) {}

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
            // Remove fake robot verification / notification / loading / APK download prompts.
            // This had no size/length bound at all - since it checks EVERY div/section/dialog at
            // every nesting level (not just small leaf popups) via .innerText (which aggregates
            // ALL descendant text), a large, completely legitimate ancestor section could satisfy
            // both halves of the condition purely by coincidence: a real Cloudflare Turnstile
            // widget rendering the word "robot" ANYWHERE on the page, combined with a large parent
            // that also happens to contain one of the very common action words ("download", "ok",
            // "continue", "allow" - virtually guaranteed to appear somewhere on a real streaming
            // page with per-episode download buttons) ANYWHERE within it, e.g. an episode list's
            // whole wrapping section. Capping the text length keeps this scoped to small,
            // popup-sized content the way it was actually meant to be, matching the same 400-char
            // bound already used below for the ad-wall text detector.
            var botCards = document.querySelectorAll('div, section, dialog');
            for (var bc = 0; bc < botCards.length; bc++) {
                var bcRawTxt = botCards[bc].innerText || '';
                if (bcRawTxt.length > 400) continue;
                var bcTxt = bcRawTxt.toLowerCase();
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
                    // A real ad wall is virtually always taken out of normal document flow via
                    // fixed/absolute positioning to sit on top of content - require that as a
                    // baseline, then use z-index/width only as a secondary signal on TOP of that.
                    // Previously any one of these four alone was enough, which meant a plain
                    // in-flow page section (a season's episode-list wrapper, a synopsis paragraph,
                    // anything comfortably over half the viewport wide - which describes most main
                    // content on a phone/TV layout) could get removed outright just for
                    // coincidentally containing one of this regex's fairly generic words/phrases.
                    var isPositionedOverlay = tCs.position === 'fixed' || tCs.position === 'absolute';
                    if (isPositionedOverlay && (tZ > 100 || tEl.offsetWidth > window.innerWidth * 0.5)) {
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
            // sololatino.net's own trailer-close and auth-modal-close buttons both happen to
            // carry aria-label="Cerrar" (Spanish "Close") - the same generic label this sweep
            // targets for OTHER sites' cookie/tutorial dialogs. Opening the trailer sets a class
            // on #trailer-modal, which is inside this observer's own attributeFilter, so the very
            // next purgeOverlays() pass (next animation frame) found #trailer-close via this
            // selector and clicked it - auto-closing the trailer the instant it opened. Excluding
            // both modals' own controls here, same idea as the id checks already used elsewhere
            // in this file for these two ids.
            var dismissBtns = document.querySelectorAll('button[data-aniyt-cookie-dismiss], [data-abismo-login-dialog] .close, [aria-label="Cerrar"]');
            for (var d = 0; d < dismissBtns.length; d++) {
                if (dismissBtns[d].closest && dismissBtns[d].closest('#trailer-modal, #auth-modal')) continue;
                try { dismissBtns[d].click(); } catch (e) {}
            }
            var allBtns = document.querySelectorAll('button');
            for (var b = 0; b < allBtns.length; b++) {
                if (allBtns[b].innerText && allBtns[b].innerText.trim().toLowerCase() === 'entendido') {
                    try { allBtns[b].click(); } catch (e) {}
                }
            }

            // SoloLatino: visually de-emphasize paid-tier server buttons (Premium/VIP) - no
            // auto-clicking (see git history: every attempt at that caused a reload loop or
            // interrupted an already-playing free server). The user picks their own server from
            // the visible buttons, same as always; this only makes the paid ones look less like
            // the right choice.
            // NOTE: this used to also force-remove #auth-modal here (plus a matching fixes.css
            // display:none rule). That's the site's real login dialog - already `hidden` by
            // default in its own markup, only unhidden by the site's own JS when an account
            // action needs it (e.g. clicking Favorito/Mi Lista/Vista while logged out gets a 401,
            // and the response handler calls window.showAuthModal()). Removing it meant those
            // clicks still fired their request but the resulting login prompt never appeared -
            // "if I click on them nothing happens." Left alone now; it stays invisible on its own
            // whenever the site itself has no reason to show it.
            if (window.location.hostname.indexOf('sololatino.net') !== -1) {
                // sololatino.net's real <footer> has no unique class/id (plain Tailwind utility
                // classes, confirmed live) - a bare `footer` CSS selector in fixes.css would be
                // unsafe there since that file applies to every third-party player iframe this
                // app loads too (all_frames + <all_urls>), so it's removed via JS instead,
                // hostname-scoped like everything else in this block.
                var slFooter = document.querySelector('footer');
                if (slFooter) { try { slFooter.remove(); } catch (e) {} }

                var paidTierRe = /premium|\bvip\b/i;
                var allServerBtns = document.querySelectorAll('button[data-server-btn], .server-btn');
                for (var s = 0; s < allServerBtns.length; s++) {
                    var bTxt = (allServerBtns[s].innerText || '').trim();
                    if (paidTierRe.test(bTxt)) {
                        try { allServerBtns[s].style.setProperty('opacity', '0.45', 'important'); } catch (e) {}
                    }
                }
            }

            // jkanime.net: the fixes.css rule for #adangle-pop-iframe-container hides it, but it
            // sits nested inside the real comments widget (#jk_thread) and the page's own CSS has
            // to fight its layout impact there (confirmed via the site's own inline style rule
            // targeting it) - remove it outright rather than relying on display:none alone, in
            // case it still reserves space inside that widget.
            if (window.location.hostname.indexOf('jkanime.net') !== -1) {
                var adangle = document.getElementById('adangle-pop-iframe-container');
                if (adangle) { try { adangle.remove(); } catch (e) {} }
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
            // #player-frame is a CONTAINER div in SoloLatino's real markup (the server-selection
            // JS injects the actual <iframe> inside it), not the iframe itself - it used to sit
            // in the middle of this OR-chain, which meant getElementById('player-frame') matched
            // and returned that bare div before several of the more specific iframe[src*=...]
            // checks below it ever got a chance to run. Prefer any real iframe nested inside it
            // first, and only fall back to the bare div (e.g. a <video>/mp4 server with no iframe
            // at all) as an absolute last resort.
            return document.getElementById('iframe-embed') ||
                   document.querySelector('.player-embed iframe') ||
                   document.querySelector('iframe.player-iframe') ||
                   document.querySelector('iframe[src*="player"]') ||
                   document.querySelector('iframe[src*="embed"]') ||
                   document.querySelector('iframe[src*="megaplay"]') ||
                   document.querySelector('iframe[src*="mytsumi"]') ||
                   document.querySelector('iframe[src*="embed69"]') ||
                   document.querySelector('iframe[src*="xupalace"]') ||
                   document.querySelector('iframe#iframePlayer') ||
                   document.querySelector('iframe.player_conte') ||
                   document.querySelector('iframe[src*="jkplayer"]') ||
                   document.querySelector('#player-frame iframe') ||
                   document.getElementById('player-frame');
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
                            // This call reaches the page asynchronously (native key event ->
                            // bridgePort -> content script), outside the original key-press's own
                            // call stack, so the browser can reject it for lacking "transient user
                            // activation" even though a real remote press caused it. That's fine -
                            // the CSS-based animetv-fullscreen-wrap expansion above is the actual
                            // visual mechanism and doesn't depend on this succeeding - but log the
                            // rejection instead of silently swallowing it, so it's visible in
                            // about:debugging instead of just looking like nothing happened.
                            if (ifr.requestFullscreen) {
                                ifr.requestFullscreen().catch(function (err) {
                                    try { console.warn('Anime TV: player requestFullscreen() rejected:', err && err.message); } catch (ignore) {}
                                });
                            } else if (ifr.webkitRequestFullscreen) ifr.webkitRequestFullscreen();
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

            // ── Infinite scroll (client-side auto-pagination) ──────────────────────
            // Every source paginates its catalog/listing pages as a full page navigation to a
            // "?page=N"-style URL. A real navigation here is the slowest thing this app does: a
            // whole new GeckoView page load, this WebExtension re-injecting itself from scratch,
            // uBlock Origin re-evaluating the page, and losing scroll position. Since every site's
            // "next page" is a plain, unauthenticated GET returning ordinary server-rendered HTML
            // (verified live against each site before adding it here - no nonce/session/JS-render
            // dependency on any of them), a background fetch() from this content script (running
            // in the real page origin, so it carries the same cookies/referrer a real click would)
            // can pull the next page's cards and splice them into the current page instead. Only
            // ever activates on the specific catalog paths listed below, so it can't run wild on
            // detail/player pages; if a site ever redesigns and a selector stops matching, the
            // observer just finds nothing and the site's own normal pagination link keeps working.
            function setupInfiniteScroll() {
                var host = window.location.hostname;
                var path = window.location.pathname;
                var activated = false;

                function hostHas(list) {
                    for (var i = 0; i < list.length; i++) {
                        if (host.indexOf(list[i]) !== -1) return true;
                    }
                    return false;
                }

                // Generic engine for sites whose "next page" is a plain HTML document containing
                // the same card markup as the current page - covers every source except JKAnime
                // (see runJkanimeDirectorioPagination below), which embeds its data as a JSON blob
                // instead of relying on repeated card markup. Returns true if it actually found
                // the container/next-link and armed the observer, so the caller knows whether it's
                // safe to stop retrying.
                function runListPagination(config) {
                    var container = document.querySelector(config.container);
                    if (!container) return false;
                    var nextUrl = config.getNextUrl(document);
                    if (!nextUrl) return false;
                    console.log('AnimeTV: infinite scroll active (' + config.container + '), next=' + nextUrl);

                    var sentinel = document.createElement('div');
                    sentinel.setAttribute('data-animetv-infinite-scroll-sentinel', '1');
                    container.parentNode.insertBefore(sentinel, container.nextSibling);

                    var loading = false;
                    var observer = new IntersectionObserver(function (entries) {
                        if (!nextUrl || loading || !entries[0].isIntersecting) return;
                        loading = true;
                        var requestedUrl = nextUrl;
                        fetch(requestedUrl, { credentials: 'same-origin' })
                            .then(function (res) { return res.text(); })
                            .then(function (html) {
                                var doc = new DOMParser().parseFromString(html, 'text/html');
                                var items = doc.querySelectorAll(config.container + ' ' + config.item);
                                if (!items.length) {
                                    nextUrl = null;
                                    observer.disconnect();
                                    return;
                                }
                                for (var i = 0; i < items.length; i++) {
                                    var node = items[i];
                                    // Defensively strip any nested <script> tags before appending -
                                    // none of the verified sites embed scripts inside card markup,
                                    // but a fetched-and-parsed foreign document should never get to
                                    // run script in this page regardless.
                                    var scripts = node.querySelectorAll ? node.querySelectorAll('script') : [];
                                    for (var s = 0; s < scripts.length; s++) {
                                        try { scripts[s].remove(); } catch (ignore) {}
                                    }
                                    if (config.afterParse) {
                                        try { config.afterParse(node); } catch (ignore) {}
                                    }
                                    container.appendChild(node);
                                }
                                nextUrl = config.getNextUrl(doc);
                                if (!nextUrl) observer.disconnect();
                                // Keep the address bar roughly in sync with how far the user has
                                // auto-loaded, purely cosmetic - not required for the append itself.
                                try { history.replaceState(null, '', requestedUrl); } catch (ignore) {}
                            })
                            .catch(function () {
                                // A single failed fetch shouldn't wedge the page - just stop rather
                                // than retry indefinitely against a possibly-broken URL. The site's
                                // own pagination nav (never removed) still works as a fallback.
                                observer.disconnect();
                            })
                            .then(function () { loading = false; });
                    }, { rootMargin: '800px' });
                    observer.observe(sentinel);
                    return true;
                }

                // 9anime.or.at ("9animetv" theme) - /filter, /genres/*, /az-list catalog views.
                // No plain rel="next" link is exposed; the pager instead shows a "go to page"
                // input plus an "of N" total, so the next URL is built from the current path.
                if (hostHas(['9anime.or.at']) && /^\/(filter|genres|az-list)(\/|$)/.test(path)) {
                    activated = runListPagination({
                        container: '.film_list-wrap',
                        item: '.flw-item',
                        getNextUrl: function (doc) {
                            var input = doc.querySelector('.anime-pagination .input-page');
                            var totalEl = doc.querySelector('.anime-pagination .ap__-input .btn-blank:last-child');
                            if (!input || !totalEl) return null;
                            var current = parseInt(input.value, 10) || 1;
                            var total = parseInt((totalEl.textContent || '').replace(/\D/g, ''), 10) || current;
                            if (current >= total) return null;
                            // doc.location on a DOMParser-created document is always a real but
                            // useless Location-like object (never reflects the fetched URL), so
                            // the base catalog path always comes from the real page's own
                            // location - it never changes across pages, only the /page/N/
                            // segment does, which is exactly what's being rebuilt here.
                            var stripped = path.replace(/\/page\/\d+\/?$/, '/').replace(/\/+$/, '');
                            return stripped + '/page/' + (current + 1) + '/' + window.location.search;
                        }
                    });
                }

                // gogoanime.by and animeflix.team ("dramastream" theme, same markup/mechanism on
                // both) - the theme's own "Next" link already carries whatever filter query
                // params are active, so it's read directly rather than reconstructed.
                if (!activated && hostHas(['gogoanime.by', 'animeflix.team', '9animes.me.uk']) && /^\/(series|Anime)(\/|$)/.test(path)) {
                    activated = runListPagination({
                        container: 'div.listupd',
                        item: 'article.bs',
                        getNextUrl: function (doc) {
                            var next = doc.querySelector('div.hpage a.r[href]');
                            return next ? next.getAttribute('href') : null;
                        }
                    });
                }

                // sololatino.net (both the anime-filtered /animes catalog and the root catalog's
                // /doramas, /genero/* views) - Laravel-style paginator, exposes a standard
                // rel="next" link both in <head> and in the visible pagination nav.
                if (!activated && hostHas(['sololatino.net']) && /^\/(animes|doramas|peliculas|series|genero)(\/|$)/.test(path)) {
                    activated = runListPagination({
                        container: 'div.movies-grid',
                        item: 'div.card',
                        getNextUrl: function (doc) {
                            var next = doc.querySelector('link[rel="next"]') || doc.querySelector('nav.pagination a[rel="next"]');
                            return next ? next.getAttribute('href') : null;
                        }
                    });
                }

                // animeyt.cc ("aniyt" theme) - /tv and /pelicula archives. Cards use a lazy-load
                // placeholder (data-src/data-srcset) that the theme's own lazy-load script only
                // wires up once at initial page load, so appended cards need it copied manually.
                if (!activated && hostHas(['animeyt.cc']) && /^\/(tv|pelicula)(\/|$)/.test(path)) {
                    activated = runListPagination({
                        container: 'div.aniyt-poster-grid',
                        item: 'article.aniyt-anime-card',
                        getNextUrl: function (doc) {
                            var next = doc.querySelector('link[rel="next"]') || doc.querySelector('.aniyt-pagination a.next.page-numbers');
                            return next ? next.getAttribute('href') : null;
                        },
                        afterParse: function (node) {
                            var imgs = node.querySelectorAll('img[data-src], img[data-srcset]');
                            for (var i = 0; i < imgs.length; i++) {
                                var img = imgs[i];
                                if (img.getAttribute('data-src')) img.src = img.getAttribute('data-src');
                                if (img.getAttribute('data-srcset')) img.srcset = img.getAttribute('data-srcset');
                            }
                        }
                    });
                }

                // jkanime.net /directorio - bespoke: each page embeds its ~30 results as a JSON
                // blob (Laravel paginator ->toJson()) rather than repeating card markup verbatim,
                // so the fetched page is parsed for that blob and rendered by cloning whichever
                // card the site itself already rendered for the current view mode (grid/list/
                // compact) - this tracks the site's own template instead of hardcoding one, so it
                // keeps working even if jkanime tweaks its card HTML later. Returns true once
                // successfully armed, same contract as runListPagination above.
                function runJkanimeDirectorioPagination() {
                    var pagerLink = document.querySelector('nav ul.pagination a[rel="next"]');
                    if (!pagerLink) return false;
                    var nextUrl = pagerLink.href;

                    // All three view-mode containers (.page_directorio.mode1/2/3) exist statically
                    // in the DOM regardless of which is active; only the active one has children,
                    // so find a template item directly rather than guessing the class order/which
                    // mode is active.
                    var templateItem = document.querySelector(
                        '.page_directorio.mode1 > div, .page_directorio.mode2 > div, .page_directorio.mode3 > div'
                    );
                    if (!templateItem) return false;
                    var container = templateItem.parentElement;
                    if (!container) return false;
                    console.log('AnimeTV: infinite scroll active (jkanime directorio), next=' + nextUrl);

                    var sentinel = document.createElement('div');
                    sentinel.setAttribute('data-animetv-infinite-scroll-sentinel', '1');
                    var pagination = document.querySelector('nav ul.pagination');
                    (pagination ? pagination.parentNode : container.parentNode).insertBefore(
                        sentinel, pagination || container.nextSibling
                    );

                    var loading = false;
                    var observer = new IntersectionObserver(function (entries) {
                        if (!nextUrl || loading || !entries[0].isIntersecting) return;
                        loading = true;
                        fetch(nextUrl, { credentials: 'same-origin' })
                            .then(function (res) { return res.text(); })
                            .then(function (html) {
                                var match = html.match(/var animes = (\{[\s\S]*?\});\s*\r?\n\s*var mode/);
                                if (!match) { nextUrl = null; observer.disconnect(); return; }
                                var pageData = JSON.parse(match[1]);
                                var items = pageData && pageData.data ? pageData.data : [];
                                if (!items.length) { nextUrl = null; observer.disconnect(); return; }
                                for (var i = 0; i < items.length; i++) {
                                    var item = items[i];
                                    var node = templateItem.cloneNode(true);
                                    try {
                                        var img = node.querySelector('img');
                                        if (img && item.image) { img.src = item.image; img.alt = item.title || ''; }
                                        var link = node.querySelector('a[href]');
                                        if (link && item.url) link.href = item.url;
                                        var title = node.querySelector('.card-title');
                                        if (title) title.textContent = item.title || item.short_title || '';
                                    } catch (ignore) {}
                                    container.appendChild(node);
                                }
                                nextUrl = pageData.next_page_url || null;
                                if (!nextUrl) observer.disconnect();
                            })
                            .catch(function () { observer.disconnect(); })
                            .then(function () { loading = false; });
                    }, { rootMargin: '800px' });
                    observer.observe(sentinel);
                    return true;
                }

                if (!activated && hostHas(['jkanime.net']) && /^\/directorio(\/|$)/.test(path)) {
                    try { activated = runJkanimeDirectorioPagination(); } catch (e) {}
                }

                return activated;
            }

            // Content scripts run at document_start - none of the containers/selectors above
            // exist in the DOM yet at that point even on fully server-rendered pages (document_start
            // fires before <body> has been parsed at all, regardless of whether its eventual
            // content comes from the server or client-side JS), so calling setupInfiniteScroll()
            // synchronously here would always find nothing and silently never activate - which is
            // exactly what happened the first time this shipped. Defer to DOMContentLoaded, with a
            // few retries afterward in case a given site still takes a moment past that point to
            // finish rendering its catalog grid (a self-healing pattern already used elsewhere in
            // this file for the same reason - see purgeOverlays()'s MutationObserver/interval).
            function trySetupInfiniteScrollWithRetries() {
                var attempts = 0;
                var maxAttempts = 10;
                var intervalId = setInterval(function () {
                    attempts++;
                    var succeeded = false;
                    try { succeeded = setupInfiniteScroll(); } catch (e) {}
                    if (succeeded || attempts >= maxAttempts) {
                        clearInterval(intervalId);
                    }
                }, 500);
            }

            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', trySetupInfiniteScrollWithRetries);
            } else {
                trySetupInfiniteScrollWithRetries();
            }
        }
    } catch (e) {
        console.error('Anime TV Page Patches error: ', e);
    }
})();
