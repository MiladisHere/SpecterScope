package app.specterscope;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import java.util.List;

/** Small round radar: up is the direction the phone points; blips are the simulated entities. */
final class RadarView extends View {
    int color = 0xFF39FF14;
    List<Engine.Entity> ents;
    double deviceAz;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;

    RadarView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
    }

    private int a(int col, int al) {
        return (Math.max(0, Math.min(255, al)) << 24) | (col & 0xFFFFFF);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float r = Math.min(w, h) / 2f - 2 * d, cx = w / 2f, cy = h / 2f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x99000000);
        c.drawCircle(cx, cy, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f * d);
        p.setColor(a(color, 140));
        c.drawCircle(cx, cy, r, p);
        c.drawCircle(cx, cy, r * 0.66f, p);
        c.drawCircle(cx, cy, r * 0.33f, p);
        c.drawLine(cx - r, cy, cx + r, cy, p);
        c.drawLine(cx, cy - r, cx, cy + r, p);

        // field of view wedge
        p.setStyle(Paint.Style.FILL);
        p.setColor(a(color, 38));
        c.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), -90 - OverlayView.HFOV / 2, OverlayView.HFOV, true, p);

        // sweep
        float sw = (SystemClock.uptimeMillis() / 8) % 360;
        for (int i = 0; i < 12; i++) {
            p.setColor(a(color, 70 - i * 6));
            c.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), sw - 90 - i * 4, 4, true, p);
        }

        if (ents != null) {
            for (Engine.Entity e : ents) {
                double rel = Math.toRadians(OverlayView.wrap(e.az - deviceAz));
                float rad = (float) Math.max(0.12, Math.min(1.0, e.dist / 9.0)) * r;
                float x = cx + rad * (float) Math.sin(rel), y = cy - rad * (float) Math.cos(rel);
                p.setColor(a(0xFFFF5050, (int) (255 * e.alpha)));
                c.drawCircle(x, y, 3.5f * d, p);
            }
        }
        p.setColor(a(color, 255));
        c.drawCircle(cx, cy, 2 * d, p);
    }
}
