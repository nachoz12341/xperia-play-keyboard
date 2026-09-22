package com.nachoz12341.playkeyboard;

import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.util.Locale;

/**
 * The IME itself. Touch-driven key presses (via {@link KeyboardView.OnKeyboardActionListener})
 * and D-pad/gamepad-button-driven key presses (via {@link #onKeyDown}) both funnel through
 * {@link #handleKeySelection(int)}, so there is exactly one place that commits text.
 *
 * Hardware key handling is gated on {@link #isInputViewShown()}: when this keyboard isn't
 * the thing on screen, events are left unconsumed and fall through to normal system/app
 * behavior, matching how an IME's hardware-key callbacks are meant to work.
 */
public class PlayKeyboardService extends InputMethodService implements KeyboardView.OnKeyboardActionListener {

    private enum Page { LETTERS, SYMBOLS }

    /** OFF: lowercase. SHIFT_ONCE: next letter only. CAPS_LOCK: stays on until toggled off. */
    private enum ShiftState { OFF, SHIFT_ONCE, CAPS_LOCK }

    private PlayKeyboardView keyboardView;
    private Keyboard qwertyKeyboard;
    private Keyboard symbolsKeyboard;
    private Keyboard symbolsShiftKeyboard;
    private KeyFocusController focusController;

    private Page currentPage = Page.LETTERS;
    private ShiftState shiftState = ShiftState.OFF;
    private long lastShiftPressTime = 0;
    private boolean symbolsShiftOn = false;

    /** True while the R trigger (KEYCODE_BUTTON_R1) is held, repurposing D-pad
     *  left/right for text-cursor movement instead of key-focus movement. */
    private boolean cursorModifierHeld = false;

    private long lastEnterPressTime = 0;
    private boolean lastEnterInsertedNewline = false;

    @Override
    public void onCreate() {
        super.onCreate();
        qwertyKeyboard = new Keyboard(this, R.xml.kbd_qwerty);
        symbolsKeyboard = new Keyboard(this, R.xml.kbd_symbols);
        symbolsShiftKeyboard = new Keyboard(this, R.xml.kbd_symbols_shift);
        focusController = new KeyFocusController();
    }

    @Override
    public View onCreateInputView() {
        keyboardView = (PlayKeyboardView) getLayoutInflater().inflate(R.layout.input, null);
        keyboardView.setOnKeyboardActionListener(this);
        keyboardView.setPreviewEnabled(false);
        return keyboardView;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        resetToLetters();
    }

    private void resetToLetters() {
        currentPage = Page.LETTERS;
        shiftState = ShiftState.OFF;
        symbolsShiftOn = false;
        lastEnterPressTime = 0;
        lastEnterInsertedNewline = false;
        applyShiftLabels(qwertyKeyboard, false);
        keyboardView.setKeyboard(qwertyKeyboard);
        focusController.setFocusedKey(null);
        focusController.setKeyboard(qwertyKeyboard);
        keyboardView.setFocusedKey(focusController.getFocusedKey());
    }

