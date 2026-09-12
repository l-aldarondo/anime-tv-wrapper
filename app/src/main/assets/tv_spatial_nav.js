/**
 * AnimeTV Spatial Navigation Engine
 * Transforms any web catalog (SoloLatino, 9Anime, GogoAnime, AnimeFlix, AnimeYT, JKAnime)
 * into a clean, responsive Netflix / Prime Video 10-foot card-by-card TV experience.
 */
(function() {
    if (window.__AnimeTvSpatialNavLoaded) return;
    window.__AnimeTvSpatialNavLoaded = true;

    // Card selectors across all supported anime & streaming sources
    const CARD_SELECTORS = [
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
        '#player-section [data-server-btn]',
        '#player-section [data-lang-tab]',
        '.episodes-list a',
        '.episodes-nav a',
        '.server-btn',
        '.player-tab',
        'a.item',
        'a[href*="/serie/"]',
        'a[href*="/pelicula/"]',
        'a[href*="/anime/"]',
        'a[href*="/watch/"]',
        'a[href*="/ver/"]',
        'a[href*="/episode/"]',
        'a[href*="/episodio-"]',
        'a.film-poster-ahref'
    ];

    let currentFocusedElement = null;

    function getEligibleCards() {
        const elements = document.querySelectorAll(CARD_SELECTORS.join(', '));
        const eligible = [];
        const seen = new Set();

        for (let i = 0; i < elements.length; i++) {
            const el = elements[i];
            // Skip hidden elements, zero-sized elements, top bar, or elements inside player controls
            if (el.closest('#header, header, .top-bar, .navbar, .site-header, #animetv-topbar, .plyr, .player-controls')) {
                continue;
            }
            const rect = el.getBoundingClientRect();
            if (rect.width < 30 || rect.height < 30) continue;
            
            // Prefer clickable anchor or button inside or the element itself
            const target = el.tagName.toLowerCase() === 'a' || el.tagName.toLowerCase() === 'button'
                ? el
                : el.querySelector('a, button') || el;

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

        // Smoothly bring card into TV viewport center
        el.scrollIntoView({
            behavior: 'smooth',
            block: 'center',
            inline: 'center'
        });
    }

    function findInitialFocus() {
        const cards = getEligibleCards();
        if (cards.length === 0) return null;

        // Find the card closest to top-left of the visible viewport
        let best = cards[0];
        let bestDist = Infinity;
        for (let i = 0; i < cards.length; i++) {
            const r = cards[i].rect;
            if (r.top >= -50 && r.left >= 0) {
                const dist = r.top * 2 + r.left;
                if (dist < bestDist) {
                    bestDist = dist;
                    best = cards[i];
                }
            }
        }
        return best.element;
    }

    function navigate(direction) {
        const cards = getEligibleCards();
        if (cards.length === 0) {
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

        for (let i = 0; i < cards.length; i++) {
            const cand = cards[i];
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
                if (dy > 15) {
                    isValidDirection = true;
                    primaryDist = dy;
                    secondaryDist = Math.abs(dx);
                }
            } else if (direction === 'up') {
                if (dy < -15) {
                    isValidDirection = true;
                    primaryDist = -dy;
                    secondaryDist = Math.abs(dx);
                }
            }

            if (isValidDirection) {
                // Weight distance: heavily penalize cross-axis displacement so rows remain aligned
                const score = primaryDist + secondaryDist * 2.5;
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

    function clickFocused() {
        if (!currentFocusedElement || !document.body.contains(currentFocusedElement)) {
            const init = findInitialFocus();
            if (init) setFocus(init);
            return false;
        }

        currentFocusedElement.classList.add('animetv-clicked-pulse');
        setTimeout(() => {
            if (currentFocusedElement) currentFocusedElement.classList.remove('animetv-clicked-pulse');
        }, 300);

        try {
            currentFocusedElement.click();
            return true;
        } catch(e) {
            return false;
        }
    }

    // Expose SpatialNav API to Android WebView
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
        }
    };

    // Auto-focus on initial load once cards are available
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => setTimeout(findInitialFocus, 400));
    } else {
        setTimeout(findInitialFocus, 400);
    }
})();
