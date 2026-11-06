package app.specterscope;

/**
 * Pure-Java stick-figure rig used for the SLS-style "tracked figure" overlay.
 * Coordinates are in body space: x to the right, y DOWN, hip centre at (0,0),
 * a straight standing body spans y = TOP (head top) .. FOOT (feet).
 * Every limb is built by forward kinematics, so bone lengths never change.
 */
public final class Skeleton {
    public static final int HEAD = 0, NECK = 1, SHL = 2, SHR = 3, ELL = 4, ELR = 5, HNL = 6, HNR = 7,
            HIP = 8, HPL = 9, HPR = 10, KNL = 11, KNR = 12, FTL = 13, FTR = 14;
    public static final int N = 15;

    public static final int[][] BONES = {
            {HEAD, NECK}, {NECK, SHL}, {NECK, SHR}, {SHL, ELL}, {ELL, HNL}, {SHR, ELR}, {ELR, HNR},
            {NECK, HIP}, {HIP, HPL}, {HIP, HPR}, {HPL, KNL}, {KNL, FTL}, {HPR, KNR}, {KNR, FTR}};

    public static final int STAND = 0, WALK = 1, RAISED = 2, CROUCH = 3, REACH = 4, STYLES = 5;

    public static final float HEAD_RADIUS = 0.055f;
    public static final float TOP = -0.435f;   // top of head
    public static final float FOOT = 0.5f;     // foot level when standing straight

    static final float UARM = 0.17f, FARM = 0.16f, THIGH = 0.25f, SHIN = 0.25f;

    private Skeleton() { }

    private static void set(float[] o, int j, float x, float y) {
        o[2 * j] = x;
        o[2 * j + 1] = y;
    }

    /** Place joint b at distance len from joint a; angle is measured from straight down, + toward +x. */
    private static void limb(float[] o, int a, int b, float ang, float len) {
        o[2 * b] = o[2 * a] + (float) Math.sin(ang) * len;
        o[2 * b + 1] = o[2 * a + 1] + (float) Math.cos(ang) * len;
    }

    /** Fill out[2*N] with the pose for the given style at animation phase t (radians). */
    public static void pose(int style, double t, float[] out) {
        float s = (float) Math.sin(t), c = (float) Math.cos(t);
        float lean = 0f;
        float hipW = 0.07f, shW = 0.12f;      // half widths; narrowed for the side-on walking view
        float aSL, aSR, bEL, bER, tL, tR, kL, kR;
        switch (style) {
            case WALK: {
                hipW = 0.02f;
                shW = 0.04f;
                lean = 0.06f;
                tL = 0.5f * s;
                tR = -0.5f * s;
                kL = -0.7f * Math.max(0f, c);
                kR = -0.7f * Math.max(0f, -c);
                aSL = -0.45f * s;
                aSR = 0.45f * s;
                bEL = 0.5f;
                bER = 0.5f;
                break;
            }
            case RAISED: {
                aSL = -(float) (Math.PI - 0.55 + 0.12 * Math.sin(1.5 * t));
                aSR = (float) (Math.PI - 0.55 + 0.12 * Math.sin(1.7 * t));
                bEL = 0.2f;
                bER = -0.2f;
                tL = -0.08f;
                tR = 0.08f;
                kL = 0f;
                kR = 0f;
                lean = 0.03f * s;
                break;
            }
            case CROUCH: {
                lean = 0.12f;
                tL = -0.95f;
                tR = 0.95f;
                kL = 1.0f;
                kR = -1.0f;
                aSL = -0.5f + 0.1f * s;
                aSR = 0.5f - 0.1f * s;
                bEL = 0.6f;
                bER = -0.6f;
                break;
            }
            case REACH: {
                lean = 0.18f + 0.03f * (float) Math.sin(2 * t);
                aSR = 1.75f + 0.15f * (float) Math.sin(2 * t);
                bER = -0.1f;
                aSL = -0.15f;
                bEL = 0.2f;
                tL = -0.2f;
                tR = 0.25f;
                kL = 0.2f;
                kR = -0.1f;
                break;
            }
            default: { // STAND
                lean = 0.04f * (float) Math.sin(0.5 * t);
                aSL = -0.10f - 0.05f * s;
                aSR = 0.10f + 0.05f * s;
                bEL = 0.15f;
                bER = -0.15f;
                tL = -0.05f;
                tR = 0.05f;
                kL = 0f;
                kR = 0f;
            }
        }

        set(out, HIP, 0f, 0f);
        set(out, HPL, -hipW, 0f);
        set(out, HPR, hipW, 0f);
        set(out, NECK, 0f, -0.30f);
        set(out, SHL, -shW, -0.28f);
        set(out, SHR, shW, -0.28f);
        set(out, HEAD, 0f, -0.38f);
        limb(out, SHL, ELL, aSL, UARM);
        limb(out, ELL, HNL, aSL + bEL, FARM);
        limb(out, SHR, ELR, aSR, UARM);
        limb(out, ELR, HNR, aSR + bER, FARM);
        limb(out, HPL, KNL, tL, THIGH);
        limb(out, KNL, FTL, tL + kL, SHIN);
        limb(out, HPR, KNR, tR, THIGH);
        limb(out, KNR, FTR, tR + kR, SHIN);

        // lean the whole upper body about the hip (rotation keeps every bone length)
        if (lean != 0f) {
            float cl = (float) Math.cos(lean), sl = (float) Math.sin(lean);
            int[] upper = {HEAD, NECK, SHL, SHR, ELL, ELR, HNL, HNR};
            for (int j : upper) {
                float x = out[2 * j], y = out[2 * j + 1];
                out[2 * j] = x * cl - y * sl;
                out[2 * j + 1] = x * sl + y * cl;
            }
        }
    }

    /** Lowest joint y of a posed body (used to plant the feet on the ground line). */
    public static float lowest(float[] p) {
        float m = p[1];
        for (int j = 0; j < N; j++) m = Math.max(m, p[2 * j + 1]);
        return m;
    }
}