    // ---- Hardware key interception (D-pad + Xperia Play face buttons) ----

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!isInputViewShown()) {
            return super.onKeyDown(keyCode, event);
        }

        if (KeyMap.isCursorModifier(keyCode)) {
            cursorModifierHeld = true;
            return true;
        }
        if (cursorModifierHeld
                && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            // Let it fall through to the focused EditText's own D-pad handling,
            // which moves the text cursor - exactly what happens when no IME
            // intercepts the event at all.
            return super.onKeyDown(keyCode, event);
        }

        switch (KeyMap.classify(keyCode, event)) {
            case KeyMap.ACTION_MOVE_UP:
                moveFocus(KeyFocusController.Direction.UP);
                return true;
            case KeyMap.ACTION_MOVE_DOWN:
                moveFocus(KeyFocusController.Direction.DOWN);
                return true;
            case KeyMap.ACTION_MOVE_LEFT:
                moveFocus(KeyFocusController.Direction.LEFT);
                return true;
            case KeyMap.ACTION_MOVE_RIGHT:
                moveFocus(KeyFocusController.Direction.RIGHT);
                return true;
            case KeyMap.ACTION_CONFIRM: {
                Keyboard.Key focused = focusController.getFocusedKey();
                if (focused != null && focused.codes != null && focused.codes.length > 0) {
                    handleKeySelection(focused.codes[0]);
                }
                return true;
            }
            case KeyMap.ACTION_BACKSPACE:
                handleBackspace();
                return true;
            case KeyMap.ACTION_PAGE_TOGGLE:
                togglePage();
                return true;
            case KeyMap.ACTION_SHIFT_TOGGLE:
                toggleShift();
                return true;
            case KeyMap.ACTION_FORCE_SUBMIT:
                forceSubmit();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (!isInputViewShown()) {
            return super.onKeyUp(keyCode, event);
        }

        if (KeyMap.isCursorModifier(keyCode)) {
            cursorModifierHeld = false;
            return true;
        }
        if (cursorModifierHeld
                && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            return super.onKeyUp(keyCode, event);
        }

        if (KeyMap.classify(keyCode, event) != KeyMap.ACTION_NONE) {
            // The matching down event was already handled; swallow up so it
            // doesn't also fall through to the app/system (e.g. as a Back press).
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void moveFocus(KeyFocusController.Direction direction) {
        focusController.moveFocus(direction);
        keyboardView.setFocusedKey(focusController.getFocusedKey());
    }

    // ---- Shared key-commit path (touch and D-pad both funnel through here) ----

    private void handleKeySelection(int code) {
        if (code == Keyboard.KEYCODE_SHIFT) {
            toggleShift();
        } else if (code == Keyboard.KEYCODE_DELETE) {
            handleBackspace();
        } else if (code == Keyboard.KEYCODE_MODE_CHANGE) {
            togglePage();
        } else if (code == Keyboard.KEYCODE_DONE) {
            handleEnter();
        } else {
            commitCharacter(code);
        }
    }

    private void commitCharacter(int code) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return;
        }
        char character = (char) code;
        boolean isLetter = Character.isLetter(character);
        boolean shifted = currentPage == Page.LETTERS && shiftState != ShiftState.OFF && isLetter;
        if (shifted) {
            character = Character.toUpperCase(character);
        }
        ic.commitText(String.valueOf(character), 1);

        // A single (non-locked) shift only capitalizes the next letter typed.
        if (currentPage == Page.LETTERS && shiftState == ShiftState.SHIFT_ONCE && isLetter) {
            shiftState = ShiftState.OFF;
            applyShiftLabels(qwertyKeyboard, false);
            keyboardView.invalidateAllKeys();
        }
    }

    private void handleBackspace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.deleteSurroundingText(1, 0);
        }
    }

    /**
     * A single Enter press does the field's normal thing (performs its declared
     * editor action, or inserts a newline if it declared none - e.g. most
     * multi-line messaging compose boxes). If the field declared a real action,
     * double-tapping Enter quickly instead force-submits via {@link #forceSubmit()},
     * undoing the newline the first tap inserted - this is the escape hatch for
     * apps that set the multi-line "no enter action" flag but still want a
     * declared action reachable. When the field declared no action at all, a
     * double-tap is just treated as two ordinary Enter presses - see
     * {@link #forceSubmit()} for why that case can't be helped from here.
     */
    private void handleEnter() {
        long now = SystemClock.uptimeMillis();
        boolean doubleTapped = lastEnterPressTime != 0
                && (now - lastEnterPressTime) <= ViewConfiguration.getDoubleTapTimeout();

        // If there's no explicit action to force, a double-tap has nothing useful
        // to do beyond what a single tap already does - fall through to that
        // instead of deleting the just-typed newline for no benefit.
        if (doubleTapped && hasExplicitEditorAction()) {
            lastEnterPressTime = 0;
            InputConnection ic = getCurrentInputConnection();
            if (ic != null && lastEnterInsertedNewline) {
                ic.deleteSurroundingText(1, 0);
            }
            forceSubmit();
            return;
        }

        lastEnterPressTime = now;
        performDefaultEnterAction();
    }

    private void performDefaultEnterAction() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return;
        }
        EditorInfo editorInfo = getCurrentInputEditorInfo();
        int action = editorInfo != null ? (editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION) : EditorInfo.IME_ACTION_NONE;
        boolean noEnterFlag = editorInfo != null && (editorInfo.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;

        if (!noEnterFlag && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            lastEnterInsertedNewline = false;
            ic.performEditorAction(action);
        } else {
            lastEnterInsertedNewline = true;
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        }
    }

    /**
     * Forces the field's declared editor action (Send/Go/Done/...), bypassing its
     * multi-line "insert newline instead" default. Bound to the Start button, and
     * to double-tapping Enter.
     *
     * Deliberately does nothing if the field declared no action (NONE/UNSPECIFIED):
     * performEditorAction()'s default fallback, when nothing in the app actually
     * consumes that action, is to just hide the soft keyboard - confirmed by
     * testing against a real messaging app's compose box, where guessing
     * IME_ACTION_SEND for an UNSPECIFIED field did exactly that (and nothing else)
     * instead of sending. An app whose Send is a standalone UI button with no
     * editor-action hook at all can't be reached from the keyboard this way -
     * there's no IME-side workaround for that case.
     */
    private void forceSubmit() {
        if (!hasExplicitEditorAction()) {
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        EditorInfo editorInfo = getCurrentInputEditorInfo();
        if (ic != null && editorInfo != null) {
            ic.performEditorAction(editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION);
        }
    }

    private boolean hasExplicitEditorAction() {
        EditorInfo editorInfo = getCurrentInputEditorInfo();
        int action = editorInfo != null ? (editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION) : EditorInfo.IME_ACTION_NONE;
        return action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED;
    }

    private void toggleShift() {
        if (currentPage == Page.LETTERS) {
            long now = SystemClock.uptimeMillis();
            if (shiftState == ShiftState.CAPS_LOCK) {
                shiftState = ShiftState.OFF;
            } else if (shiftState == ShiftState.SHIFT_ONCE
                    && (now - lastShiftPressTime) <= ViewConfiguration.getDoubleTapTimeout()) {
                shiftState = ShiftState.CAPS_LOCK;
            } else {
                shiftState = ShiftState.SHIFT_ONCE;
            }
            lastShiftPressTime = now;
            applyShiftLabels(qwertyKeyboard, shiftState != ShiftState.OFF);
            keyboardView.invalidateAllKeys();
        } else {
            symbolsShiftOn = !symbolsShiftOn;
            Keyboard target = symbolsShiftOn ? symbolsShiftKeyboard : symbolsKeyboard;
            keyboardView.setKeyboard(target);
            focusController.setKeyboard(target);
            keyboardView.setFocusedKey(focusController.getFocusedKey());
        }
    }

    private void togglePage() {
        if (currentPage == Page.LETTERS) {
            currentPage = Page.SYMBOLS;
            symbolsShiftOn = false;
            keyboardView.setKeyboard(symbolsKeyboard);
            focusController.setKeyboard(symbolsKeyboard);
        } else {
            currentPage = Page.LETTERS;
            keyboardView.setKeyboard(qwertyKeyboard);
            focusController.setKeyboard(qwertyKeyboard);
        }
        keyboardView.setFocusedKey(focusController.getFocusedKey());
    }

    /** Visual-only: swaps letter key labels between cases. Committed case is decided
     *  separately in {@link #commitCharacter} so label updates never affect it. */
    private void applyShiftLabels(Keyboard keyboard, boolean shifted) {
        for (Keyboard.Key key : keyboard.getKeys()) {
            if (key.label != null && key.label.length() == 1 && Character.isLetter(key.label.charAt(0))) {
                String letter = key.label.toString();
                key.label = shifted ? letter.toUpperCase(Locale.US) : letter.toLowerCase(Locale.US);
            }
        }
    }

    private Keyboard.Key findKeyByCode(int code) {
        Keyboard active = keyboardView.getKeyboard();
        if (active == null) {
            return null;
        }
        for (Keyboard.Key key : active.getKeys()) {
            if (key.codes != null && key.codes.length > 0 && key.codes[0] == code) {
                return key;
            }
        }
        return null;
    }

    // ---- KeyboardView.OnKeyboardActionListener (touch input) ----

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        handleKeySelection(primaryCode);
    }

    @Override
    public void onPress(int primaryCode) {
        Keyboard.Key touched = findKeyByCode(primaryCode);
        if (touched != null) {
            focusController.setFocusedKey(touched);
            keyboardView.setFocusedKey(touched);
        }
    }

    @Override
    public void onRelease(int primaryCode) {
        // No-op.
    }

    @Override
    public void onText(CharSequence text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null && text != null) {
            ic.commitText(text, 1);
        }
    }

    @Override
    public void swipeLeft() {
        // No-op.
    }

    @Override
    public void swipeRight() {
        // No-op.
    }

    @Override
    public void swipeDown() {
        // No-op.
    }

    @Override
    public void swipeUp() {
        // No-op.
    }
}
