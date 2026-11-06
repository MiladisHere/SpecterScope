package app.specterscope;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * One microphone stream shared by the ambient level meter and the EVP recorder
 * (two separate recorders would fight over the microphone).
 */
final class SoundMeter {
    static final int RATE = 16000;

    private volatile boolean running;
    private volatile boolean recording;
    private volatile long bytes;
    volatile float level;      // 0..1
    volatile float db = -90f;

    private final Object lock = new Object();
    private OutputStream out;
    private File recFile;

    boolean isRunning() {
        return running;
    }

    boolean isRecording() {
        return recording;
    }

    long recordedMs() {
        return bytes * 1000L / (RATE * 2L);
    }

    boolean start() {
        if (running) return true;
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return false;
        final AudioRecord ar;
        try {
            ar = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
        } catch (Exception e) {
            return false;
        }
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
            ar.release();
            return false;
        }
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                short[] buf = new short[RATE / 10];
                try {
                    ar.startRecording();
                    while (running) {
                        int n = ar.read(buf, 0, buf.length);
                        if (n <= 0) continue;
                        double sum = 0;
                        for (int i = 0; i < n; i++) sum += buf[i] * (double) buf[i];
                        double rms = Math.sqrt(sum / n) / 32768.0;
                        double d = 20 * Math.log10(rms + 1e-6);
                        db = (float) d;
                        level = (float) Math.max(0, Math.min(1, (d + 70) / 50));
                        if (recording) write(buf, n);
                    }
                } catch (Exception ignored) {
                } finally {
                    try {
                        ar.stop();
                    } catch (Exception ignored) {
                    }
                    ar.release();
                    running = false;
                }
            }
        }, "sound-meter");
        t.setDaemon(true);
        t.start();
        return true;
    }

    void stop() {
        running = false;
    }

    boolean startRecording(File f) {
        synchronized (lock) {
            try {
                out = new BufferedOutputStream(new FileOutputStream(f));
                out.write(new byte[44]);   // header is patched when the recording stops
                bytes = 0;
                recFile = f;
                recording = true;
                return true;
            } catch (IOException e) {
                out = null;
                return false;
            }
        }
    }

    File stopRecording() {
        synchronized (lock) {
            recording = false;
            if (out == null) return null;
            try {
                out.flush();
                out.close();
            } catch (IOException ignored) {
            }
            out = null;
            patchHeader(recFile, bytes);
            return recFile;
        }
    }

    private void write(short[] b, int n) {
        synchronized (lock) {
            if (out == null || !recording) return;
            byte[] by = new byte[n * 2];
            for (int i = 0; i < n; i++) {
                by[2 * i] = (byte) (b[i] & 0xff);
                by[2 * i + 1] = (byte) ((b[i] >> 8) & 0xff);
            }
            try {
                out.write(by);
                bytes += by.length;
            } catch (IOException e) {
                recording = false;
            }
        }
    }

    private static void patchHeader(File f, long dataLen) {
        try (RandomAccessFile r = new RandomAccessFile(f, "rw")) {
            ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes());
            h.putInt((int) (36 + dataLen));
            h.put("WAVE".getBytes());
            h.put("fmt ".getBytes());
            h.putInt(16);
            h.putShort((short) 1);          // PCM
            h.putShort((short) 1);          // mono
            h.putInt(RATE);
            h.putInt(RATE * 2);
            h.putShort((short) 2);
            h.putShort((short) 16);
            h.put("data".getBytes());
            h.putInt((int) dataLen);
            r.seek(0);
            r.write(h.array());
        } catch (IOException ignored) {
        }
    }
}
