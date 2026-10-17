hterm.defaultStorage = new lib.Storage.Memory();
window.onload = async function() {
    await lib.init();
    window.term = new hterm.Terminal();

    // make everything invisible so as to not be embarrassing
    term.getPrefs().set('background-color', 'transparent');
    term.getPrefs().set('foreground-color', 'transparent');
    term.getPrefs().set('cursor-color', 'transparent');

    term.getPrefs().set('terminal-encoding', 'iso-2022');
    term.getPrefs().set('enable-resize-status', false);
    term.getPrefs().set('copy-on-select', false);
    term.getPrefs().set('enable-clipboard-notice', false);
    term.getPrefs().set('user-css-text', termCss);
    term.getPrefs().set('screen-padding-size', 4);
    // Creating and preloading the <audio> element for this sometimes hangs WebKit on iOS 16 for some reason. Can be most easily reproduced by resetting a simulator and starting the app. System logs show Fig hanging while trying to do work.
    term.getPrefs().set('audible-bell-sound', '');

    term.onTerminalReady = onTerminalReady;
    term.decorate(document.getElementById('terminal'));
};

// Cinder Lite: Noto Sans Symbols 2 (a 70 KB cut) behind the system monospace font, for the symbols
// no iOS monospace font has, Claude Code's ⏵⏵ mode marker among them. hterm draws in its own frame,
// so the font is declared in its CSS, by absolute URL.
var termCss = `
@font-face {
    font-family: CinderSymbols;
    src: url("${new URL('symbols.ttf', document.baseURI).href}");
}
x-screen {
    background: transparent !important;
    overflow: hidden !important;
    -webkit-tap-highlight-color: transparent;
}
x-row {
  text-rendering: optimizeLegibility;
  font-variant-ligatures: normal;
}
.uri-node {
  text-decoration: underline;
}
`;

