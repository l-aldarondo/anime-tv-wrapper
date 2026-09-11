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

        try {
            if (!Document.prototype._origGetById) {
                Document.prototype._origGetById = Document.prototype.getElementById;
                Document.prototype.getElementById = function (id) {
                    var el = this._origGetById.apply(this, arguments);
                    if (id === '__sl_ads' && el) {
                        el.textContent = '{"h":"","b":""}';
                    }
                    return el;
                };
            }
        } catch (e) {}

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
            if (!HTMLAnchorElement.prototype._origClick) {
                HTMLAnchorElement.prototype._origClick = HTMLAnchorElement.prototype.click;
                HTMLAnchorElement.prototype.click = function () {
                    var target = this.getAttribute('target') || '';
                    var href = this.getAttribute('href') || '';
                    if (target === '_blank' && isAllowedInternalLink(href)) {
                        this.setAttribute('target', '_self');
                    } else if (target === '_blank' || (href && href.indexOf('javascript:') === -1 && href.indexOf('#') !== 0 && !isAllowedInternalLink(href))) {
                        console.log('AnimeTV Lite: Blocked anchor programmatic click -> ' + href);
                        return;
                    }
                    return this._origClick.apply(this, arguments);
                };
            }
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
                   u.indexOf('morencius') !== -1 ||
                   u.indexOf('vidhide') !== -1 ||
                   u.indexOf('streamwish') !== -1 ||
                   u.indexOf('hglink') !== -1 ||
                   u.indexOf('voe.sx') !== -1 ||
                   u.indexOf('mega') !== -1;
        }

        function isBadScriptSrc(s) {
            if (!s) return false;
            s = s.toLowerCase();
            return s.indexOf('potdarmottoed') !== -1 || s.indexOf('osarcotypes') !== -1 ||
                   s.indexOf('shaptinfulmars') !== -1 || s.indexOf('densenbl') !== -1 ||
                   s.indexOf('etaargon') !== -1 || s.indexOf('duotypesleeted') !== -1 ||
                   s.indexOf('dayworkannona') !== -1 || s.indexOf('webfedrewraps') !== -1 ||
                   s.indexOf('dearthsongman') !== -1 || s.indexOf('ashrafiundined') !== -1 ||
                   s.indexOf('motelmute') !== -1 || s.indexOf('invoke.js') !== -1 ||
                   s.indexOf('/mtn/') !== -1 ||
                   s.indexOf('furudloof') !== -1 || s.indexOf('belchlipin') !== -1 ||
                   /\/[a-z0-9_-]{10,32}\/[0-9]{5,8}/i.test(s);
        }

        // Neutralize dynamic script injection for Adsterra/Monetag
        try {
            if (!Node.prototype._origAppendChild) {
                Node.prototype._origAppendChild = Node.prototype.appendChild;
                Node.prototype.appendChild = function (child) {
                    if (child && child.tagName === 'SCRIPT' && child.src && isBadScriptSrc(child.src)) {
                        console.log('AnimeTV Lite: Suppressed ad script insertion -> ' + child.src);
                        return child;
                    }
                    return this._origAppendChild.apply(this, arguments);
                };
            }

            if (!Node.prototype._origInsertBefore) {
                Node.prototype._origInsertBefore = Node.prototype.insertBefore;
                Node.prototype.insertBefore = function (newNode, refNode) {
                    if (newNode && newNode.tagName === 'SCRIPT' && newNode.src && isBadScriptSrc(newNode.src)) {
                        console.log('AnimeTV Lite: Suppressed ad script insertBefore -> ' + newNode.src);
                        return newNode;
                    }
                    return this._origInsertBefore.apply(this, arguments);
                };
            }
        } catch (e) {}

        // 4. Capture-phase click interceptor (zero reflow, fast native .closest())
        document.addEventListener('click', function (e) {
            var target = e.target;
            if (!target) return;

            // Instantly kill Animeflix floating ad banners or rogue modals
            var rogue = target.closest('.xb, .x-x, .modal-vast, [class*="D1BnW"]');
            if (rogue) {
                e.preventDefault();
                e.stopPropagation();
                try { rogue.remove(); } catch (err) {}
                return false;
            }

            // Intercept anchor clicks
            var a = target.closest('a');
            if (a) {
                var href = a.getAttribute('href') || '';
                var lowerHref = href.toLowerCase();

                // Block all social media links
                if (lowerHref.indexOf('t.me/') !== -1 || lowerHref.indexOf('telegram.me/') !== -1 ||
                    lowerHref.indexOf('discord.gg') !== -1 || lowerHref.indexOf('discord.com') !== -1 ||
                    lowerHref.indexOf('twitter.com') !== -1 || lowerHref.indexOf('x.com') !== -1 ||
                    lowerHref.indexOf('facebook.com') !== -1 || lowerHref.indexOf('reddit.com') !== -1 ||
                    lowerHref.indexOf('instagram.com') !== -1 || lowerHref.indexOf('tiktok.com') !== -1 ||
                    lowerHref.indexOf('whatsapp.com') !== -1 || lowerHref.indexOf('pinterest.com') !== -1) {
                    e.preventDefault();
                    e.stopPropagation();
                    console.log('AnimeTV Lite: Suppressed social media link click -> ' + href);
                    return false;
                }

                if (isAllowedInternalLink(href)) {
                    // Allow legitimate internal site navigation, converting _blank to _self
                    if (a.getAttribute('target') === '_blank') {
                        a.setAttribute('target', '_self');
                    }
                } else {
                    e.preventDefault();
                    e.stopPropagation();
                    console.log('AnimeTV Lite: Suppressed external popup link click -> ' + href);
                    return false;
                }
            }
        }, true);

        // 5. Overlay, Bait, Popup, Social, and Comments Purging (Targeted Selectors Only)
        var badSelectors = [
            'iframe[src*="adsterra"]', 'iframe[src*="monetag"]', 'iframe[src*="popads"]', 'iframe[src*="propeller"]',
            'iframe[src*="furudloof"]', 'iframe[src*="belchlipin"]', 'iframe[src*="doubleclick"]',
            'iframe[src*="ashrafiundined"]', 'iframe[src*="motelmute"]', 'script[src*="ashrafiundined"]', 'script[src*="motelmute"]',
            'iframe[src*="potdarmottoed"]', 'iframe[src*="osarcotypes"]', 'iframe[src*="shaptinfulmars"]', 'iframe[src*="densenbl"]',
            'iframe[src*="dayworkannona"]', 'iframe[src*="webfedrewraps"]', 'iframe[src*="dearthsongman"]',
            'script[src*="potdarmottoed"]', 'script[src*="osarcotypes"]', 'script[src*="shaptinfulmars"]', 'script[src*="densenbl"]',
            'script[src*="dayworkannona"]', 'script[src*="webfedrewraps"]', 'script[src*="dearthsongman"]', 'script[src*="s.php"]',
            '.xb', 'div.xb', '.x-x', 'div[data-area]', '.wrapper[data-area]', 'div[class*="popup"]', 'div[class*="popunder"]',
            '.modal-vast', '#modal.modal-vast', '.tutorial-overlay',
            '.D1BnW', '[class*="D1BnW"]', 'html > iframe',
            '#disqus_thread', '#disqus_recommendations', 'iframe[src*="disqus"]', '#comments', '.comments',
            '.comment-section', '.comments-area', '.comments-wrap', '.comment-box', '.comment-form', '.comment-respond',
            '.fb-comments', 'iframe[src*="facebook.com/plugins"]', '#fb-root', '.v-comments', '.ep-comments',
            '.anime-comments', '#show-comments', '.comments-holder', '#wpcomm', '.wp-comments', '.gogo-comments',
            '.animeflix-comments', '#comments-block', '.thread', '.conversation-wrapper', '.chat-room', '.c-comments',
            '.social-share', '.share-buttons', '.share-bar', '.social-icons', '.social-buttons', '.social-media', '.socials',
            '.addthis', '.sharethis', '.a2a_kit', '.telegram-btn', '.discord-btn', '.community', '.community-box',
            'a[href*="t.me/"]', 'a[href*="discord.gg/"]', 'a[href*="discord.com/"]', 'a[href*="twitter.com/"]',
            'a[href*="x.com/"]', 'a[href*="facebook.com/"]', 'a[href*="reddit.com/"]', 'a[href*="instagram.com/"]'
        ].join(',');

        function purgeOverlays() {
            neutralizeSlAds();

            try {
                var badEls = document.querySelectorAll(badSelectors);
                for (var i = 0; i < badEls.length; i++) {
                    badEls[i].remove();
                }
            } catch (e) {}
        }

        purgeOverlays();
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', purgeOverlays);
        }
        window.addEventListener('load', purgeOverlays);
        setInterval(purgeOverlays, 2000);

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



    } catch (e) {
        console.error('AnimeTV Guard error: ', e);
    }
})();
