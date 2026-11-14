/*
 * Lumen page stealth.
 *
 * Exam and homework pages run their own visibility / focus watchdog:
 *
 *   document.addEventListener('visibilitychange', ...)
 *   window.addEventListener('blur', ...)
 *   if (document.hidden) reportAbnormal('leave')
 *
 * Switching to another app, splitting the screen or pulling down the notification shade therefore
 * produces an "abnormal behaviour" event even when the page is only covered for a moment.  The patch
 * below reports the page as permanently visible and focused, and hides the automation markers the
 * page also looks for.
 */
(function () {
    'use strict';
    if (window.__lumenStealth) {
        return 'ok';
    }
    window.__lumenStealth = 1;

    function define(target, name, getter) {
        try {
            Object.defineProperty(target, name, {get: getter, configurable: true});
        } catch (e) { /* ignore */ }
    }

    define(document, 'hidden', function () { return false; });
    define(document, 'visibilityState', function () { return 'visible'; });
    define(document, 'webkitHidden', function () { return false; });
    define(document, 'webkitVisibilityState', function () { return 'visible'; });
    define(navigator, 'webdriver', function () { return false; });

    try {
        document.hasFocus = function () { return true; };
    } catch (e) { /* ignore */ }

    var add = EventTarget.prototype.addEventListener;
    EventTarget.prototype.addEventListener = function (type, listener, options) {
        try {
            if (type === 'visibilitychange' || type === 'webkitvisibilitychange') {
                return;
            }
            if (this === window && (type === 'blur' || type === 'pagehide')) {
                return;
            }
        } catch (e) { /* ignore */ }
        return add.call(this, type, listener, options);
    };

    try {
        Object.defineProperty(document, 'onvisibilitychange', {
            get: function () { return null; },
            set: function () { },
            configurable: true
        });
    } catch (e) { /* ignore */ }

    // Report the full screen geometry even when the activity runs in a split screen window.
    try {
        var w = function () { return screen && screen.width ? screen.width : window.innerWidth; };
        var h = function () { return screen && screen.height ? screen.height : window.innerHeight; };
        define(window, 'screenX', function () { return 0; });
        define(window, 'screenY', function () { return 0; });
        define(window, 'screenLeft', function () { return 0; });
        define(window, 'screenTop', function () { return 0; });
        define(window, 'outerWidth', w);
        define(window, 'outerHeight', h);
    } catch (e) { /* ignore */ }

    return 'ok';
})();
