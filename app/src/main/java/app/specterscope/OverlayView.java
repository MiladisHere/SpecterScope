package app.specterscope;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Everything drawn on top of the camera image: the structured-light dot field, night-vision grain,
 * the false-colour "thermal" picture, the tracked stick figures with their labels, and the
 * viewfinder frame. This view is also drawn into captured photos and recorded video.
 */
final class OverlayView extends View {
    static final int SLS = 0, NIGHT = 1, THERMAL = 2, PLAIN = 3;
    static final float HFOV = 62f;       // assumed horizontal field of view, degrees
    private static final int MAXF = 6;

    /** Iron-bow style palette used for the false-colour thermal view. */
    static final int[] PALETTE = new int[256];

    static {
        float[][] stops = {{0f, 0, 0, 0}, {0.25f, 40, 0, 90}, {0.5f, 200, 30, 40}, {0.75f, 255, 160, 0},
                {0.9f, 255, 235, 80}, {1f, 255, 255, 255}};
        for (int i = 0; i < 256; i++) {
            float t = i / 255f;
            int k = 0;
            while (k < stops.length - 2 && t > stops[k + 1][0]) k++;
            float u = (t - stops[k][0]) / (stops[k + 1][0] - stops[k][0]);
            u = Math.max(0, Math.min(1, u));
            int r = (int) (stops[k][1] + (stops[k + 1][1] - stops[k][1]) * u);
            int g = (int) (stops[k][2] + (stops[k + 1][2] - stops[k][2]) * u);
            int b = (int) (stops[k][3] + (stops[k + 1][3] - stops[k][3]) * u);
            PALETTE[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    int mode = SLS;
    int color = 0xFF39FF14;
    boolean labels = true, feet = false, rec = false;
    List<Engine.Entity> ents;
    double deviceAz;
    Bitmap thermal;
    Matrix thermalMatrix;
    String caption = "", recText = "";

    private final Paint dimDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint brightDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint vign = new Paint();
    private final Random rnd = new Random();
    private final float[] pose = new float[Skeleton.N * 2];
    private final float[][] fj = new float[MAXF][Skeleton.N * 2];
    private final float[] fbox = new float[MAXF * 4];
    private final float[] fhf = new float[MAXF];
    private final Engine.Entity[] fe = new Engine.Entity[MAXF];
    private int nFig;
    private float[] dimPts = new float[0], brightPts = new float[0];
    private final float d;

    OverlayView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        dimDot.setStrokeCap(Paint.Cap.ROUND);
        brightDot.setStrokeCap(Paint.Cap.ROUND);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        txt.setTypeface(Typeface.MONOSPACE);
        txt.setTextSize(10 * d);
        txt.setShadowLayer(2 * d, 0, 0, 0xFF000000);
        setWillNotDraw(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        vign.setShader(new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.75f,
                new int[]{0x00000000, 0x00000000, 0xCC000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    static double wrap(double a) {
        a %= 360;
        if (a > 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }

    private static int alpha(int c, int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (c & 0xFFFFFF);
    }

    private static int hash(int a, int b, int c) {
        int h = a * 73856093 ^ b * 19349663 ^ c * 83492791;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return h;
    }

    @Override
    protected void onDraw(Canvas c) {
        int W = getWidth(), H = getHeight();
        if (W == 0 || H == 0) return;
        long now = SystemClock.uptimeMillis();
        layoutFigures(W, H);
        switch (mode) {
            case THERMAL:
                drawThermal(c, W, H);
                break;
            case NIGHT:
                drawNight(c, W, H);
                break;
            case SLS:
                drawDots(c, W, H, now);
                break;
            default:
                break;
        }
        for (int i = 0; i < nFig; i++) drawFigure(c, i, now);
        drawHud(c, W, H, now);
    }

    private void layoutFigures(int W, int H) {
        nFig = 0;
        if (ents == null || mode == PLAIN) return;
        for (Engine.Entity e : ents) {
            if (nFig >= MAXF) break;
            double dAz = wrap(e.az - deviceAz);
            float x = (float) (W * (0.5 + dAz / HFOV));
            if (x < -W * 0.25f || x > W * 1.25f) continue;
            float hf = Math.max(0.12f, Math.min(0.62f, 0.95f / ((float) e.dist + 0.7f)));
            float ground = H * (0.52f + 0.55f * hf);
            Skeleton.pose(e.style, e.phase, pose);
            float low = Skeleton.lowest(pose);
            float sc = hf * H / (Skeleton.FOOT - Skeleton.TOP);
            float[] j = fj[nFig];
            float minx = 1e9f, miny = 1e9f, maxx = -1e9f, maxy = -1e9f;
            for (int k = 0; k < Skeleton.N; k++) {
                float jit = 0.006f * sc;
                float px = x + (float) e.facing * pose[2 * k] * sc + (rnd.nextFloat() - 0.5f) * jit;
                float py = ground - (low - pose[2 * k + 1]) * sc + (rnd.nextFloat() - 0.5f) * jit;
                j[2 * k] = px;
                j[2 * k + 1] = py;
                minx = Math.min(minx, px);
                maxx = Math.max(maxx, px);
                miny = Math.min(miny, py);
                maxy = Math.max(maxy, py);
            }
            float hr = Skeleton.HEAD_RADIUS * sc;
            miny = Math.min(miny, j[2 * Skeleton.HEAD + 1] - hr);
            fbox[nFig * 4] = minx;
            fbox[nFig * 4 + 1] = miny;
            fbox[nFig * 4 + 2] = maxx;
            fbox[nFig * 4 + 3] = maxy;
            fhf[nFig] = hf;
            fe[nFig] = e;
            nFig++;
        }
    }

    private void drawDots(Canvas c, int W, int H, long now) {
        float sp = 14 * d;
        int cols = (int) (W / sp) + 1, rows = (int) (H / sp) + 1;
        int cap = cols * rows * 2;
        if (dimPts.length < cap) {
            dimPts = new float[cap];
            brightPts = new float[cap];
        }
        int nd = 0, nb = 0;
        int frame = (int) (now / 90);
        float pad = 12 * d;
        for (int r = 0; r < rows; r++) {
            for (int q = 0; q < cols; q++) {
                int h = hash(r, q, frame);
                if ((h & 0xFFFF) / 65535f > 0.6f) continue;
                float x = q * sp + (((h >> 16) & 0xFF) / 255f - 0.5f) * sp * 0.35f;
                float y = r * sp + (((h >> 24) & 0xFF) / 255f - 0.5f) * sp * 0.35f;
                boolean bright = false;
                for (int i = 0; i < nFig && !bright; i++) {
                    bright = x > fbox[i * 4] - pad && x < fbox[i * 4 + 2] + pad
                            && y > fbox[i * 4 + 1] - pad && y < fbox[i * 4 + 3] + pad;
                }
                if (bright) {
                    brightPts[nb++] = x;
                    brightPts[nb++] = y;
                } else {
                    dimPts[nd++] = x;
                    dimPts[nd++] = y;
                }
            }
        }
        dimDot.setColor(alpha(color, 85));
        dimDot.setStrokeWidth(2f * d);
        brightDot.setColor(alpha(color, 235));
        brightDot.setStrokeWidth(2.8f * d);
        c.drawPoints(dimPts, 0, nd, dimDot);
        c.drawPoints(brightPts, 0, nb, brightDot);
        float y = (now / 12) % (H + 200) - 100;
        fill.setColor(alpha(color, 22));
        c.drawRect(0, y, W, y + 36 * d, fill);
    }

    private void drawNight(Canvas c, int W, int H) {
        c.drawRect(0, 0, W, H, vign);
        fill.setColor(alpha(color, 70));
        for (int i = 0; i < 160; i++) {
            c.drawCircle(rnd.nextFloat() * W, rnd.nextFloat() * H, (0.6f + rnd.nextFloat()) * d, fill);
        }
    }

    private void drawThermal(Canvas c, int W, int H) {
        c.drawColor(0xFF000000);
        if (thermal != null) {
            c.save();
            if (thermalMatrix != null) c.concat(thermalMatrix);
            c.drawBitmap(thermal, null, new RectF(0, 0, W, H), bmp);
            c.restore();
        }
        c.drawRect(0, 0, W, H, vign);
        float bw = 10 * d, bh = H * 0.28f, bx = W - 22 * d, by = H * 0.36f;
        for (int i = 0; i < 40; i++) {
            fill.setColor(PALETTE[255 - i * 255 / 39]);
            c.drawRect(bx, by + bh * i / 40f, bx + bw, by + bh * (i + 1) / 40f, fill);
        }
        txt.setColor(0xFFFFFFFF);
        c.drawText("HOT", bx - 6 * d, by - 4 * d, txt);
        c.drawText("COLD", bx - 8 * d, by + bh + 12 * d, txt);
    }

    private void drawFigure(Canvas c, int i, long now) {
        Engine.Entity e = fe[i];
        float[] j = fj[i];
        float hf = fhf[i];
        boolean flick = ((now / 60 + e.id * 7) % 29) == 0;
        int a = (int) (255 * e.alpha * (flick ? 0.35 : 1.0));
        int base = mode == THERMAL ? 0xFFFFF2B0 : color;
        float lw = Math.max(1.5f * d, hf * 10 * d);
        line.setColor(alpha(base, a));
        line.setStrokeWidth(lw);
        glow.setColor(alpha(base, a / 4));
        glow.setStrokeWidth(lw * 3.2f);
        for (int[] b : Skeleton.BONES) {
            c.drawLine(j[2 * b[0]], j[2 * b[0] + 1], j[2 * b[1]], j[2 * b[1] + 1], glow);
        }
        for (int[] b : Skeleton.BONES) {
            c.drawLine(j[2 * b[0]], j[2 * b[0] + 1], j[2 * b[1]], j[2 * b[1] + 1], line);
        }
        float sc = hf * getHeight() / (Skeleton.FOOT - Skeleton.TOP);
        float hr = Skeleton.HEAD_RADIUS * sc;
        c.drawCircle(j[2 * Skeleton.HEAD], j[2 * Skeleton.HEAD + 1], hr, line);
        fill.setColor(alpha(base, a));
        for (int k = 0; k < Skeleton.N; k++) {
            if (k == Skeleton.HEAD) continue;
            c.drawCircle(j[2 * k], j[2 * k + 1], lw * 0.9f, fill);
        }
        float l = fbox[i * 4] - 10 * d, t = fbox[i * 4 + 1] - 10 * d, r = fbox[i * 4 + 2] + 10 * d, b = fbox[i * 4 + 3] + 10 * d;
        line.setStrokeWidth(1.2f * d);
        line.setColor(alpha(base, a * 2 / 3));
        corners(c, l, t, r, b, Math.min(14 * d, (r - l) / 3), line);
        if (labels) {
            txt.setColor(alpha(base, a));
            c.drawText(String.format(Locale.US, "ENTITY %02d", e.id), l, t - 6 * d, txt);
            double dist = feet ? e.dist * 3.281 : e.dist;
            c.drawText(String.format(Locale.US, "%.1f %s  %d%%", dist, feet ? "ft" : "m", (int) Math.round(e.conf * 100)),
                    l, b + 14 * d, txt);
        }
    }

    private static void corners(Canvas c, float l, float t, float r, float b, float n, Paint p) {
        c.drawLine(l, t, l + n, t, p);
        c.drawLine(l, t, l, t + n, p);
        c.drawLine(r, t, r - n, t, p);
        c.drawLine(r, t, r, t + n, p);
        c.drawLine(l, b, l + n, b, p);
        c.drawLine(l, b, l, b - n, p);
        c.drawLine(r, b, r - n, b, p);
        c.drawLine(r, b, r, b - n, p);
    }

    private void drawHud(Canvas c, int W, int H, long now) {
        line.setColor(alpha(color, 200));
        line.setStrokeWidth(2f * d);
        corners(c, 14 * d, 14 * d, W - 14 * d, H - 14 * d, 26 * d, line);
        line.setStrokeWidth(1f * d);
        line.setColor(alpha(color, 120));
        c.drawLine(W / 2f - 10 * d, H / 2f, W / 2f + 10 * d, H / 2f, line);
        c.drawLine(W / 2f, H / 2f - 10 * d, W / 2f, H / 2f + 10 * d, line);
        txt.setColor(alpha(color, 230));
        c.drawText(caption, 20 * d, H - 112 * d, txt);
        if (rec && (now / 500) % 2 == 0) {
            fill.setColor(0xFFFF2020);
            c.drawCircle(W / 2f - 36 * d, 30 * d, 5 * d, fill);
        }
        if (rec) {
            txt.setColor(0xFFFF5050);
            c.drawText("REC " + recText, W / 2f - 26 * d, 34 * d, txt);
        }
    }
}
