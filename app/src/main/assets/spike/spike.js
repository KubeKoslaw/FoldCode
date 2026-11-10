// Phase 0 keyboard spike, web side.
// Hosts a Monaco editor, an xterm.js terminal and a plain <input>, records every keyboard/input
// event, and exposes window.spike so the native side can reset a target, send input through the
// JS bridge or as synthetic DOM events, and read back what arrived.
'use strict';

const bar = document.getElementById('bar');
const eventsEl = document.getElementById('events');
const plain = document.getElementById('plain');

let editor = null;
let term = null;
let termData = '';
let counters = { save: 0, palette: 0 };
let target = 'monaco';
const events = [];
const commands = {};

// ---- event recording -------------------------------------------------------------------------
function describe(el) {
  if (!el || el === document || el === document.body) return 'body';
  if (el.closest && el.closest('#monaco')) return 'monaco';
  if (el.closest && el.closest('#term')) return 'xterm';
  if (el === plain) return 'input';
  return (el.id || el.tagName || '?').toString().toLowerCase();
}

function record(e) {
  const r = {
    type: e.type, key: e.key, code: e.code, keyCode: e.keyCode,
    mods: (e.ctrlKey ? 'C' : '') + (e.altKey ? 'A' : '') + (e.shiftKey ? 'S' : '') + (e.metaKey ? 'M' : ''),
    trusted: e.isTrusted, inputType: e.inputType, data: e.data, tgt: describe(e.target),
  };
  events.push(r);
  if (events.length > 80) events.shift();
  const line = document.createElement('div');
  line.className = r.trusted ? 't' : 'u';
  line.textContent = `${r.type.padEnd(17)} ${r.tgt.padEnd(7)} key=${JSON.stringify(r.key)} code=${r.code || ''} kc=${r.keyCode ?? ''} ${r.mods}` +
    (r.inputType ? ` inputType=${r.inputType}` : '') + (r.data != null ? ` data=${JSON.stringify(r.data)}` : '') +
    (r.trusted ? '' : ' UNTRUSTED');
  eventsEl.prepend(line);
  while (eventsEl.childNodes.length > 60) eventsEl.lastChild.remove();
}

for (const t of ['keydown', 'keypress', 'keyup', 'beforeinput', 'input', 'compositionstart', 'compositionupdate', 'compositionend']) {
  document.addEventListener(t, record, true);
}
document.addEventListener('focusin', () => {
  // Manual testing: bridge input follows whatever you tapped last.
  const d = describe(document.activeElement);
  if (d === 'monaco' || d === 'xterm' || d === 'input') target = d;
  updateBar();
}, true);

function updateBar(note) {
  bar.textContent = `focus=${describe(document.activeElement)} target=${target} save=${counters.save} palette=${counters.palette}` + (note ? ` · ${note}` : '');
}

// ---- xterm.js ----------------------------------------------------------------------------------
term = new Terminal({ fontSize: 13, cursorBlink: true, convertEol: true, scrollback: 200 });
term.open(document.getElementById('term'));
term.onData((d) => {
  termData += d;
  // Echo what arrived, with control characters made visible.
  term.write(Array.from(d).map((c) => {
    const n = c.charCodeAt(0);
    return n < 32 || n === 127 ? `\x1b[33m\\x${n.toString(16).padStart(2, '0')}\x1b[0m` : c;
  }).join(''));
});
term.writeln('xterm.js ready — keys you send show up here');

// ---- Monaco ----------------------------------------------------------------------------------
require.config({ paths: { vs: 'vendor/monaco/vs' } });
require(['vs/editor/editor.main'], () => {
  editor = monaco.editor.create(document.getElementById('monaco'), {
    value: '// Monaco ready\n',
    language: 'javascript',
    theme: 'vs-dark',
    automaticLayout: true,
    fontSize: 14,
    minimap: { enabled: false },
    // Keep typed text exactly as sent so the tests can compare it.
    autoClosingBrackets: 'never', autoClosingQuotes: 'never', autoClosingOvertype: 'never', autoSurround: 'never',
    autoIndent: 'none', formatOnType: false, quickSuggestions: false, suggestOnTriggerCharacters: false,
    acceptSuggestionOnEnter: 'off', wordBasedSuggestions: 'off', tabSize: 4, insertSpaces: true,
  });
  commands.save = () => { counters.save++; updateBar('Ctrl+S command ran'); };
  commands.palette = () => { counters.palette++; updateBar('Ctrl+Shift+P command ran'); };
  editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, commands.save);
  editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.KeyP, commands.palette);
  updateBar('ready');
  console.log('spike ready');
});