function onTerminalReady() {

// Shorthand for JS -> native IPC
const native = new Proxy({}, {
    get(obj, prop) {
        return (...args) => {
            if (args.length == 0)
                args = null;
            else if (args.length == 1)
                args = args[0];
            webkit.messageHandlers[prop].postMessage(args);
        };
    },
});

// Functions for native -> JS
window.exports = {};

term.io.push();
term.reset();

let oldProps = {};
function syncProp(name, value) {
    if (oldProps[name] !== value)
        native.propUpdate(name, value);
}
// Force a full viewport repaint after output, coalesced to one per animation
// frame. Under iOS WKWebView, hterm's incremental dirty-row redraw can miss
// changed row ranges for rapidly-updated regions (e.g. htop's meters and
// per-process fields), leaving stale pixels until a full repaint (^L). An
// explicit scheduleInvalidate() + scheduleRedraw() repaints the whole visible
// viewport, fixing the partially-painted rows. Mirrors the PSCAL iPad port.
let pendingViewportRefresh = false;
function scheduleViewportRefresh() {
    if (pendingViewportRefresh)
        return;
    pendingViewportRefresh = true;
    requestAnimationFrame(() => {
        pendingViewportRefresh = false;
        if (!term.scrollPort_)
            return;
        // Not while text is selected. invalidate() throws away every row node
        // on screen and draws new ones, so the selection is left pointing at
        // detached nodes: hterm then cannot find the rows it starts and ends
        // on, and it also stops keeping an off-screen end row alive. Any output
        // during a selection -- a prompt, a clock, tmux's status line -- cut
        // what Copy copied down to what was on screen, or to nothing (#617).
        // hterm's own redraw keeps the selection's rows, so it alone runs
        // until the selection is gone; htop's stale cells can wait that long.
        if (liveSelection() == null)
            term.scrollPort_.scheduleInvalidate();
        term.scrollPort_.scheduleRedraw();
        syncScroll();
        layoutComposition();
    });
}
exports.write = (data) => {
    // Feed raw bytes into hterm's writeUTF8, which uses an internal *streaming*
    // UTF-8 decoder (TextDecoder with {stream: true}). The previous path ran a
    // standalone, non-streaming TextDecoder here and then writeUTF16: any
    // multibyte UTF-8 sequence that straddled the boundary between two coalesced
    // output chunks got flushed as U+FFFD on both sides, corrupting glyphs like
    // htop's box-drawing/meter characters. Streaming decode reassembles them.
    term.io.writeUTF8(lib.codec.stringToCodeUnitArray(data));
    syncProp('applicationCursor', term.keyboard.applicationCursor);
    scheduleViewportRefresh();
};
term.io.sendString = term.io.onVTKeyStroke = (data) => {
    native.sendInput(data);
};

// IME composition: the pinyin (or kana, or jamo) being typed before a
// candidate is chosen. It belongs to the keyboard, not the guest, so it is
// never written into hterm's screen: it is drawn in a node of its own laid over
// the cursor, and only the committed text reaches the pty (native sends it).
// Native also needs to know where the cursor is on screen, so that a hardware
// keyboard's candidate window can sit next to it rather than in the top-left
// corner -- the node stays in place at the cursor while hidden, and its cell is
// reported to native whenever it moves.
const screenDoc = term.scrollPort_.getDocument();
const imeNode = screenDoc.createElement('div');
imeNode.id = 'aok-ime-composition';
imeNode.style.cssText = `
position: absolute;
left: calc(var(--hterm-screen-padding-size) +
    var(--hterm-charsize-width) * var(--hterm-ime-col, 0));
top: calc(var(--hterm-screen-padding-size) +
    var(--hterm-charsize-height) * var(--hterm-cursor-offset-row, 0));
min-width: var(--hterm-charsize-width);
height: var(--hterm-charsize-height);
line-height: var(--hterm-charsize-height);
white-space: pre;
visibility: hidden;
pointer-events: none;
z-index: 1;
color: rgb(var(--hterm-foreground-color));
background-color: rgb(var(--hterm-background-color));
box-shadow: inset 0 -2px 0 rgb(var(--hterm-foreground-color));`;
term.scrollPort_.screen_.appendChild(imeNode);
let imeText = '';
let lastImeRect = '';
function layoutComposition() {
    // Columns as hterm will lay the text out once it is committed and echoed:
    // a wide character in a .wc-node, two cells, exactly as hterm draws it.
    const width = imeText ? lib.wc.strWidth(imeText) : 0;
    const cols = term.screenSize.width;
    let col = term.screen_.cursorPosition.column;
    // Near the right edge, slide left so the whole composition stays visible.
    if (col + width > cols)
        col = Math.max(0, cols - width);
    term.setCssVar('ime-col', col);
    const frame = term.scrollPort_.iframe_.getBoundingClientRect();
    const r = imeNode.getBoundingClientRect();
    const cs = term.scrollPort_.characterSize;
    // Where the composition starts, as one cell, in web view coordinates.
    const rect = [frame.left + r.left, frame.top + r.top, cs.width, cs.height];
    const key = rect.join(',');
    // The handler exists only while a TerminalView shows this terminal, which
    // asks again (exports.layoutComposition) when it installs it.
    if (!window.webkit.messageHandlers.imeRect) {
        lastImeRect = '';
        return;
    }
    if (key !== lastImeRect) {
        lastImeRect = key;
        native.imeRect(rect);
    }
}
exports.layoutComposition = () => {
    lastImeRect = '';
    layoutComposition();
};
exports.setComposition = (text) => {
    imeText = text || '';
    imeNode.textContent = '';
    for (const ch of imeText) {
        if (lib.wc.charWidth(ch.codePointAt(0)) == 2) {
            const span = screenDoc.createElement('span');
            span.className = 'wc-node';
            span.textContent = ch;
            imeNode.appendChild(span);
        } else {
            imeNode.appendChild(screenDoc.createTextNode(ch));
        }
    }
    imeNode.style.visibility = imeText ? 'visible' : 'hidden';
    layoutComposition();
};

// hterm size updates native size
term.io.onTerminalResize = () => {
    native.resize();
    requestAnimationFrame(layoutComposition);
};

exports.getSize = () => [term.screenSize.width, term.screenSize.height];

// selection, copying
term.scrollPort_.screen_.contentEditable = false;
term.blur();
term.focus();
exports.copy = () => term.copySelectionToClipboard();

// focus
// This listener blocks blur events that come in because the webview has lost first responder
term.scrollPort_.screen_.addEventListener('blur', (e) => {
    if (e.target.ownerDocument.activeElement == e.target) {
        e.stopImmediatePropagation();
    }
}, {capture: true});
// A tap or click on the terminal gives it keyboard focus back: native.focus() is what makes the
// TerminalView first responder again, and on a click nothing else does.
//
// The exception is a press ON a selection. That belongs to the selection view -- it is how a
// selection is adjusted and how its menu comes up -- and taking first responder away from the
// web view would dismiss both.
//
// Ask the document the rows are actually in. hterm draws them inside an <iframe>, so the
// terminal's selection is that document's. This used to ask the top-level document, which
// never holds terminal text but can hold a caret: a trackpad click can leave one there. From
// then on its rangeCount was never 0 again, every click and tap returned early, and no click
// could give the terminal keyboard focus back (#579). A collapsed range is only a caret, so it
// does not count as a selection either.
const screenDocument = term.scrollPort_.getDocument();
function liveSelection() {
    const selection = screenDocument.getSelection();
    if (selection == null || selection.rangeCount == 0 || selection.isCollapsed)
        return null;
    return selection;
}
function selectionContainsPoint(selection, x, y) {
    for (let i = 0; i < selection.rangeCount; i++) {
        for (const rect of selection.getRangeAt(i).getClientRects()) {
            if (x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom)
                return true;
        }
    }
    return false;
}
let pressOnSelection = false;
term.scrollPort_.screen_.addEventListener('mousedown', (e) => {
    const selection = liveSelection();
    pressOnSelection = selection != null && selectionContainsPoint(selection, e.clientX, e.clientY);
    if (pressOnSelection)
        return;
    // A plain click away from a selection ends it. Left in place it would no longer be shown
    // once the terminal has focus, yet Copy would still copy it. Shift-click extends a selection
    // instead, so that one is left alone.
    if (selection != null && !e.shiftKey)
        selection.removeAllRanges();
    native.focus();
});
// Focus again once the click is over, unless it left a selection behind (a drag or a double
// click that selected text, which is about to be copied). The press is delivered before the
// click has finished, so anything that hands first responder to the web view during the rest of
// it would otherwise have the last word. When the terminal already has focus this is a no-op.
term.scrollPort_.screen_.addEventListener('mouseup', (e) => {
    if (pressOnSelection) {
        pressOnSelection = false;
        return;
    }
    setTimeout(() => {
        if (liveSelection() == null)
            native.focus();
    }, 0);
});
exports.setFocused = (focus) => {
    if (focus)
        term.focus();
    else
        term.blur();
};
term.scrollPort_.screen_.addEventListener('focus', (e) => native.syncFocus());

// scrolling
// Disable hterm builtin touch scrolling
term.scrollPort_.onTouch = (e) => {
    // Convince hterm that we called preventDefault() and that it shouldn't do more handling, but don't actually call it because that would break text selection
    Object.defineProperty(e, 'defaultPrevented', {value: true});
};
// Scroll to bottom wrapper
exports.scrollToBottom = () => term.scrollEnd();
// Set scroll position
exports.newScrollTop = (y) => {
    // two lines instead of one because the value you read out of scrollTop can be different from the value you write into it
    term.scrollPort_.screen_.scrollTop = y;
    lastScrollTop = term.scrollPort_.screen_.scrollTop;
};

// Send scroll height and position to native code
let lastScrollHeight, lastScrollTop;
function syncScroll() {
    const scrollHeight = parseFloat(term.scrollPort_.scrollArea_.style.height);
    if (scrollHeight != lastScrollHeight)
        native.newScrollHeight(scrollHeight);
    lastScrollHeight = scrollHeight;

    const scrollTop = term.scrollPort_.screen_.scrollTop;
    if (scrollTop != lastScrollTop)
        native.newScrollTop(scrollTop);
    lastScrollTop = scrollTop;
}

const realSyncScrollHeight = hterm.ScrollPort.prototype.syncScrollHeight;
hterm.ScrollPort.prototype.syncScrollHeight = function() {
    realSyncScrollHeight.call(this);
    syncScroll();
};
term.scrollPort_.screen_.addEventListener('scroll', syncScroll);

exports.updateStyle = ({foregroundColor, backgroundColor, fontFamily, fontSize, lineHeight, blinkCursor, cursorShape}) => {
    term.getPrefs().set('background-color', backgroundColor);
    term.getPrefs().set('foreground-color', foregroundColor);
    term.getPrefs().set('cursor-color', foregroundColor);
    term.getPrefs().set('font-family', fontFamily);
    term.getPrefs().set('font-size', fontSize);
    // Cell height as a multiple of the font's measured maximum extent. See the
    // 'line-height' pref: a block glyph is shorter than the box its background
    // fills, and the leftover shows as a band beside a Powerline separator.
    term.getPrefs().set('line-height', lineHeight);
    term.getPrefs().set('cursor-blink', blinkCursor);
    term.getPrefs().set('cursor-shape', cursorShape);
    // A new font or size moves every cell; re-measure once it has laid out.
    requestAnimationFrame(layoutComposition);
};

exports.getCharacterSize = () => {
    return [term.scrollPort_.characterSize.width, term.scrollPort_.characterSize.height];
};

exports.clearScrollback = () => term.clearScrollback();

exports.setUserGesture = () => term.accessibilityReader_.hasUserGesture = true;

// Links the guest prints (OSC 8): native opens them only if /dev/url would (CinderURLAllowed).
hterm.openUrl = (url) => native.openLink(url);

// Bracketed paste (DECSET 2004) reaches the guest through NATIVE, not through
// hterm: Cmd-V, the Paste bar button and the snippet sheet all write bytes
// straight to the tty, so hterm's own onPasteData_ -- the thing that knows
// about the mode and wraps the payload in \e[200~ ... \e[201~ -- never runs
// for any of them. Without that wrapper a multi-line paste arrives as a run of
// typed lines and the shell executes each one as it lands, and an editor with
// the mode on cannot tell a paste from typing. hterm still PARSES the mode, so
// publish its state and let native do the wrapping.
//
// reset() and softReset() replace options_ wholesale instead of going through
// setBracketedPaste, so a `reset` that clears the mode has to be caught here
// too, or the native flag stays stuck on with nothing to turn it off.
function syncBracketedPaste() {
    native.propUpdate('bracketedPasteEnabled', !!term.options_.bracketedPaste);
}
const realSetBracketedPaste = hterm.Terminal.prototype.setBracketedPaste;
hterm.Terminal.prototype.setBracketedPaste = function(state) {
    realSetBracketedPaste.call(this, state);
    if (this === term)
        syncBracketedPaste();
};
for (const resetName of ['reset', 'softReset']) {
    const realReset = hterm.Terminal.prototype[resetName];
    hterm.Terminal.prototype[resetName] = function(...args) {
        const result = realReset.apply(this, args);
        if (this === term)
            syncBracketedPaste();
        return result;
    };
}


native.load();
native.syncFocus();
layoutComposition();

}
