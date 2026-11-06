package app.specterscope;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Five-LED level bar in the style of a classic EMF meter (green, green, yellow, orange, red). */
final class LedBar extends View {
    private static final int[] COLORS = {0xFF30D158, 0xFF30D158, 0xFFFFD60A, 0xFFFF9F0A, 0xFFFF3B30};
    private int level;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    LedBar(Context c) {
        super(c);
    }

    void setLevel(int l) {
        if (l != level) {
            level = l;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), gap = h * 0.25f;
        float cw = (w - gap * 4) / 5f;
        for (int i = 0; i < 5; i++) {
            p.setColor(i < level ? COLORS[i] : (COLORS[i] & 0xFFFFFF) | 0x30000000);
            float x = i * (cw + gap);
            c.drawRoundRect(new RectF(x, 0, x + cw, h), h * 0.2f, h * 0.2f, p);
        }
    }
}
