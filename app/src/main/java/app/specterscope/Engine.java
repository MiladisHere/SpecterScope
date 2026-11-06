package app.specterscope;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Sensor-driven entity simulation (pure Java, no Android dependencies).
 *
 * Real sensor readings (camera motion, magnetometer change, microphone level) raise an "activity"
 * level; the level, the sensitivity setting and a slow random drift decide when simulated figures
 * appear. This is an entertainment simulation: nothing here detects anything paranormal.
 */
public final class Engine {

    public static final class Entity {
        public int id;
        public double az;       // world bearing, degrees
        public double dist;     // metres (simulated)
        public double vAz;      // drift in degrees per second
        public double phase;    // animation phase, radians
        public double rate;     // phase speed, rad/s
        public double age, life;
        public double conf;     // simulated "confidence", 0..1
        public double alpha;    // fade envelope, 0..1
        public double facing = 1;
        public int style;
    }

    public interface Listener {
        /** type: ENTITY, LOST, EMF, MOTION, SOUND. spawn is true only for a newly appeared entity. */
        void onEvent(String type, String message, boolean spawn);
    }

    public final List<Entity> entities = new ArrayList<Entity>();
    public int sensitivity = 6;   // 1..10
    public int maxEntities = 3;   // 1..6

    private final Random rnd;
    private Listener listener;
    private double activity, drift, level;
    private double motion, emfDelta, sound;
    private double cdEmf, cdMot, cdSnd;
    private int nextId = 1;

    public Engine(long seed) {
        rnd = new Random(seed);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** motion: 0..1 (camera frame change), emfDelta: microtesla away from baseline, sound: 0..1. */
    public void setInputs(double motion, double emfDelta, double sound) {
        this.motion = motion;
        this.emfDelta = emfDelta;
        this.sound = sound;
    }

    public double gain() {
        return 0.4 + sensitivity * 0.2;
    }

    public double level() {
        return level;
    }

    /** 0..5 LED level for the EMF meter. */
    public int emfLevel(double delta) {
        double v = delta * gain();
        double[] th = {0.8, 1.8, 3.5, 7.0, 14.0};
        int n = 0;
        for (double t : th) if (v >= t) n++;
        return n;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void emit(String type, String msg, boolean spawn) {
        if (listener != null) listener.onEvent(type, msg, spawn);
    }

    public void step(double dt, double deviceAz) {
        double g = gain();
        double m = clamp(motion * g, 0, 1);
        double e = clamp(emfDelta * g / 12.0, 0, 1);
        double s = clamp(sound * g * 0.8, 0, 1);
        double raw = Math.max(m * 0.9, Math.max(e, s * 0.7));
        activity += (raw - activity) * Math.min(1.0, dt * 2.5);
        drift += (rnd.nextDouble() - 0.5) * dt * 1.2 - drift * dt * 0.4;
        level = clamp(activity + 0.2 * Math.max(0, drift), 0, 1);

        cdEmf -= dt;
        cdMot -= dt;
        cdSnd -= dt;
        if (emfLevel(emfDelta) >= 3 && cdEmf <= 0) {
            emit("EMF", String.format(Locale.US, "EMF spike: %.1f uT above baseline (level %d)", emfDelta, emfLevel(emfDelta)), false);
            cdEmf = 8;
        }
        if (m > 0.55 && cdMot <= 0) {
            emit("MOTION", String.format(Locale.US, "Motion in frame (%d%%)", (int) Math.round(m * 100)), false);
            cdMot = 10;
        }
        if (s > 0.6 && cdSnd <= 0) {
            emit("SOUND", String.format(Locale.US, "Sound burst (%d%%)", (int) Math.round(s * 100)), false);
            cdSnd = 10;
        }

        double rate = 0.004 + 0.004 * sensitivity + 0.30 * level * level * (0.5 + sensitivity / 10.0);
        if (entities.size() < maxEntities && rnd.nextDouble() < 1 - Math.exp(-rate * dt)) {
            spawn(deviceAz);
        }

        for (int i = entities.size() - 1; i >= 0; i--) {
            Entity en = entities.get(i);
            en.age += dt;
            en.az += en.vAz * dt;
            en.phase += en.rate * dt;
            en.conf = clamp(en.conf + (rnd.nextDouble() - 0.5) * dt * 0.6, 0.35, 0.97);
            en.alpha = Math.min(Math.min(1.0, en.age / 0.8), Math.max(0.0, (en.life - en.age) / 1.2));
            if (en.age >= en.life) {
                entities.remove(i);
                emit("LOST", String.format(Locale.US, "Entity #%02d lost", en.id), false);
            }
        }
    }

    private void spawn(double deviceAz) {
        Entity en = new Entity();
        en.id = nextId++;
        double r = rnd.nextDouble();
        en.style = r < 0.35 ? Skeleton.STAND : r < 0.65 ? Skeleton.WALK : r < 0.75 ? Skeleton.RAISED
                : r < 0.85 ? Skeleton.CROUCH : Skeleton.REACH;
        en.az = deviceAz + (rnd.nextDouble() - 0.5) * 40.0;
        en.dist = 1.5 + Math.pow(rnd.nextDouble(), 1.3) * 6.5;
        double dir = rnd.nextBoolean() ? 1 : -1;
        en.vAz = en.style == Skeleton.WALK ? dir * (3 + rnd.nextDouble() * 7) : dir * rnd.nextDouble() * 1.2;
        en.facing = dir;
        switch (en.style) {
            case Skeleton.WALK: en.rate = 5.0; break;
            case Skeleton.RAISED: en.rate = 2.0; break;
            case Skeleton.CROUCH: en.rate = 1.5; break;
            case Skeleton.REACH: en.rate = 2.5; break;
            default: en.rate = 1.2;
        }
        en.phase = rnd.nextDouble() * Math.PI * 2;
        en.life = 6 + rnd.nextDouble() * 18;
        en.conf = 0.5 + rnd.nextDouble() * 0.3;
        entities.add(en);
        emit("ENTITY", String.format(Locale.US, "Entity #%02d acquired: %.1f m, bearing %03d deg",
                en.id, en.dist, ((int) Math.round(((en.az % 360) + 360) % 360)) % 360), true);
    }
}
