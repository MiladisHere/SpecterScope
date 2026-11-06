package app.specterscope;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import java.util.Collections;

/** Camera2 live preview into a TextureView, with torch and front/back selection. */
final class CameraController {
    interface Callback {
        void onPreviewSize(int w, int h);

        void onReady(boolean torchAvailable);

        void onError(String msg);
    }

    private final Context ctx;
    private final TextureView tv;
    private final Callback cb;
    private final Handler main = new Handler(Looper.getMainLooper());
    private CameraManager mgr;
    private CameraDevice device;
    private CameraCaptureSession session;
    private CaptureRequest.Builder builder;
    private Surface surface;
    private HandlerThread thread;
    private Handler handler;
    private Size previewSize;
    private boolean front, torch, torchAvail, wanted;

    CameraController(Context ctx, TextureView tv, Callback cb) {
        this.ctx = ctx;
        this.tv = tv;
        this.cb = cb;
        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
                if (wanted && device == null) open();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture s) {
            }
        });
    }

    boolean isFront() {
        return front;
    }

    void start(boolean front) {
        this.front = front;
        wanted = true;
        if (thread == null) {
            thread = new HandlerThread("camera");
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        if (tv.isAvailable() && device == null) open();
    }

    void stop() {
        wanted = false;
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        try {
            if (device != null) device.close();
        } catch (Exception ignored) {
        }
        session = null;
        device = null;
        builder = null;
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    void release() {
        stop();
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }

    void setTorch(boolean on) {
        torch = on;
        applyRepeating();
    }

    private void err(final String m) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onError(m);
            }
        });
    }

    private String pick(boolean wantFront) throws CameraAccessException {
        int want = wantFront ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        String any = null;
        for (String id : mgr.getCameraIdList()) {
            CameraCharacteristics c = mgr.getCameraCharacteristics(id);
            Integer f = c.get(CameraCharacteristics.LENS_FACING);
            if (any == null) any = id;
            if (f != null && f == want) return id;
        }
        return any;
    }

    private static Size choose(Size[] sizes) {
        Size best = null;
        long bestArea = 0;
        for (Size s : sizes) {
            if (s.getWidth() > 1920 || s.getHeight() > 1080) continue;
            double r = (double) s.getWidth() / s.getHeight();
            if (Math.abs(r - 16.0 / 9) > 0.03) continue;
            long a = (long) s.getWidth() * s.getHeight();
            if (a > bestArea) {
                best = s;
                bestArea = a;
            }
        }
        if (best == null) {
            for (Size s : sizes) {
                if (s.getWidth() > 1920) continue;
                long a = (long) s.getWidth() * s.getHeight();
                if (a > bestArea) {
                    best = s;
                    bestArea = a;
                }
            }
        }
        return best != null ? best : sizes[0];
    }

    @SuppressLint("MissingPermission")
    private void open() {
        try {
            mgr = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String id = pick(front);
            if (id == null) {
                err("No camera found on this device");
                return;
            }
            CameraCharacteristics cc = mgr.getCameraCharacteristics(id);
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                err("Camera has no usable stream configuration");
                return;
            }
            previewSize = choose(map.getOutputSizes(SurfaceTexture.class));
            Boolean fl = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            torchAvail = fl != null && fl;
            final int pw = previewSize.getWidth(), ph = previewSize.getHeight();
            main.post(new Runnable() {
                @Override
                public void run() {
                    cb.onPreviewSize(pw, ph);
                }
            });
            mgr.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    if (!wanted) {
                        d.close();
                        return;
                    }
                    device = d;
                    startPreview();
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    d.close();
                    device = null;
                }

                @Override
                public void onError(CameraDevice d, int e) {
                    d.close();
                    device = null;
                    err("Camera error " + e);
                }
            }, handler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            err("Cannot open camera: " + e.getMessage());
        }
    }

    private void startPreview() {
        try {
            SurfaceTexture st = tv.getSurfaceTexture();
            if (st == null || device == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            if (surface != null) surface.release();
            surface = new Surface(st);
            builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            device.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    if (device == null) return;
                    session = s;
                    applyRepeating();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.onReady(torchAvail);
                        }
                    });
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    err("Could not start the camera preview");
                }
            }, handler);
        } catch (CameraAccessException | IllegalStateException e) {
            err("Preview error: " + e.getMessage());
        }
    }

    private void applyRepeating() {
        try {
            if (session == null || builder == null) return;
            builder.set(CaptureRequest.FLASH_MODE, torch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            session.setRepeatingRequest(builder.build(), null, handler);
        } catch (CameraAccessException | IllegalStateException ignored) {
        }
    }
}
