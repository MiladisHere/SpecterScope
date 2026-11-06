package app.specterscope;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Records video WITH the overlay burned in: every ~66 ms the composer draws the camera frame plus the
 * overlay into a bitmap, which is pushed through an H.264 encoder's input surface into an MP4.
 * The video has no audio track.
 */
final class OverlayRecorder {
    interface Composer {
        /** Called on the UI thread; draw the full composited frame into out. */
        void compose(Bitmap out);
    }

    private final File file;
    private final int w, h;
    private final Composer composer;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Bitmap frame;
    private MediaCodec enc;
    private MediaMuxer muxer;
    private Surface surface;
    private Thread drain;
    private volatile boolean running;
    private int track = -1;
    private boolean muxStarted;

    OverlayRecorder(File file, int w, int h, Composer composer) {
        this.file = file;
        this.w = w;
        this.h = h;
        this.composer = composer;
        this.frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    }

    File file() {
        return file;
    }

    private final Runnable pump = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                composer.compose(frame);
                Canvas c = surface.lockCanvas(null);
                try {
                    c.drawBitmap(frame, 0, 0, null);
                } finally {
                    surface.unlockCanvasAndPost(c);
                }
            } catch (Exception ignored) {
            }
            ui.postDelayed(this, 66);
        }
    };

    boolean start() {
        try {
            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = enc.createInputSurface();
            enc.start();
            muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            running = true;
            drain = new Thread(new Runnable() {
                @Override
                public void run() {
                    drainLoop();
                }
            }, "overlay-recorder");
            drain.start();
            ui.post(pump);
            return true;
        } catch (Exception e) {
            cleanup();
            return false;
        }
    }

    private void drainLoop() {
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        boolean done = false;
        try {
            while (!done) {
                int idx = enc.dequeueOutputBuffer(bi, 10000);
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(enc.getOutputFormat());
                    muxer.start();
                    muxStarted = true;
                } else if (idx >= 0) {
                    ByteBuffer buf = enc.getOutputBuffer(idx);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bi.size = 0;
                    if (bi.size > 0 && muxStarted && buf != null) {
                        buf.position(bi.offset);
                        buf.limit(bi.offset + bi.size);
                        muxer.writeSampleData(track, buf, bi);
                    }
                    enc.releaseOutputBuffer(idx, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true;
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Finish the file. Must be called on the UI thread. */
    void stop() {
        if (!running) return;
        running = false;
        ui.removeCallbacks(pump);
        try {
            enc.signalEndOfInputStream();
        } catch (Exception ignored) {
        }
        try {
            if (drain != null) drain.join(3000);
        } catch (InterruptedException ignored) {
        }
        cleanup();
    }

    private void cleanup() {
        try {
            if (enc != null) {
                enc.stop();
                enc.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (muxer != null) {
                if (muxStarted) muxer.stop();
                muxer.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (surface != null) surface.release();
        } catch (Exception ignored) {
        }
        enc = null;
        muxer = null;
        surface = null;
        frame.recycle();
    }
}
