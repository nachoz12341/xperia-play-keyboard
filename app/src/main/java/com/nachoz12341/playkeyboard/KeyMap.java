package com.nachoz12341.playkeyboard;

import android.view.KeyEvent;

/**
 * Single source of truth mapping raw hardware {@link KeyEvent}s to logical keyboard
 * actions. Current bindings:
 *   D-pad          -> move D-pad focus between keys
 *   X / Cross       -> confirm/select the focused key (also DPAD_CENTER as a fallback,
 *                       since Cross reportedly reports as DPAD_CENTER on real hardware)
 *   Square          -> backspace
 *   L trigger       -> shift toggle
 *   Triangle        -> toggle letters/symbols page
 *   Circle / Back   -> deliberately left unhandled; falls through as plain system Back
 *   Start           -> force-submit (performEditorAction), bypassing a field's
 *                       default "Enter inserts newline" behavior - see
 *                       PlayKeyboardService#forceSubmit. Double-tapping the
 *                       on-screen Enter key does the same thing.
 *   R trigger (held) + D-pad left/right -> handled separately in PlayKeyboardService
 *                       as a stateful modifier, not a single-shot action here
 * If real-device testing finds different actual keycodes, only this file needs to change.
 */
public final class KeyMap {

    private KeyMap() {
    }

    public static final int ACTION_NONE = 0;
    public static final int ACTION_MOVE_UP = 1;
    public static final int ACTION_MOVE_DOWN = 2;
    public static final int ACTION_MOVE_LEFT = 3;
    public static final int ACTION_MOVE_RIGHT = 4;
    public static final int ACTION_CONFIRM = 5;
    public static final int ACTION_BACKSPACE = 6;
    public static final int ACTION_PAGE_TOGGLE = 7;
    public static final int ACTION_SHIFT_TOGGLE = 8;
    public static final int ACTION_FORCE_SUBMIT = 9;

    public static int classify(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return ACTION_MOVE_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return ACTION_MOVE_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return ACTION_MOVE_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return ACTION_MOVE_RIGHT;

            // Confirm/select: expected KEYCODE_BUTTON_A (Cross), plus the documented
            // real-hardware fallback where Cross instead reports as DPAD_CENTER.
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                return ACTION_CONFIRM;

            case KeyEvent.KEYCODE_BUTTON_X:
                return ACTION_BACKSPACE;

            case KeyEvent.KEYCODE_BUTTON_L1:
                return ACTION_SHIFT_TOGGLE;

            case KeyEvent.KEYCODE_BUTTON_Y:
                return ACTION_PAGE_TOGGLE;

            case KeyEvent.KEYCODE_BUTTON_START:
                return ACTION_FORCE_SUBMIT;

            // Circle (KEYCODE_BUTTON_B) and plain Back (KEYCODE_BACK) are deliberately
            // left unclassified so they fall through as normal system Back and close
            // the IME, rather than being repurposed for anything else.
            default:
                return ACTION_NONE;
        }
    }

    /** KEYCODE_BUTTON_R1 (R trigger): held-down modifier that repurposes D-pad
     *  left/right for text-cursor movement instead of key-focus movement. Stateful,
     *  so it's tracked in PlayKeyboardService rather than folded into classify(). */
    public static boolean isCursorModifier(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BUTTON_R1;
    }
}
