/*
 * Lumen page probe.
 *
 * Injected into every WebView page of the host app.  It is completely passive until the native side
 * (or the floating button) asks it to do something:
 *
 *   ping()  -> 'QUESTIONS' | 'NONE'          cheap structural check
 *   scan()  -> JSON {ok, url, items:[...]}   full extraction
 *   fill()  -> JSON {ok, res:[...]}          apply answers + read back the result
 *   next()  -> 'next' | 'topreview' | 'click' | 'none'
 *   save()  -> 'submitForm' | 'click' | 'none'
 *
 * The DOM contract mirrors the question pages of the host app (container classes, hidden inputs
 * named questionId / type<id> / blankNum<id> / answer<id>, and the page's own helper functions such
 * as questionWrapSynFormAnswer / getTheNextQuestion / submitForm).  All of it is treated as
 * best-effort: every entry point is wrapped so a page change can never throw into the page.
 */
(function () {
    'use strict';
    if (window.__lumen && window.__lumen.v >= 3) {
        return 'ok';
    }

    var L = {v: 3, lastError: ''};
    /** Scroll target remembered by L.frame and used by L.scrollTo (may live in an iframe). */
    var shotCtx = null;

    var ROOT_SEL = [
        '.questionWrap', '.TiMu', '.tiMu', '.singleQuesId', '.questionLi', '.queBox',
        '.mark_item', '.questionItem', '.exam-item', '.pad_question', '.subjectDet',
        '.mark_itemBox', '.topic-item', '.question-item'
    ].join(',');

    var OPT_SEL = [
        '.singleChoice', '.mulChoice', '.trueOrFalse', '.judgeoption',
        '.child-singleChoice', '.child-mulChoice', 'li[choice_name]',
        '.optionItem', '.option_li', '.radio_option', '.checkbox_option',
        '.radioItemCont', '.checkItemCont', '.option-item', '.optionUl li', '.answerBg li'
    ].join(',');

    var STEM_SEL = [
        '.Zy_TItle', '.tit', '.mark_name', '.mark_name_one', '.stem', '.q-title',
        'h3.tit', '.Cy_TItle', '.question-title', '.que-title'
    ].join(',');

    function txt(el) {
        if (!el) return '';
        var s = (el.innerText !== undefined ? el.innerText : el.textContent) || '';
        return String(s).replace(/\s+/g, ' ').trim();
    }

    function attr(el, name) {
        if (!el || !el.getAttribute) return '';
        var v = el.getAttribute(name);
        return v === null || v === undefined ? '' : String(v);
    }

    function nodes(parent, sel) {
        try {
            if (!parent || !parent.querySelectorAll) return [];
            return Array.prototype.slice.call(parent.querySelectorAll(sel));
        } catch (e) {
            return [];
        }
    }

    function docs() {
        var out = [document];
        nodes(document, 'iframe,frame').forEach(function (f) {
            try {
                if (f.contentDocument) out.push(f.contentDocument);
            } catch (e) { /* cross origin frame */ }
        });
        return out;
    }

    function valueOf(el) {
        if (!el) return '';
        if (el.value !== undefined && el.value !== null) return String(el.value);
        return txt(el);
    }

    function qidOf(el) {
        var input = el.querySelector && el.querySelector('input[name="questionId"]');
        if (input) {
            var v = valueOf(input);
            if (v) return v;
        }
        var direct = attr(el, 'data-qid') || attr(el, 'data-questionid') || attr(el, 'qid');
        if (direct) return direct;
        var id = attr(el, 'id');
        if (id) {
            var m = id.match(/(\d{3,})/);
            if (m) return m[1];
        }
        var hidden = el.querySelector && el.querySelector('input[name^="type"]');
        if (hidden) {
            var mm = attr(hidden, 'name').match(/^type(\d+)$/);
            if (mm) return mm[1];
        }
        return '';
    }

    function typeOf(el, qid) {
        var t = el.querySelector && el.querySelector('input[name="type' + qid + '"]');
        if (t) {
            var v = parseInt(valueOf(t), 10);
            if (!isNaN(v)) return v;
        }
        var dt = attr(el, 'data-type');
        if (dt) {
            var d = parseInt(dt, 10);
            if (!isNaN(d)) return d;
        }
        var name = txt(el.querySelector && el.querySelector('.newZy_TItle,.typeName,.mark_name'))
            || txt(el.parentElement);
        if (/多选/.test(name)) return 1;
        if (/判断/.test(name)) return 3;
        if (/填空/.test(name)) return 2;
        if (/简答|问答|论述|名词解释/.test(name)) return 4;
        if (/单选/.test(name)) return 0;
        return -1;
    }

    function cleanStem(s) {
        var out = String(s || '').replace(/\s+/g, ' ').trim();
        out = out.replace(/^(?:标记|已标记|收藏|已收藏)\s*/, '');
        out = out.replace(/^(单选题|多选题|判断题|填空题|简答题|问答题|论述题|名词解释|计算题)\s*/, '');
        out = out.replace(/^\d+\s*[\.、:：)]\s*/, '');
        out = out.replace(/^[\[【(（]\s*(单选题|多选题|判断题|填空题|简答题|问答题|论述题|名词解释|计算题)\s*[\]】)）]\s*/, '');
        out = out.replace(/^[\(\（]\s*\d+(\.\d+)?\s*分\s*[\)\）]\s*/, '');
        return out.trim();
    }

    function stemOf(el) {
        var node = el.querySelector(STEM_SEL);
        var text = '';
        if (node) {
            // Clone and drop the badge ("标记"/"已收藏") and the type header ("单选题"), which the
            // page renders inside the same title block as the stem.
            var clone = node.cloneNode(true);
            var drops = clone.querySelectorAll(
                '.asFlagBtn,.lb,.mark_name,.newZy_TItle,.typeName,h1,h2,svg,script,style');
            for (var i = 0; i < drops.length; i++) {
                if (drops[i].parentNode) {
                    drops[i].parentNode.removeChild(drops[i]);
                }
            }
            text = cleanStem(txt(clone));
        }
        if (!text) {
            text = cleanStem(txt(el));
            var cut = text.search(/[A-D]\s*[\.、．]/);
            if (cut > 12) text = text.slice(0, cut).trim();
        }
        if (text.length > 900) text = text.slice(0, 900);
        return text;
    }

    function keyOf(n) {
        var k = attr(n, 'name') || attr(n, 'choice_name') || attr(n, 'orichoice') || attr(n, 'data');
        if (k) return k.replace(/[^A-Za-z]{1,}$/, '').replace(/^[^A-Za-z]+/, '');
        var inner = n.querySelector && n.querySelector('input[type="hidden"],.No,.num,.check,.option-key');
        if (inner) {
            var v = attr(inner, 'choice_name') || valueOf(inner);
            if (v) return String(v).replace(/[^A-Za-z]/g, '').slice(0, 3);
        }
        var label = n.querySelector && n.querySelector('.num,.No,.letter');
        if (label) {
            var lv = txt(label).replace(/[^A-Za-z]/g, '');
            if (lv) return lv.slice(0, 3);
        }
        return '';
    }

    function optionText(n) {
        var info = n.querySelector && n.querySelector('.answerInfo,.info,.aBox,.ansBox,.option-text,.text');
        var s = txt(info || n);
        return s.replace(/^\s*[A-Za-z]\s*[\.、．:：\)）]\s*/, '').replace(/\s+/g, ' ').trim();
    }

    function optionsOf(el, qid) {
        var out = [];
        var seen = {};
        var list = nodes(el, OPT_SEL + (qid ? ',.choice' + qid + ' li,.choice' + qid : ''));
        list.forEach(function (n) {
            var k = keyOf(n);
            if (!k) return;
            var t = optionText(n);
            var src = '';
            var img = n.querySelector && n.querySelector('img');
            if (img) {
                src = String(img.getAttribute('src') || img.src || '').slice(0, 300);
            }
            if (!t) {
                // Image options (very common in physics/maths papers): keep the key and the image
                // URL so an image answer returned by the bank can be mapped back to a letter.
                if (src) {
                    t = '[图片]';
                } else {
                    return;
                }
            }
            var up = k.toUpperCase();
            if (seen[up]) return;
            seen[up] = 1;
            out.push({k: up, t: t.length > 300 ? t.slice(0, 300) : t, src: src});
        });
        if (out.length === 0) {
            if (/^(对|错)$/.test(txt(el.querySelector('.judgeoption,.trueOrFalse')) || '')) {
                out.push({k: 'A', t: '对'});
                out.push({k: 'B', t: '错'});
            }
        }
        return out;
    }

    function blanksOf(el, qid) {
        var count = 0;
        var bn = el.querySelector('input[name="blankNum' + qid + '"]');
        if (bn) {
            var raw = valueOf(bn).trim();
            if (raw) {
                var parts = raw.split(/[,，、]/).filter(function (x) { return x.trim().length > 0; });
                if (parts.length > 1) {
                    count = parts.length;
                } else {
                    var n = parseInt(parts[0], 10);
                    if (!isNaN(n) && n > 0) count = n;
                }
            }
        }
        // Only real input surfaces count; the hidden "answer<qid>" field is not a blank.
        var areas = nodes(el, 'textarea[id^="my-content' + (qid || '') + '"],.editorItem');
        if (areas.length > count) count = areas.length;
        return count;
    }

    function containers() {
        var out = [];
        var seen = {};
        var seq = 0;
        docs().forEach(function (d) {
            var found = [];
            try {
                found = Array.prototype.slice.call(d.querySelectorAll(ROOT_SEL));
            } catch (e) {
                found = [];
            }
            if (found.length === 0) {
                // Single question page: build a virtual container around the qid input.
                var ids = [];
                try {
                    ids = Array.prototype.slice.call(d.querySelectorAll('input[name="questionId"]'));
                } catch (e2) {
                    ids = [];
                }
                ids.forEach(function (input) {
                    var host = input.closest ? input.closest('form,#question,.pad_h1,.questionDetail,.subject_det') : null;
                    if (host) found.push(host);
                });
            }
            found.forEach(function (el) {
                seq++;
                var qid = qidOf(el);
                if (!qid) {
                    // Pages that keep questionId inputs outside the block: synthesise a stable id so
                    // the answer can still be applied by position.
                    qid = '@' + seq;
                }
                if (seen[qid]) return;
                seen[qid] = 1;
                out.push({el: el, qid: qid, doc: d});
            });
        });
        return out;
    }

    L.ping = function () {
        try {
            var list = containers();
            if (list.length > 0) return 'QUESTIONS';
            var body = document.body ? String(document.body.innerText || '') : '';
            if (/单选题|多选题|判断题|填空题|简答题|共\s*\d+\s*题|答题卡/.test(body)) {
                return 'QUESTIONS';
            }
        } catch (e) {
            L.lastError = String(e && e.message || e);
        }
        return 'NONE';
    };

    /**
     * True when the question (stem or options) really carries a picture.
     *
     * <p>Not "any image element": every question's title block also holds the 标记 badge with an
     * inline {@code <svg>} flag, and the page background carries watermarks.  Those made every
     * question look like a picture question, so the vision model was used even for plain text.</p>
     */
    function hasPicture(el, opts) {
        try {
            if (opts && opts.length) {
                for (var i = 0; i < opts.length; i++) {
                    if (opts[i].src) return 1;
                    if (opts[i].t === '[图片]') return 1;
                }
            }
            var pics = el.querySelectorAll('img,canvas,math,[style*="background-image"]');
            for (var j = 0; j < pics.length; j++) {
                var n = pics[j];
                // Skip icons/badges: they live in the title block and are tiny.
                var badge = null;
                try {
                    badge = n.closest && n.closest(
                        '.asFlagBtn,.lb,.mark_name,.newZy_TItle,.typeName,h1,h2,h3,svg,button');
                } catch (e1) { badge = null; }
                if (badge) continue;
                var r = n.getBoundingClientRect();
                if (r.width >= 60 && r.height >= 20) {
                    return 1;
                }
                // The page may not have laid the image out yet; an <img> with a real source counts.
                if (n.tagName === 'IMG' && (r.width === 0 || r.height === 0)) {
                    var src = n.getAttribute('src') || '';
                    if (src && src.indexOf('data:image') !== 0 && src.length > 24) {
                        return 1;
                    }
                }
            }
            return 0;
        } catch (e) {
            return 0;
        }
    }

    /** Page-coordinate box of a question container. */
    function boxOf(el) {
        try {
            var r = el.getBoundingClientRect();
            var top = r.top + (window.pageYOffset || document.documentElement.scrollTop || 0);
            var sc = scrollerOf(el);
            return {
                top: Math.round(top),
                h: Math.round(r.height),
                vh: window.innerHeight,
                dpr: window.devicePixelRatio || 1,
                y: Math.round(window.pageYOffset || 0),
                max: Math.max(0, (document.documentElement.scrollHeight || 0) - window.innerHeight),
                // Diagnostics for pages that scroll an inner container instead of the window.
                eh: Math.round(el.scrollHeight || 0),
                ch: Math.round(el.clientHeight || 0),
                sc: sc === (document.scrollingElement || document.documentElement) ? 'window'
                    : (sc.className ? String(sc.className).slice(0, 40) : sc.tagName),
                sh: Math.round(sc.scrollHeight || 0),
                chh: Math.round(sc.clientHeight || 0),
                sy: Math.round(sc.scrollTop || 0),
                sh_rect: Math.round(sc.getBoundingClientRect().height),
                sy_win: Math.round(window.pageYOffset || 0),
                diag: diagOf(el)
            };
        } catch (e) {
            return null;
        }
    }

    /** Compact description of the scroll situation, used while tuning the capture. */
    function diagOf(el) {
        try {
            var parts = [];
            parts.push('win=' + Math.round(window.innerHeight) + '/' + Math.round(window.pageYOffset));
            parts.push('body=' + Math.round(document.body.scrollHeight) + '/'
                + Math.round(document.body.clientHeight) + '/' + Math.round(document.body.scrollTop));
            parts.push('html=' + Math.round(document.documentElement.scrollHeight) + '/'
                + Math.round(document.documentElement.clientHeight));
            parts.push('iframes=' + document.querySelectorAll('iframe').length);
            var node = el;
            var depth = 0;
            while (node && depth < 8) {
                var cls = String(node.className || '').split(' ')[0].slice(0, 18);
                parts.push('<' + (node.tagName || '?').toLowerCase() + (cls ? '.' + cls : '') + ' '
                    + Math.round(node.scrollHeight) + '/' + Math.round(node.clientHeight) + '>');
                node = node.parentElement;
                depth++;
            }
            return parts.join(' ');
        } catch (e) {
            return '';
        }
    }

    /**
     * Nearest scrollable ancestor of an element.
     *
     * <p>Accepts {@code overflow:hidden} boxes as well: 学习通's exam page scrolls
     * {@code div.answerCon} from JavaScript, so its computed overflow is hidden even though it has a
     * real scroll offset and a taller scrollHeight.</p>
     */
    function scrollerOf(el) {
        try {
            var node = el;
            while (node && node !== document.body && node !== document.documentElement) {
                if (node.scrollHeight > node.clientHeight + 8 && node.clientHeight > 0) {
                    var oy = window.getComputedStyle(node).overflowY;
                    if (oy !== 'visible') {
                        return node;
                    }
                }
                node = node.parentElement;
            }
        } catch (e) { /* ignore */ }
        return document.scrollingElement || document.documentElement;
    }

    /** Element rect in top-document CSS pixels (accumulates iframe offsets). */
    function rectInTop(node) {
        var r = node.getBoundingClientRect();
        var out = {left: r.left, top: r.top, width: r.width, height: r.height};
        try {
            var w = node.ownerDocument.defaultView;
            var guard = 0;
            while (w && w !== w.parent && guard < 6) {
                var fe = w.frameElement;
                if (!fe) break;
                var fr = fe.getBoundingClientRect();
                out.left += fr.left;
                out.top += fr.top;
                w = w.parent;
                guard++;
            }
            var tw = w || window;
            out.top += tw.pageYOffset || 0;
            out.left += tw.pageXOffset || 0;
        } catch (e) { /* ignore */ }
        return out;
    }

    /** Diagnostic: which elements currently hold a scroll offset (used to find the real scroller). */
    L.scrollables = function () {
        try {
            var out = [];
            var nodes = document.querySelectorAll('*');
            for (var i = 0; i < nodes.length && out.length < 8; i++) {
                var n = nodes[i];
                if (n.scrollTop > 0 && n.scrollHeight > n.clientHeight + 8) {
                    out.push((n.tagName || '?') + '.' + String(n.className || '').split(' ')[0]
                        + '=' + Math.round(n.scrollTop) + '/' + Math.round(n.scrollHeight)
                        + '/' + Math.round(n.clientHeight));
                }
            }
            if (window.pageYOffset > 0) {
                out.push('window=' + Math.round(window.pageYOffset));
            }
            return out.join(' | ') || 'none';
        } catch (e) {
            return 'err ' + e;
        }
    };

    /** First ancestor that clips its content (学习通 uses overflow:hidden + JS scrolling). */
    function clipBoxOf(el) {
        try {
            var node = el.parentElement;
            while (node && node !== document.body && node !== document.documentElement) {
                var oy = window.getComputedStyle(node).overflowY;
                if (oy !== 'visible' && node.clientHeight > 0) {
                    return node;
                }
                node = node.parentElement;
            }
        } catch (e) { /* ignore */ }
        return null;
    }

    /** Real content height of a container (its own scrollHeight can lie when it is height:100%). */
    function contentHeightOf(el) {
        try {
            var r0 = el.getBoundingClientRect();
            var bottom = r0.top;
            var kids = el.querySelectorAll('*');
            for (var i = 0; i < kids.length; i++) {
                var r = kids[i].getBoundingClientRect();
                if (r.height > 0.5) {
                    bottom = Math.max(bottom, r.bottom);
                }
            }
            return Math.max(0, bottom - r0.top);
        } catch (e) {
            return 0;
        }
    }

    /**
     * Picks the scroll box that shows the question and reports it as the camera frame.
     *
     * <p>学习通's exam page scrolls {@code div.answerCon} with JavaScript and clips it with
     * {@code overflow:hidden}, so the box is found by looking for the element with the largest
     * hidden scroll range rather than by walking up from the question.</p>
     */
    L.shotBox = function () {
        try {
            var best = null;
            var bestDelta = 24;
            var nodes = document.querySelectorAll('div, section, main');
            for (var i = 0; i < nodes.length; i++) {
                var n = nodes[i];
                var delta = n.scrollHeight - n.clientHeight;
                if (delta > bestDelta && n.clientHeight >= 200
                        && n.getBoundingClientRect().width >= 200) {
                    best = n;
                    bestDelta = delta;
                }
            }
            if (best) {
                shotCtx = best;
                var r = rectInTop(best);
                return JSON.stringify({
                    mode: 'box',
                    x: Math.round(r.left),
                    y: Math.round(r.top),
                    w: Math.round(r.width),
                    h: Math.round(best.clientHeight),
                    max: Math.round(bestDelta),
                    scrollY: Math.round(best.scrollTop || 0),
                    dpr: window.devicePixelRatio || 1
                });
            }
            // Nothing scrolls: frame the question itself so page chrome stays out of the picture.
            var list = containers();
            if (list.length) {
                var qr = rectInTop(list[0].el);
                if (qr.width > 100 && qr.height > 100) {
                    shotCtx = null;
                    return JSON.stringify({
                        mode: 'question',
                        x: Math.round(qr.left),
                        y: Math.round(qr.top),
                        w: Math.round(qr.width),
                        h: Math.round(Math.min(qr.height, window.innerHeight - Math.max(0, qr.top))),
                        max: 0,
                        scrollY: 0,
                        dpr: window.devicePixelRatio || 1
                    });
                }
            }
            var de = document.documentElement;
            var vh = window.innerHeight;
            shotCtx = null;
            return JSON.stringify({
                mode: 'window',
                x: 0,
                y: 0,
                w: Math.round(de.clientWidth),
                h: Math.round(vh),
                max: Math.max(0, (de.scrollHeight || 0) - vh),
                scrollY: Math.round(window.pageYOffset || 0),
                dpr: window.devicePixelRatio || 1
            });
        } catch (e) {
            return '{}';
        }
    };

    /** Scrolls the camera frame chosen by {@link L.shotBox}. */
    L.shotScroll = function (y) {
        try {
            var target = Math.max(0, Math.round(y));
            if (shotCtx) {
                shotCtx.scrollTop = target;
                return String(Math.round(shotCtx.scrollTop || 0));
            }
            window.scrollTo(0, target);
            return String(Math.round(window.pageYOffset || 0));
        } catch (e) {
            return '-1';
        }
    };

    /** Where the question sits **inside its own scroller** (that scroll box is the camera frame). */
    L.frame = function (index) {
        try {
            var list = containers();
            var entry = list[index || 0];
            if (!entry) return '{}';
            var el = entry.el;
            var doc = el.ownerDocument || document;
            var win = doc.defaultView || window;
            var sc = scrollerOf(el);
            var isDoc = sc === (doc.scrollingElement || doc.documentElement);
            var scroll, vh, max, rect;
            if (isDoc) {
                scroll = win.pageYOffset || 0;
                vh = doc.documentElement.clientHeight;
                max = Math.max(0, (doc.documentElement.scrollHeight || 0) - vh);
                rect = rectInTop(doc.documentElement);
            } else {
                scroll = sc.scrollTop || 0;
                vh = sc.clientHeight;
                max = Math.max(0, sc.scrollHeight - vh);
                rect = rectInTop(sc);
            }
            var er = el.getBoundingClientRect();
            var sr = isDoc ? {top: 0} : sc.getBoundingClientRect();
            var topInScroll = isDoc ? (er.top + scroll) : (er.top - sr.top + scroll);
            shotCtx = {doc: isDoc, sc: sc, win: win};
            return JSON.stringify({
                qid: entry.qid,
                top: Math.round(topInScroll),
                h: Math.round(er.height),
                viewH: Math.round(vh),
                max: Math.round(max),
                scrollY: Math.round(scroll),
                dpr: win.devicePixelRatio || 1,
                x: Math.round(rect.left),
                y: Math.round(rect.top),
                w: Math.round(rect.width),
                hh: Math.round(rect.height),
                count: list.length
            });
        } catch (e) {
            return '{}';
        }
    };

    /** Scrolls the scroll box that holds the question (see L.frame). */
    L.scrollTo = function (y) {
        try {
            var target = Math.max(0, Math.round(y));
            var ctx = shotCtx;
            var sc = ctx && ctx.sc ? ctx.sc : null;
            if (sc && !ctx.doc) {
                sc.scrollTop = target;
                return String(Math.round(sc.scrollTop || 0));
            }
            var win = ctx && ctx.win ? ctx.win : window;
            win.scrollTo(0, target);
            var doc = win.document;
            return String(Math.round(win.pageYOffset
                || (doc && doc.documentElement && doc.documentElement.scrollTop) || 0));
        } catch (e) {
            return '-1';
        }
    };

    L.scan = function () {        try {
            var list = containers();
            var items = [];
            list.forEach(function (entry) {
                var el = entry.el;
                var qid = entry.qid;
                var type = typeOf(el, qid);
                var opts = optionsOf(el, qid);
                var blanks = blanksOf(el, qid);
                var stem = stemOf(el);
                if (!stem && opts.length === 0 && blanks === 0) return;
                if (type < 0) {
                    if (opts.length === 2 && /^(对|错|正确|错误|是|否|true|false)$/i.test(opts[0].t) ) {
                        type = 3;
                    } else if (opts.length >= 2) {
                        type = 0;
                    } else if (blanks > 0) {
                        type = 2;
                    }
                }
                items.push({
                    qid: qid,
                    type: type,
                    stem: stem,
                    options: opts,
                    blanks: blanks,
                    img: hasPicture(el, opts),
                    box: boxOf(el)
                });
            });
            return JSON.stringify({
                ok: 1,
                url: String(location.href).slice(0, 300),
                items: items.slice(0, 60)
            });
        } catch (e) {
            return JSON.stringify({ok: 0, err: String(e && e.message || e)});
        }
    };

    function fire(el, type) {
        try {
            var ev = new Event(type, {bubbles: true, cancelable: true});
            el.dispatchEvent(ev);
        } catch (e) {
            try {
                var legacy = document.createEvent('HTMLEvents');
                legacy.initEvent(type, true, true);
                el.dispatchEvent(legacy);
            } catch (e2) { /* ignore */ }
        }
    }

    function mouse(el) {
        if (!el) return false;
        // Clicking the real form control fires the page's own handlers exactly once; dispatching a
        // synthetic sequence on top of that would toggle check boxes twice.
        var control = null;
        try {
            control = el.querySelector && el.querySelector('input[type="radio"],input[type="checkbox"]');
        } catch (e) {
            control = null;
        }
        var target = control || el;
        try {
            if (typeof target.click === 'function') {
                target.click();
                return true;
            }
        } catch (e2) { /* fall through to the synthetic path */ }
        var done = false;
        ['mousedown', 'mouseup', 'click'].forEach(function (name) {
            try {
                var ev = new MouseEvent(name, {bubbles: true, cancelable: true, view: window});
                el.dispatchEvent(ev);
                done = true;
            } catch (e) {
                try {
                    var legacy = document.createEvent('MouseEvents');
                    legacy.initEvent(name, true, true);
                    el.dispatchEvent(legacy);
                    done = true;
                } catch (e2) { /* ignore */ }
            }
        });
        return done;
    }

    function setValue(el, v) {
        if (!el) return false;
        try {
            el.value = v;
            fire(el, 'input');
            fire(el, 'change');
            fire(el, 'keyup');
            fire(el, 'blur');
            return true;
        } catch (e) {
            return false;
        }
    }

    function optionByKey(el, key) {
        var want = String(key).toUpperCase();
        var found = null;
        var list = nodes(el, OPT_SEL);
        list.forEach(function (n) {
            if (found) return;
            var k = keyOf(n);
            if (k && k.toUpperCase() === want) found = n;
        });
        if (found) return found;
        // Judgment pages that use true/false instead of letters.
        var truthy = want === 'A' || want === 'T' || want === 'Y';
        var names = truthy ? ['true', '1', 'yes', '对', '正确']
            : ['false', '0', 'no', '错', '错误'];
        list.forEach(function (n) {
            if (found) return;
            var k = (attr(n, 'name') || attr(n, 'choice_name') || attr(n, 'orichoice') || '').toLowerCase();
            if (names.indexOf(k) >= 0) found = n;
        });
        if (found) return found;
        // Last resort: index based (A -> first option).
        if (/^[A-H]$/.test(want)) {
            var idx = want.charCodeAt(0) - 65;
            if (list[idx]) return list[idx];
        }
        return null;
    }

    function readChoiceState(el, qid) {
        var hidden = document.getElementById('answers' + qid) || document.getElementById('answer' + qid);
        if (hidden && hidden.value) return String(hidden.value);
        var picked = '';
        var checked = nodes(el, '.dxchecked,.checked,.on,.cur,.selected,input:checked');
        if (checked.length > 0) {
            checked.forEach(function (n) {
                var k = keyOf(n);
                if (k) picked += k.toUpperCase();
                else {
                    var input = n.querySelector && n.querySelector('input');
                    if (input && input.value) picked += String(input.value).toUpperCase();
                }
            });
        }
        return picked;
    }

    function setChoiceClass(node, on) {
        try {
            if (!node.classList) return;
            if (on) {
                node.classList.add('on');
            } else {
                node.classList.remove('on');
            }
        } catch (e) { /* ignore */ }
    }

    /** Writes the answer into the page state (hidden field + visual class) without clicking. */
    function forceChoice(el, qid, letters) {
        var hidden = document.getElementById('answers' + qid) || document.getElementById('answer' + qid);
        if (hidden) {
            setValue(hidden, letters);
        }
        var inQ = el.querySelector('input[name="answers' + qid + '"],input[name="answer' + qid + '"]');
        if (inQ && inQ !== hidden) {
            setValue(inQ, letters);
        }
        var list = nodes(el, OPT_SEL);
        for (var i = 0; i < list.length; i++) {
            var k = keyOf(list[i]);
            setChoiceClass(list[i], !!k && letters.indexOf(String(k).toUpperCase()) >= 0);
        }
        syncPage(el, qid);
    }

    /** Calls whatever "commit" helpers the page exposes. */
    function syncPage(el, qid) {
        try {
            if (typeof window.fireAnswerChangeEvent === 'function') {
                window.fireAnswerChangeEvent();
            }
        } catch (e) { /* ignore */ }
        try {
            var fn = window.questionWrapSynFormAnswer || window.synFormAnswer || window.answerSynForm;
            if (typeof fn === 'function') {
                fn(window.jQuery ? window.jQuery(el) : el);
            }
        } catch (e) { /* ignore */ }
    }

    function applyChoice(el, qid, answer) {
        var letters = String(answer || '').replace(/[^A-Za-z]/g, '').toUpperCase();
        if (!letters) return false;
        // The page only trusts real touch input, so a synthetic click is at best a hint; the state
        // is then written directly below.
        for (var i = 0; i < letters.length; i++) {
            var node = optionByKey(el, letters.charAt(i));
            if (node) mouse(node);
        }
        var observed = readChoiceState(el, qid);
        if (observed.toUpperCase() !== letters) {
            forceChoice(el, qid, letters);
        }
        return true;
    }

    /**
     * Writes an answer into a text surface.
     *
     * <p>简答/计算题 in 学习通 are rendered with UEditor: the real editable body lives in
     * {@code iframe#ueditor_N} and the editor instance is registered in {@code window.$EDITORUI}.
     * Writing the iframe body directly is not enough (the page submits the editor's internal value),
     * so the official {@code setContent} is used whenever the instance can be found.</p>
     */
    function applyBlanks(el, qid, answer, essay) {
        var text = String(answer === undefined || answer === null ? '' : answer);
        if (!text) return false;
        var parts = essay ? [text] : text.split('|');
        var areas = nodes(el, '.editorItem,textarea[id^="my-content' + qid + '-"],'
            + 'textarea[name^="answer' + qid + '"],div[contenteditable="true"],iframe[id^="ueditor_"]');
        if (areas.length === 0) {
            areas = nodes(el, 'textarea');
        }
        var okCount = 0;
        for (var i = 0; i < parts.length && i < areas.length; i++) {
            if (writeIntoEditor(areas[i], parts[i])) {
                okCount++;
            }
        }
        var hiddenA = document.getElementById('answer' + qid);
        if (hiddenA) setValue(hiddenA, essay ? text : parts.join('|'));
        syncPage(el, qid);
        return okCount > 0;
    }

    function writeIntoEditor(host, value) {
        try {
            var ed = ueditorOf(host);
            if (ed && typeof ed.setContent === 'function') {
                ed.setContent(toHtml(value));
                if (typeof ed.sync === 'function') {
                    ed.sync();
                }
                try {
                    if (ed.fireEvent) ed.fireEvent('contentchange');
                } catch (e1) { /* ignore */ }
                return true;
            }
            var ifr = host.tagName === 'IFRAME' ? host : (host.querySelector && host.querySelector('iframe'));
            if (ifr) {
                var idoc = null;
                try { idoc = ifr.contentDocument; } catch (e2) { idoc = null; }
                if (idoc && idoc.body) {
                    idoc.body.innerHTML = toHtml(value);
                    try {
                        idoc.body.dispatchEvent(new Event('input', {bubbles: true}));
                    } catch (e3) { /* ignore */ }
                    return true;
                }
            }
            var editable = host.querySelector
                && host.querySelector('[contenteditable="true"],.edui-body,.edui-body-container');
            if (editable) {
                editable.innerHTML = toHtml(value);
                fire(editable, 'input');
                return true;
            }
            if (host.tagName === 'TEXTAREA' || host.tagName === 'INPUT') {
                setValue(host, value);
                return true;
            }
            return false;
        } catch (e) {
            return false;
        }
    }

    /** Finds the UEditor instance that owns {@code host} (学习通 keeps them in window.$EDITORUI). */
    function ueditorOf(host) {
        try {
            var map = window.$EDITORUI;
            if (!map) return null;
            for (var k in map) {
                if (!Object.prototype.hasOwnProperty.call(map, k)) continue;
                var ed = map[k];
                if (!ed) continue;
                try {
                    if (ed.container && host.contains(ed.container)) return ed;
                } catch (e1) { /* ignore */ }
                try {
                    if (ed.iframe && host.contains(ed.iframe)) return ed;
                } catch (e2) { /* ignore */ }
            }
        } catch (e) { /* ignore */ }
        return null;
    }

    function toHtml(value) {
        var s = String(value);
        var out = s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
        var lines = out.split(/\n+/);
        var html = '';
        for (var i = 0; i < lines.length; i++) {
            if (lines[i].trim() === '') continue;
            html += '<p>' + lines[i] + '</p>';
        }
        return html || '<p></p>';
    }

    /** Text currently inside the essay editor (used by verify). */
    function readEditorText(el) {
        try {
            var host = el.querySelector && el.querySelector('.editorItem');
            if (!host) return '';
            var ed = ueditorOf(host);
            if (ed && typeof ed.getContent === 'function') {
                return String(ed.getContent() || '').replace(/<[^>]+>/g, '').trim();
            }
            var ifr = host.querySelector && host.querySelector('iframe');
            if (ifr) {
                var doc = null;
                try { doc = ifr.contentDocument; } catch (e) { doc = null; }
                if (doc && doc.body) return String(doc.body.textContent || '').trim();
            }
            var ta = host.querySelector && host.querySelector('textarea');
            return ta ? String(ta.value || '').trim() : '';
        } catch (e) {
            return '';
        }
    }

    L.fill = function (payload) {
        var data = payload;
        try {
            if (typeof payload === 'string') data = JSON.parse(payload);
        } catch (e) {
            return JSON.stringify({ok: 0, err: 'bad_payload'});
        }
        var res = [];
        var pending = [];
        try {
            var list = containers();
            (data.answers || []).forEach(function (a) {
                var entry = null;
                for (var i = 0; i < list.length; i++) {
                    if (String(list[i].qid) === String(a.qid)) {
                        entry = list[i];
                        break;
                    }
                }
                if (!entry) {
                    res.push(a.qid + ':no_container');
                    return;
                }
                var written;
                if (a.type === 2 || a.type === 4 || a.type === 5 || a.type === 6) {
                    // 4/5/6 are essay-like: the whole text goes into one editor (no '|' splitting).
                    written = applyBlanks(entry.el, entry.qid, a.answer, a.type !== 2);
                } else {
                    written = applyChoice(entry.el, entry.qid, a.answer);
                }
                var state = a.type === 2 ? '' : readChoiceState(entry.el, entry.qid);
                res.push(a.qid + ':' + (written ? 'written' : 'fail') + (state ? '/' + state : ''));
                pending.push(a.qid);
            });
        } catch (e) {
            return JSON.stringify({ok: 0, err: String(e && e.message || e)});
        }
        // Re-read after the page's own handlers had a chance to run, so the log shows the real state.
        window.__lumenVerified = '';
        setTimeout(function () {
            try {
                var list2 = containers();
                var out = [];
                pending.forEach(function (qid) {
                    for (var i = 0; i < list2.length; i++) {
                        if (String(list2[i].qid) === String(qid)) {
                            var st = readChoiceState(list2[i].el, qid);
                            if (!st) {
                                // Essay questions have no choice state: report the editor content.
                                var t = readEditorText(list2[i].el);
                                st = t ? ('editor:' + t.length + '字') : 'editor:空';
                            }
                            out.push(qid + ':' + st);
                            return;
                        }
                    }
                    out.push(qid + ':gone');
                });
                window.__lumenVerified = out.join(',');
            } catch (e2) { /* ignore */ }
        }, 700);
        return JSON.stringify({ok: 1, res: res});
    };

    L.verify = function () {
        return window.__lumenVerified || '(pending)';
    };

    L.next = function () {
        try {
            var nx = document.getElementById('nextQuestion');
            if (nx && typeof window.getTheNextQuestion === 'function' && nx.offsetParent !== null) {
                window.getTheNextQuestion(nx, 1);
                return 'next';
            }
            var tp = document.getElementById('topreview2') || document.getElementById('topreview');
            if (tp && typeof window.topreview === 'function') {
                window.topreview();
                return 'topreview';
            }
            var btn = document.querySelector('#nextBtn,.nextBtn,.next-btn,.btn-next,.pad_next,.next');
            if (btn) {
                mouse(btn);
                return 'click';
            }
        } catch (e) { /* ignore */ }
        return 'none';
    };

    L.save = function () {
        try {
            var submit = document.getElementById('submitTest') || document.querySelector('.completeBtn,.submitBtn');
            if (submit) {
                mouse(submit);
                return 'click';
            }
            if (typeof window.submitForm === 'function') {
                window.submitForm(true, function () { }, window.jQuery ? window.jQuery(document) : document);
                return 'submitForm';
            }
        } catch (e) { /* ignore */ }
        return 'none';
    };

    window.__lumen = L;
    return 'ok';
})();
