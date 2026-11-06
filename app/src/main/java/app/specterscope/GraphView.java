package app.specterscope;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Rolling line graph used by the EMF and EVP panels. */
final class GraphView extends View {
    private final float[] v = new float[150];
    private float lo = 0f, hi = 1f;
    private final int color;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float d;

    GraphView(Context c, int color) {
        super(c);
        this.color = color;
        d = c.getResources().getDisplayMetrics().density;
    }

    void setRange(float lo, float hi) {
        this.lo = lo;
        this.hi = hi;
    }

    void push(float x) {
        System.arraycopy(v, 1, v, 0, v.length - 1);
        v[v.length - 1] = x;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x55000000);
        c.drawRect(0, 0, w, h, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f * d);
        p.setColor((color & 0xFFFFFF) | 0x40000000);
        for (int i = 1; i < 4; i++) c.drawLine(0, h * i / 4f, w, h * i / 4f, p);
        path.reset();
        for (int i = 0; i < v.length; i++) {
            float x = w * i / (v.length - 1f);
            float t = (v[i] - lo) / Math.max(1e-6f, hi - lo);
            float y = h - Math.max(0f, Math.min(1f, t)) * (h - 4 * d) - 2 * d;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        p.setColor(color);
        p.setStrokeWidth(2f * d);
        c.drawPath(path, p);
    }
}
