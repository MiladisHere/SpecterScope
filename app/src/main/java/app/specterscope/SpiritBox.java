package app.specterscope;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import java.util.Random;

/**
 * "Spirit box": a synthesized radio sweep. The audio is generated noise, crackle and vowel-like
 * garble (a sawtooth run through two formant resonators). The words it shows are picked at random
 * from a list. Entertainment only.
 */
final class SpiritBox {
    interface Listener {
        void onFreq(float mhz);

        void onWord(String word);
    }

    static final String[] WORDS = {
            "here", "behind", "listen", "cold", "help", "name", "stay", "leave", "light", "dark",
            "door", "upstairs", "basement", "window", "mirror", "quiet", "closer", "who", "why", "alone",
            "child", "mother", "father", "garden", "fire", "water", "night", "hello", "wait", "watching",
            "hidden", "under", "open", "close", "remember", "forgotten", "home", "voice", "shadow", "cellar",
            "yes", "no", "run", "hide", "follow", "near", "far", "sleep", "wake", "bell"};

    private static final double[][] VOWELS = {{800, 1200}, {500, 1800}, {300, 2200}, {500, 900}, {350, 700}};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private volatile boolean running;
    volatile float gain = 0.6f;
    volatile int dwellMs = 250;
    volatile boolean wordsOn = true;

    boolean isRunning() {
        return running;
    }

    void start(final Listener l) {
        if (running) return;
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(l);
            }
        }, "spirit-box");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
    }

    private void loop(final Listener l) {
        final int sr = 22050;
        int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack at = null;
        try {
            at = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(min, sr / 5))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            at.play();
            short[] buf = new short[sr / 50];     // 20 ms
            float freq = 87.5f;
            int dir = 1, left = 0, type = 0, vowelLeft = 0;
            double ph = 0, f0 = 140, lp = 0, prev = 0, sylPh = 0;
            double a1 = 0, b11 = 0, b21 = 0, y11 = 0, y12 = 0;
            double a2 = 0, b12 = 0, b22 = 0, y21 = 0, y22 = 0;
            double syl = 5;
            while (running) {
                if (left <= 0) {
                    freq += dir * 0.2f;
                    if (freq >= 108f) {
                        freq = 108f;
                        dir = -1;
                    }
                    if (freq <= 87.5f) {
                        freq = 87.5f;
                        dir = 1;
                    }
                    final float ff = freq;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            l.onFreq(ff);
                        }
                    });
                    left = (int) (dwellMs * sr / 1000.0 * (0.6 + rnd.nextDouble() * 0.8));
                    double r = rnd.nextDouble();
                    type = r < 0.55 ? 0 : (r < 0.85 ? 1 : 2);
                    f0 = 95 + rnd.nextDouble() * 120;
                    syl = 3.5 + rnd.nextDouble() * 3.5;
                    vowelLeft = 0;
                    if (type == 1 && wordsOn && rnd.nextDouble() < 0.18) {
                        final String w = WORDS[rnd.nextInt(WORDS.length)];
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                l.onWord(w);
                            }
                        });
                    }
                }
                for (int i = 0; i < buf.length; i++) {
                    double noise = rnd.nextDouble() * 2 - 1;
                    double v;
                    if (type == 0) {                               // static with crackle
                        lp += (noise - lp) * 0.45;
                        v = lp * 0.55 + (rnd.nextInt(900) == 0 ? noise * 1.2 : 0);
                    } else if (type == 1) {                        // garbled "voice"
                        if (vowelLeft <= 0) {
                            double[] vw = VOWELS[rnd.nextInt(VOWELS.length)];
                            double r1 = Math.exp(-Math.PI * 90 / sr), r2 = Math.exp(-Math.PI * 130 / sr);
                            b11 = 2 * r1 * Math.cos(2 * Math.PI * vw[0] / sr);
                            b21 = -r1 * r1;
                            a1 = 1 - r1;
                            b12 = 2 * r2 * Math.cos(2 * Math.PI * vw[1] / sr);
                            b22 = -r2 * r2;
                            a2 = 1 - r2;
                            vowelLeft = (int) (sr * (0.08 + rnd.nextDouble() * 0.12));
                        }
                        vowelLeft--;
                        ph += f0 / sr;
                        if (ph >= 1) ph -= 1;
                        double saw = 2 * ph - 1;
                        double o1 = a1 * saw + b11 * y11 + b21 * y12;
                        y12 = y11;
                        y11 = o1;
                        double o2 = a2 * saw + b12 * y21 + b22 * y22;
                        y22 = y21;
                        y21 = o2;
                        sylPh += syl / sr;
                        if (sylPh >= 1) sylPh -= 1;
                        double amp = 0.5 + 0.5 * Math.sin(2 * Math.PI * sylPh);
                        v = (o1 * 5.0 + o2 * 4.0) * amp * 0.5 + noise * 0.22;
                    } else {                                       // hiss
                        v = (noise - prev) * 0.35;
                    }
                    prev = noise;
                    double s = Math.max(-1, Math.min(1, v * gain)) * 26000;
                    buf[i] = (short) s;
                }
                left -= buf.length;
                at.write(buf, 0, buf.length);
            }
        } catch (Exception ignored) {
        } finally {
            if (at != null) {
                try {
                    at.stop();
                } catch (Exception ignored) {
                }
                at.release();
            }
            running = false;
        }
    }
}
