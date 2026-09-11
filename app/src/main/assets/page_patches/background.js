// Bridges MainActivity.kt <-> content_script.js. GeckoView's embedder<->extension
// native-messaging port only reaches the background script (never a content script
// directly), so this just relays in both directions: page events (video-play-detected,
// double-tap-on-player) go native->up, and native commands (play/pause/seek, fullscreen
// toggle) get relayed back down to the tab's top frame. connectNative() is called eagerly
// so the port is already open by the time a page event fires.
(function bridgeNativeMessages() {
    var nativePort = null;

    function connectNative() {
        try {
            nativePort = browser.runtime.connectNative("anime_tv_bridge");
            nativePort.onMessage.addListener(onNativeMessage);
            nativePort.onDisconnect.addListener(function () {
                nativePort = null;
            });
        } catch (e) {
            // No embedder listening (e.g. this ran outside the app) - harmless.
        }
    }

    function onNativeMessage(message) {
        if (!message) return;
        browser.tabs.query({ active: true }).then(function (tabs) {
            tabs.forEach(function (tab) {
                browser.tabs.sendMessage(tab.id, message).catch(function () {});
            });
        });
    }

    connectNative();

    browser.runtime.onMessage.addListener(function (message) {
        if (!message || !nativePort) return;
        if (message.type === "anime-video-play" || message.type === "anime-doubletap" || message.type === "favorite_candidate") {
            nativePort.postMessage(message);
        }
    });
})();
