import app.specterscope.Engine;
import app.specterscope.Skeleton;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;
import javax.imageio.ImageIO;

/** Desktop test for the pure-Java core. Run: see tools/README in the project root. */
public class CoreTest {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) fails++;
    }

    public static void main(String[] a) throws Exception {
        // 1. bone lengths are constant for every style over a full cycle
        float[] ref = new float[Skeleton.N * 2], p = new float[Skeleton.N * 2];
        double worst = 0;
        boolean finite = true;
        for (int style = 0; style < Skeleton.STYLES; style++) {
            Skeleton.pose(style, 0, ref);
            double[] len0 = new double[Skeleton.BONES.length];
            for (int b = 0; b < len0.length; b++) len0[b] = bone(ref, b);
            for (double t = 0; t < 20; t += 0.05) {
                Skeleton.pose(style, t, p);
                for (float v : p) if (Float.isNaN(v) || Float.isInfinite(v)) finite = false;
                for (int b = 0; b < len0.length; b++) worst = Math.max(worst, Math.abs(bone(p, b) - len0[b]));
            }
        }
        check(finite, "all poses finite");
        check(worst < 1e-4, "bone lengths constant (worst drift " + worst + ")");

        // 2. standing pose spans TOP..FOOT
        Skeleton.pose(Skeleton.STAND, 0, p);
        check(Math.abs(Skeleton.lowest(p) - Skeleton.FOOT) < 0.02, "standing feet at FOOT (" + Skeleton.lowest(p) + ")");

        // 3. engine behaviour at different sensitivities (quiet inputs, then active inputs)
        for (int sens : new int[]{1, 6, 10}) {
            for (int mode = 0; mode < 2; mode++) {
                Engine e = new Engine(42 + sens);
                e.sensitivity = sens;
                e.maxEntities = 3;
                final int[] ev = new int[3];
                e.setListener((type, msg, spawn) -> {
                    if (spawn) ev[0]++;
                    if (type.equals("LOST")) ev[1]++;
                    if (type.equals("EMF") || type.equals("MOTION") || type.equals("SOUND")) ev[2]++;
                });
                Random r = new Random(7);
                int maxSeen = 0;
                boolean bad = false;
                int ticks = 18000;               // 10 minutes at 30 Hz
                for (int i = 0; i < ticks; i++) {
                    if (mode == 0) e.setInputs(0.01, 0.2, 0.02);
                    else e.setInputs(r.nextDouble() * 0.5, r.nextDouble() * 6, r.nextDouble() * 0.4);
                    e.step(1 / 30.0, 90);
                    maxSeen = Math.max(maxSeen, e.entities.size());
                    for (Engine.Entity en : e.entities)
                        if (Double.isNaN(en.az) || en.alpha < 0 || en.alpha > 1.0001 || en.conf < 0.3 || en.conf > 1) bad = true;
                }
                System.out.printf("      sens %2d %-6s : %2d entities in 10 min, %2d lost, %2d sensor events, max at once %d%n",
                        sens, mode == 0 ? "quiet" : "active", ev[0], ev[1], ev[2], maxSeen);
                check(!bad && maxSeen <= 3, "sens " + sens + (mode == 0 ? " quiet" : " active") + ": values valid, cap respected");
            }
        }
        Engine q = new Engine(1);
        q.sensitivity = 6;
        check(q.emfLevel(0.1) == 0 && q.emfLevel(100) == 5, "EMF level spans 0..5");

        // 4. contact sheet of every pose
        int cw = 150, ch = 210, cols = 6;
        BufferedImage img = new BufferedImage(cw * cols, ch * Skeleton.STYLES, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(8, 12, 8));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        String[] names = {"STAND", "WALK", "RAISED", "CROUCH", "REACH"};
        for (int style = 0; style < Skeleton.STYLES; style++) {
            for (int c = 0; c < cols; c++) {
                double t = c * (Math.PI * 2 / cols);
                Skeleton.pose(style, t, p);
                float scale = 170f / (Skeleton.FOOT - Skeleton.TOP);
                float low = Skeleton.lowest(p);
                float ox = c * cw + cw / 2f, ground = style * ch + ch - 18;
                g.setColor(new Color(57, 255, 20, 70));
                g.setStroke(new BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                draw(g, p, ox, ground, low, scale);
                g.setColor(new Color(57, 255, 20));
                g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                draw(g, p, ox, ground, low, scale);
                float hx = ox + p[2 * Skeleton.HEAD] * scale, hy = ground - (low - p[2 * Skeleton.HEAD + 1]) * scale;
                float hr = Skeleton.HEAD_RADIUS * scale;
                g.draw(new java.awt.geom.Ellipse2D.Float(hx - hr, hy - hr, hr * 2, hr * 2));
                for (int j = 0; j < Skeleton.N; j++) {
                    float jx = ox + p[2 * j] * scale, jy = ground - (low - p[2 * j + 1]) * scale;
                    g.fill(new java.awt.geom.Ellipse2D.Float(jx - 2.5f, jy - 2.5f, 5, 5));
                }
            }
            g.setColor(Color.GRAY);
            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            g.drawString(names[style], 4, style * ch + 14);
        }
        g.dispose();
        File out = new File(a.length > 0 ? a[0] : "poses.png");
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out);
        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static double bone(float[] p, int b) {
        int i = Skeleton.BONES[b][0], j = Skeleton.BONES[b][1];
        return Math.hypot(p[2 * i] - p[2 * j], p[2 * i + 1] - p[2 * j + 1]);
    }

    static void draw(Graphics2D g, float[] p, float ox, float ground, float low, float scale) {
        for (int[] b : Skeleton.BONES) {
            g.draw(new java.awt.geom.Line2D.Float(
                    ox + p[2 * b[0]] * scale, ground - (low - p[2 * b[0] + 1]) * scale,
                    ox + p[2 * b[1]] * scale, ground - (low - p[2 * b[1] + 1]) * scale));
        }
    }
}
