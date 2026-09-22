package com.nachoz12341.playkeyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.util.AttributeSet;

/**
 * Standard {@link KeyboardView} with one addition: a stroked highlight drawn on top
 * of the currently D-pad-focused key. Touch handling, key rendering and layout are
 * all left to the base class; this only overlays the focus indicator, since fighting
 * KeyboardView's internal pressed-state drawable machinery for an unrelated "focus"
 * concept is more trouble than it's worth.
 */
public class PlayKeyboardView extends KeyboardView {

    private final Paint focusPaint;
    private Keyboard.Key focusedKey;

    public PlayKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        focusPaint = new Paint();
        focusPaint.setAntiAlias(true);
        focusPaint.setStyle(Paint.Style.STROKE);
        focusPaint.setStrokeWidth(dpToPx(3));
        focusPaint.setColor(0xFFFF9800);
    }

    public void setFocusedKey(Keyboard.Key key) {
        this.focusedKey = key;
        invalidate();
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (focusedKey == null) {
            return;
        }
        float left = focusedKey.x + getPaddingLeft();
        float top = focusedKey.y + getPaddingTop();
        float right = left + focusedKey.width;
        float bottom = top + focusedKey.height;
        canvas.drawRoundRect(new RectF(left, top, right, bottom), 6f, 6f, focusPaint);
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