// ---- bridge helpers ----------------------------------------------------------------------------
const XTERM_KEYS = {
  ArrowLeft: '\x1b[D', ArrowRight: '\x1b[C', ArrowUp: '\x1b[A', ArrowDown: '\x1b[B',
  Home: '\x1b[H', End: '\x1b[F', Backspace: '\x7f', Delete: '\x1b[3~', Enter: '\r', Tab: '\t', Escape: '\x1b',
};
const MONACO_KEYS = {
  ArrowLeft: 'cursorLeft', ArrowRight: 'cursorRight', ArrowUp: 'cursorUp', ArrowDown: 'cursorDown',
  Home: 'cursorHome', End: 'cursorEnd', Backspace: 'deleteLeft', Delete: 'deleteRight', Tab: 'tab',
};

function bridgeKey(name, ctrl, alt, shift) {
  const k = name.length === 1 ? name.toLowerCase() : name;
  if (target === 'monaco') {
    if (ctrl && k === 's') return commands.save();
    if (ctrl && shift && k === 'p') return commands.palette();
    if (ctrl && k === 'z') return editor.trigger('foldhost', 'undo', null);
    if (ctrl && (k === 'y' || (shift && k === 'z'))) return editor.trigger('foldhost', 'redo', null);
    if (k === 'Enter') return editor.trigger('foldhost', 'type', { text: '\n' });
    if (k === 'Escape') return editor.trigger('foldhost', 'cancelSelection', null);
    const cmd = MONACO_KEYS[k];
    if (cmd) return editor.trigger('foldhost', shift && cmd.startsWith('cursor') ? cmd + 'Select' : cmd, null);
    return editor.trigger('foldhost', 'type', { text: name });
  }
  if (target === 'xterm') {
    let seq = XTERM_KEYS[k];
    if (!seq && ctrl && k.length === 1) seq = String.fromCharCode(k.toUpperCase().charCodeAt(0) & 0x1f);
    if (!seq) seq = name;
    if (alt) seq = '\x1b' + seq;
    return term.input(seq, true);
  }
  // plain <input>
  plain.focus();
  const pos = plain.selectionStart;
  if (k === 'Backspace') return document.execCommand('delete');
  if (k === 'ArrowLeft') return plain.setSelectionRange(Math.max(0, pos - 1), Math.max(0, pos - 1));
  if (k === 'ArrowRight') return plain.setSelectionRange(pos + 1, pos + 1);
  if (k === 'Home') return plain.setSelectionRange(0, 0);
  if (k === 'End') return plain.setSelectionRange(plain.value.length, plain.value.length);
  if (name.length === 1) return document.execCommand('insertText', false, name);
}

// ---- API for the native side -------------------------------------------------------------------
window.spike = {
  ready() { return !!(editor && term); },

  reset(t) {
    target = t;
    termData = '';
    counters = { save: 0, palette: 0 };
    events.length = 0;
    if (t === 'monaco') { editor.setValue(''); editor.focus(); }
    else if (t === 'xterm') { term.clear(); term.focus(); }
    else { plain.value = ''; plain.focus(); }
    updateBar('reset');
    return 'ok';
  },

  read(t) {
    const out = { target: t, focus: describe(document.activeElement), counters, events: events.slice(-40) };
    if (t === 'monaco') {
      out.value = editor.getValue();
      out.cursor = editor.getPosition();
      out.textFocus = editor.hasTextFocus();
    } else if (t === 'xterm') {
      out.value = termData;
    } else {
      out.value = plain.value;
      out.cursor = plain.selectionStart;
    }
    return JSON.stringify(out);
  },

  type(text) {
    if (target === 'monaco') editor.trigger('foldhost', 'type', { text });
    else if (target === 'xterm') term.input(text, true);
    else { plain.focus(); document.execCommand('insertText', false, text); }
  },

  key(name, ctrl, alt, shift) { bridgeKey(name, ctrl, alt, shift); },

  // Untrusted DOM events, dispatched where the browser's own key events would go.
  synth(key, code, keyCode, ctrl, alt, shift) {
    const el = document.activeElement || document.body;
    const init = { key, code, keyCode, which: keyCode, ctrlKey: ctrl, altKey: alt, shiftKey: shift, bubbles: true, cancelable: true, composed: true };
    el.dispatchEvent(new KeyboardEvent('keydown', init));
    if (key.length === 1 && !ctrl && !alt) el.dispatchEvent(new KeyboardEvent('keypress', { ...init, charCode: key.charCodeAt(0) }));
    el.dispatchEvent(new KeyboardEvent('keyup', init));
  },
};
