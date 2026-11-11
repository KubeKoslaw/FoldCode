package io.fold.keys

import android.view.KeyEvent

enum class Mod { CTRL, ALT, SHIFT }

/** Off, one-shot (applies to the next key), or locked (double tap). */
enum class Latch { OFF, ONCE, LOCK }

sealed interface Action {
    /** Types text; Ctrl/Alt latched turn it into a shortcut, Shift into upper case. */
    data class Text(val text: String) : Action
    /** A non-character key (arrows, Esc, Tab, Enter, Backspace…). */
    data class Code(val keyCode: Int, val repeat: Boolean = false) : Action
    /** A fixed shortcut, e.g. Ctrl+Shift+P. */
    data class Chord(val keyCode: Int, val ctrl: Boolean = true, val shift: Boolean = false, val alt: Boolean = false) : Action
    data class Modifier(val mod: Mod) : Action
    data class Layer(val name: String) : Action
    data object ToCodeKeyboard : Action
    data object ToSystemKeyboard : Action
    /** From the bar's Ctrl row: open the full keyboard with Ctrl still held, for any other combination. */
    data object CodeKeyboardKeepCtrl : Action
    data object Collapse : Action
    data object Expand : Action
    data object None : Action
}

enum class Style { CHAR, SPECIAL, ACCENT, SPACER }

data class Key(
    val label: String,
    val action: Action,
    val width: Float = 1f,
    /** Long-press alternatives, e.g. Polish letters. */
    val alts: List<String> = emptyList(),
    val style: Style = Style.CHAR,
)

typealias Row = List<Key>

object Layouts {
    const val LETTERS = "letters"
    const val SYMBOLS = "symbols"

    private fun ch(c: String, alts: String = "") = Key(c, Action.Text(c), alts = alts.split(' ').filter { it.isNotEmpty() })
    private fun code(label: String, kc: Int, w: Float = 1f, repeat: Boolean = false) =
        Key(label, Action.Code(kc, repeat), w, style = Style.SPECIAL)
    private fun mod(label: String, m: Mod, w: Float = 1f) = Key(label, Action.Modifier(m), w, style = Style.SPECIAL)
    private fun chord(label: String, kc: Int, shift: Boolean = false) =
        Key(label, Action.Chord(kc, ctrl = true, shift = shift), style = Style.SPECIAL)
    private fun spacer(w: Float) = Key("", Action.None, w, style = Style.SPACER)

    private val esc = code("Esc", KeyEvent.KEYCODE_ESCAPE)
    private val tab = code("Tab", KeyEvent.KEYCODE_TAB)
    private val left = code("←", KeyEvent.KEYCODE_DPAD_LEFT, repeat = true)
    private val down = code("↓", KeyEvent.KEYCODE_DPAD_DOWN, repeat = true)
    private val up = code("↑", KeyEvent.KEYCODE_DPAD_UP, repeat = true)
    private val right = code("→", KeyEvent.KEYCODE_DPAD_RIGHT, repeat = true)
    private val backspace = code("⌫", KeyEvent.KEYCODE_DEL, 1.5f, repeat = true)
    private val enter = code("⏎", KeyEvent.KEYCODE_ENTER, 1.5f)
    private val space = Key(" ", Action.Text(" "), 4f)

    val toCode = Key("⌨", Action.ToCodeKeyboard, style = Style.ACCENT)
    val toSystem = Key("🌐", Action.ToSystemKeyboard, style = Style.SPECIAL)
    val expand = Key("▴", Action.Expand, style = Style.ACCENT)
    private val collapse = Key("▾", Action.Collapse, style = Style.SPECIAL)

    /** Top row everywhere. Shift sits here when there is no letters row (system keyboard, collapsed). */
    fun functionRow(withShift: Boolean, last: Key): Row = buildList {
        add(esc); add(tab); add(mod("Ctrl", Mod.CTRL)); add(mod("Alt", Mod.ALT))
        if (withShift) add(mod("⇧", Mod.SHIFT))
        add(left); add(down); add(up); add(right); add(last)
    }

    /** Second bar row above the system keyboard: the symbols that are slow to reach there. */
    val barSymbols: Row = "{ } [ ] ( ) < > ; = | /".split(' ').map { ch(it) }

    /** Second bar row while Ctrl is held: common shortcuts, plus the full keyboard for the rest. */
    val barCtrl: Row = listOf(
        chord("^C", KeyEvent.KEYCODE_C), chord("^V", KeyEvent.KEYCODE_V), chord("^X", KeyEvent.KEYCODE_X),
        chord("^Z", KeyEvent.KEYCODE_Z), chord("^Y", KeyEvent.KEYCODE_Y), chord("^S", KeyEvent.KEYCODE_S),
        chord("^F", KeyEvent.KEYCODE_F), chord("^A", KeyEvent.KEYCODE_A), chord("^/", KeyEvent.KEYCODE_SLASH),
        chord("⇧^P", KeyEvent.KEYCODE_P, shift = true),
        Key("abc", Action.CodeKeyboardKeepCtrl, style = Style.ACCENT),
    )

    private val numbers: Row = "1 2 3 4 5 6 7 8 9 0".split(' ').zip("! @ # $ % ^ & * ( )".split(' '))
        .map { (n, alt) -> ch(n, alt) }

    private fun bottomRow(layerKey: Key): Row = listOf(layerKey, ch(",", "; <"), space, ch(".", ": > …"), enter, collapse)

    val letters: List<Row> = listOf(
        numbers,
        listOf(ch("q"), ch("w"), ch("e", "ę"), ch("r"), ch("t"), ch("y"), ch("u"), ch("i"), ch("o", "ó"), ch("p")),
        listOf(spacer(0.5f), ch("a", "ą"), ch("s", "ś"), ch("d"), ch("f"), ch("g"), ch("h"), ch("j"), ch("k"), ch("l", "ł"), spacer(0.5f)),
        listOf(mod("⇧", Mod.SHIFT, 1.5f), ch("z", "ż ź"), ch("x"), ch("c", "ć"), ch("v"), ch("b"), ch("n", "ń"), ch("m"), backspace),
        bottomRow(Key("?#{", Action.Layer(SYMBOLS), 1.5f, style = Style.SPECIAL)),
    )

    val symbols: List<Row> = listOf(
        numbers,
        "! @ # $ % ^ & * ( )".split(' ').map { ch(it) },
        "{ } [ ] < > ; : ' \"".split(' ').map { ch(it) },
        "` ~ \\ | / - _ = +".split(' ').map { ch(it) } + backspace,
        bottomRow(Key("abc", Action.Layer(LETTERS), 1.5f, style = Style.SPECIAL)),
    )
}
