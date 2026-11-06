package app.specterscope;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.MediaActionSound;
import android.media.MediaPlayer;
import android.media.ThumbnailUtils;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.util.Size;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Specter Scope: an entertainment "ghost hunting" camera. It does not detect anything paranormal.
 * Camera motion, magnetometer changes and microphone level drive a simulation that draws SLS-style
 * tracked figures; the other tools (spirit box, EVP, EMF meter, thermal and night views) are likewise
 * clearly simulated or ordinary sensor readouts.
 */
public class MainActivity extends Activity implements SensorEventListener {
    private static final int REQ_PERM = 11;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String[] MODE_NAMES = {"SLS CAMERA", "NIGHT VISION", "THERMAL (SIMULATED)", "CAMERA"};
    private static final int SW = 48, SH = 86;

    // core objects
    private Settings st;
    private Engine engine;
    private SessionLog log;
    private CameraController cam;
    private SoundMeter meter;
    private SpiritBox box;
    private OverlayRecorder recorder;
    private SensorManager sm;
    private ToneGenerator tone;
    private TextToSpeech tts;
    private boolean ttsReady;
    private MediaPlayer player;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    // views
    private FrameLayout root, panelHost;
    private TextureView tv;
    private OverlayView overlay;
    private RadarView radar;
    private TextView statsView, bannerView, modeLabel, recLabel;
    private Button btnMode, btnTorch, btnFlip, btnRec, btnShutter;
    private View flashView;
    private boolean panelOpen;
    private Runnable panelTick;
    private TextView boxFreqView, boxWordsView;

    // camera/preview
    private int psW, psH, mode;
    private final Matrix texMatrix = new Matrix();
    private Paint camPaint = new Paint();
    private boolean torchOn;

    // frame analysis
    private final Bitmap smallBmp = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888);
    private final Bitmap thermalBmp = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888);
    private final int[] px = new int[SW * SH], prev = new int[SW * SH], lum = new int[SW * SH], heat = new int[SW * SH];
    private boolean havePrev;
    private double motion;

    // sensors
    private float emfBase = -1, emfDelta, emfShown, lux = -1, temp = Float.NaN;
    private boolean hasMag = true;
    private final float[] rot = new float[9], rot2 = new float[9], ori = new float[3];
    private double azSin, azCos = 1, azimuth;
    private long calUntil;
    private double calSum;
    private int calN;

    // timing / state
    private long lastTick, lastAnalyze, lastHud, lastBeep, lastAuto, recStart, evpStart;
    private float boxFreq = 87.5f;
    private final List<String> boxWords = new ArrayList<String>();
    private File evpCurrent;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        st = new Settings(this);
        log = new SessionLog(getFilesDir());
        engine = new Engine(System.nanoTime());
        engine.setListener(new Engine.Listener() {
            @Override
            public void onEvent(String type, String msg, boolean spawn) {
                onEngineEvent(type, msg, spawn);
            }
        });
        meter = new SoundMeter();
        box = new SpiritBox();
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 35);
        } catch (RuntimeException e) {
            tone = null;
        }
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true;
                    tts.setPitch(0.6f);
                    tts.setSpeechRate(0.8f);
                }
            }
        });
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();
        buildUi();
        applySettings();
        setMode(st.getInt("mode", 0));
        log.add("APP", "Session started");
        if (!st.getBool("seen", false)) showDisclaimer();
        requestPerms();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reg(Sensor.TYPE_MAGNETIC_FIELD);
        reg(Sensor.TYPE_ROTATION_VECTOR);
        reg(Sensor.TYPE_LIGHT);
        reg(Sensor.TYPE_AMBIENT_TEMPERATURE);
        startCamera();
        startMeter();
        lastTick = SystemClock.uptimeMillis();
        h.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
        if (recorder != null) stopVideo();
        if (meter.isRecording()) stopEvp();
        stopPlayback();
        box.stop();
        meter.stop();
        cam.stop();
        sm.unregisterListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cam.release();
        if (tone != null) tone.release();
        if (tts != null) tts.shutdown();
        pool.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (panelOpen) closePanel();
        else super.onBackPressed();
    }

    private void requestPerms() {
        List<String> need = new ArrayList<String>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.CAMERA);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else banner("CAMERA PERMISSION NEEDED");
        startMeter();
    }

    private void startCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            cam.start(st.getBool("front", false));
    }

    private void startMeter() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            meter.start();
    }

    private void reg(int type) {
        Sensor s = sm.getDefaultSensor(type);
        if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI);
        else if (type == Sensor.TYPE_MAGNETIC_FIELD) hasMag = false;
    }

    // ------------------------------------------------------------------ UI construction

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private Button btn(String s, View.OnClickListener l) {
        Button b = Ui.button(this, s, st.theme());
        b.setOnClickListener(l);
        return b;
    }

    private TextView label(String s, float sp) {
        return Ui.text(this, s, sp, 0xFFDDDDDD);
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(MATCH, WRAP);
        l.topMargin = dp(top);
        return l;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        tv = new TextureView(this);
        root.addView(tv, new FrameLayout.LayoutParams(MATCH, MATCH));
        tv.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                updateTransform();
            }
        });

        overlay = new OverlayView(this);
        overlay.thermal = thermalBmp;
        overlay.thermalMatrix = texMatrix;
        overlay.ents = engine.entities;
        root.addView(overlay, new FrameLayout.LayoutParams(MATCH, MATCH));

        cam = new CameraController(this, tv, new CameraController.Callback() {
            @Override
            public void onPreviewSize(int w, int hh) {
                psW = w;
                psH = hh;
                updateTransform();
            }

            @Override
            public void onReady(boolean torchAvailable) {
                btnTorch.setEnabled(torchAvailable);
                btnTorch.setAlpha(torchAvailable ? 1f : 0.4f);
                if (torchOn && torchAvailable) cam.setTorch(true);
            }

            @Override
            public void onError(String msg) {
                banner(msg);
            }
        });

        FrameLayout hud = new FrameLayout(this);
        root.addView(hud, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            @SuppressWarnings("deprecation")
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets ins) {
                hud.setPadding(0, ins.getSystemWindowInsetTop(), 0, ins.getSystemWindowInsetBottom());
                return ins;
            }
        });

        statsView = Ui.mono(this, "", 11, st.theme());
        statsView.setShadowLayer(3, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.START);
        sp.setMargins(dp(14), dp(18), 0, 0);
        hud.addView(statsView, sp);

        radar = new RadarView(this);
        radar.ents = engine.entities;
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(104), dp(104), Gravity.TOP | Gravity.END);
        rp.setMargins(0, dp(14), dp(12), 0);
        hud.addView(radar, rp);

        btnFlip = btn("Flip", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean f = !cam.isFront();
                st.putBool("front", f);
                cam.stop();
                cam.start(f);
            }
        });
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.END);
        fp.setMargins(0, dp(124), dp(12), 0);
        hud.addView(btnFlip, fp);

        modeLabel = Ui.mono(this, "", 12, st.theme());
        modeLabel.setShadowLayer(3, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        mp.setMargins(0, dp(60), 0, 0);
        modeLabel.setGravity(Gravity.CENTER);
        hud.addView(modeLabel, mp);

        recLabel = Ui.mono(this, "", 12, 0xFFFF5050);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        rl.setMargins(0, dp(78), 0, 0);
        hud.addView(recLabel, rl);

        bannerView = Ui.text(this, "", 18, Color.WHITE);
        bannerView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bannerView.setShadowLayer(6, 0, 0, Color.BLACK);
        bannerView.setAlpha(0f);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        bp.setMargins(0, dp(160), 0, 0);
        hud.addView(bannerView, bp);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM);
        bl.setMargins(dp(8), 0, dp(8), dp(18));
        hud.addView(bar, bl);

        btnMode = btn("Mode", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode((mode + 1) % MODE_NAMES.length);
            }
        });
        btnTorch = btn("Torch", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                torchOn = !torchOn;
                cam.setTorch(torchOn);
                btnTorch.setText(torchOn ? "Torch ON" : "Torch");
            }
        });
        btnShutter = new Button(this);
        GradientDrawable sh = new GradientDrawable();
        sh.setShape(GradientDrawable.OVAL);
        sh.setColor(0xFFFFFFFF);
        sh.setStroke(dp(4), st.theme());
        btnShutter.setBackground(sh);
        btnShutter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                capturePhoto();
            }
        });
        btnRec = btn("Rec", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleVideo();
            }
        });
        Button btnTools = btn("Tools", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTools();
            }
        });
        LinearLayout.LayoutParams small = new LinearLayout.LayoutParams(0, dp(44), 1f);
        small.setMargins(dp(4), 0, dp(4), 0);
        LinearLayout.LayoutParams big = new LinearLayout.LayoutParams(dp(68), dp(68));
        big.setMargins(dp(6), 0, dp(6), 0);
        bar.addView(btnMode, small);
        bar.addView(btnTorch, new LinearLayout.LayoutParams(small));
        bar.addView(btnShutter, big);
        bar.addView(btnRec, new LinearLayout.LayoutParams(small));
        bar.addView(btnTools, new LinearLayout.LayoutParams(small));

        flashView = new View(this);
        flashView.setBackgroundColor(Color.WHITE);
        flashView.setAlpha(0f);
        flashView.setClickable(false);
        root.addView(flashView, new FrameLayout.LayoutParams(MATCH, MATCH));

        panelHost = new FrameLayout(this);
        panelHost.setVisibility(View.GONE);
        panelHost.setClickable(true);
        root.addView(panelHost, new FrameLayout.LayoutParams(MATCH, MATCH));

        setContentView(root);
    }

    private void applySettings() {
        engine.sensitivity = st.getInt("sens", 6);
        engine.maxEntities = st.getInt("maxent", 3);
        overlay.color = st.theme();
        overlay.labels = st.getBool("labels", true);
        overlay.feet = st.getBool("feet", false);
        radar.color = st.theme();
        radar.setVisibility(st.getBool("radar", true) ? View.VISIBLE : View.GONE);
        statsView.setTextColor(st.theme());
        modeLabel.setTextColor(st.theme());
        for (Button b : new Button[]{btnMode, btnTorch, btnRec, btnFlip}) {
            b.setTextColor(st.theme());
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xAA000000);
            g.setCornerRadius(dp(20));
            g.setStroke(dp(1), st.theme());
            b.setBackground(g);
        }
        box.wordsOn = true;
    }

    private void updateTransform() {
        int W = tv.getWidth(), H = tv.getHeight();
        if (W == 0 || H == 0 || psW == 0) return;
        float pw = psH, ph = psW;                     // the portrait-oriented preview buffer
        float s = Math.max(W / pw, H / ph);
        float sx = pw * s / W, sy = ph * s / H;
        texMatrix.setScale(cam.isFront() ? -sx : sx, sy, W / 2f, H / 2f);
        tv.setTransform(texMatrix);
    }

    private void setMode(int m) {
        mode = m;
        st.putInt("mode", m);
        overlay.mode = m;
        Paint p = new Paint();
        if (m == OverlayView.SLS) {
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0);
            cm.postConcat(new ColorMatrix(new float[]{0.55f, 0, 0, 0, 0, 0, 1.05f, 0, 0, 8, 0, 0, 0.55f, 0, 0, 0, 0, 0, 1, 0}));
            p.setColorFilter(new ColorMatrixColorFilter(cm));
        } else if (m == OverlayView.NIGHT) {
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0);
            cm.postConcat(new ColorMatrix(new float[]{0.35f, 0, 0, 0, 0, 0, 1.7f, 0, 0, 20, 0, 0, 0.35f, 0, 0, 0, 0, 0, 1, 0}));
            p.setColorFilter(new ColorMatrixColorFilter(cm));
        }
        camPaint = p;
        tv.setLayerPaint(p);
        modeLabel.setText(MODE_NAMES[m]);
        havePrev = false;
    }

    // ------------------------------------------------------------------ main loop

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            double dt = Math.min(0.1, (now - lastTick) / 1000.0);
            lastTick = now;
            if (now - lastAnalyze >= 120) {
                lastAnalyze = now;
                analyze();
            }
            double snd = box.isRunning() ? 0 : meter.level;
            engine.setInputs(motion, emfDelta, snd);
            engine.step(dt, azimuth);
            overlay.deviceAz = azimuth;
            radar.deviceAz = azimuth;
            overlay.invalidate();
            radar.invalidate();
            if (now - lastHud >= 250) {
                lastHud = now;
                updateHud(now);
            }
            beeps(now);
            h.postDelayed(this, 33);
        }
    };

    private void analyze() {
        if (!tv.isAvailable()) return;
        try {
            tv.getBitmap(smallBmp);
        } catch (Exception e) {
            return;
        }
        smallBmp.getPixels(px, 0, SW, 0, 0, SW, SH);
        int min = 255, max = 0;
        long diff = 0;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int l = (((p >> 16) & 255) * 77 + ((p >> 8) & 255) * 151 + (p & 255) * 28) >> 8;
            if (havePrev) diff += Math.abs(l - prev[i]);
            prev[i] = l;
            lum[i] = l;
            if (l < min) min = l;
            if (l > max) max = l;
        }
        if (havePrev) {
            double mean = (double) diff / px.length;
            double m = Math.max(0, Math.min(1, (mean - 1.5) / 14.0));
            motion = motion * 0.5 + m * 0.5;
        }
        havePrev = true;
        if (mode == OverlayView.THERMAL) {
            int range = Math.max(30, max - min);
            for (int i = 0; i < px.length; i++) {
                int v = (lum[i] - min) * 255 / range;
                heat[i] = OverlayView.PALETTE[Math.max(0, Math.min(255, v))];
            }
            thermalBmp.setPixels(heat, 0, SW, 0, 0, SW, SH);
        }
    }

    private static String bar(int n, int total) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < total; i++) sb.append(i < n ? '#' : '-');
        return sb.toString();
    }

    private void updateHud(long now) {
        StringBuilder s = new StringBuilder();
        if (hasMag) {
            s.append(String.format(Locale.US, "EMF %5.1fuT [%s]\n", emfShown, bar(engine.emfLevel(emfShown), 5)));
        } else {
            s.append("EMF  no sensor\n");
        }
        if (meter.isRunning()) s.append(String.format(Locale.US, "SND %4.0fdB\n", meter.db));
        else s.append("SND  mic off\n");
        s.append(String.format(Locale.US, "MOT %3d%%\n", (int) Math.round(motion * 100)));
        if (lux >= 0) s.append(String.format(Locale.US, "LUX %4.0f\n", lux));
        if (!Float.isNaN(temp)) s.append(String.format(Locale.US, "TMP %4.1fC\n", temp));
        int act = (int) Math.round(engine.level() * 100);
        s.append(String.format(Locale.US, "ACT [%s] %d%%\n", bar(act / 10, 10), act));
        s.append(String.format(Locale.US, "ENT %d  BRG %03d\n", engine.entities.size(), (int) Math.round(azimuth) % 360));
        if (box.isRunning()) s.append(String.format(Locale.US, "BOX %.1f MHz\n", boxFreq));
        if (meter.isRecording()) s.append("EVP REC\n");
        statsView.setText(s.toString());

        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        overlay.caption = "SPECTER SCOPE | " + MODE_NAMES[mode] + " | " + t
                + (hasMag ? String.format(Locale.US, " | EMF %.1fuT", emfShown) : "");
        if (recorder != null) {
            long sec = (now - recStart) / 1000;
            overlay.recText = String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
            recLabel.setText("REC " + overlay.recText);
        } else {
            recLabel.setText("");
        }
        overlay.rec = recorder != null;
    }

    private void beeps(long now) {
        if (tone == null || !st.getBool("sound", true)) return;
        double lv = engine.level();
        if (lv < 0.25) return;
        long interval = (long) (1400 - 1280 * lv);
        if (now - lastBeep >= interval) {
            lastBeep = now;
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 40);
        }
    }

    // ------------------------------------------------------------------ sensors

    @Override
    public void onSensorChanged(SensorEvent e) {
        switch (e.sensor.getType()) {
            case Sensor.TYPE_MAGNETIC_FIELD: {
                float m = (float) Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]);
                if (emfBase < 0) emfBase = m;
                if (calUntil > 0) {
                    calSum += m;
                    calN++;
                    if (SystemClock.uptimeMillis() >= calUntil) {
                        calUntil = 0;
                        emfBase = (float) (calSum / Math.max(1, calN));
                        banner(String.format(Locale.US, "CALIBRATED  BASELINE %.1f uT", emfBase));
                        log.add("CAL", String.format(Locale.US, "Baseline set to %.1f uT", emfBase));
                    }
                } else {
                    emfBase += (m - emfBase) * 0.01f;
                }
                emfDelta = Math.abs(m - emfBase);
                emfShown = Math.max(emfDelta, emfShown * 0.96f);
                break;
            }
            case Sensor.TYPE_ROTATION_VECTOR: {
                SensorManager.getRotationMatrixFromVector(rot, e.values);
                SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, rot2);
                SensorManager.getOrientation(rot2, ori);
                double rad = ori[0];
                azSin += (Math.sin(rad) - azSin) * 0.2;
                azCos += (Math.cos(rad) - azCos) * 0.2;
                azimuth = (Math.toDegrees(Math.atan2(azSin, azCos)) + 360) % 360;
                break;
            }
            case Sensor.TYPE_LIGHT:
                lux = e.values[0];
                break;
            case Sensor.TYPE_AMBIENT_TEMPERATURE:
                temp = e.values[0];
                break;
            default:
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void startCalibration() {
        calUntil = SystemClock.uptimeMillis() + 5000;
        calSum = 0;
        calN = 0;
        banner("CALIBRATING - HOLD STILL");
    }

    // ------------------------------------------------------------------ events & feedback

    private void onEngineEvent(String type, String msg, boolean spawn) {
        log.add(type, msg);
        if (spawn) {
            banner("ENTITY DETECTED");
            if (st.getBool("vib", true)) vibrate(new long[]{0, 60, 80, 120});
            if (tone != null && st.getBool("sound", true)) tone.startTone(ToneGenerator.TONE_PROP_ACK, 150);
            long now = SystemClock.uptimeMillis();
            if (st.getBool("auto", false) && now - lastAuto > 6000) {
                lastAuto = now;
                capturePhoto();
            }
        } else if (type.equals("EMF")) {
            banner("EMF SPIKE");
        }
    }

    @SuppressWarnings("deprecation")
    private void vibrate(long[] pattern) {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } catch (Exception ignored) {
        }
    }

    private void banner(String s) {
        bannerView.setText(s);
        bannerView.animate().cancel();
        bannerView.setAlpha(1f);
        bannerView.animate().alpha(0f).setStartDelay(1800).setDuration(600).start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void showDisclaimer() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Entertainment only")
                .setMessage("Specter Scope is a novelty app. It does NOT detect ghosts, spirits or anything paranormal.\n\n"
                        + "The tracked figures, radar blips, spirit box words and \"thermal\" colours are generated by a "
                        + "simulation that reacts to your phone's real sensors (camera motion, magnetometer, microphone). "
                        + "The EMF reading is the phone's magnetometer, which responds to ordinary metal and electronics.")
                .setCancelable(false)
                .setPositiveButton("I understand", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        st.putBool("seen", true);
                    }
                }).show();
    }

    // ------------------------------------------------------------------ capture

    private File capDir() {
        File base = getExternalFilesDir(null);
        File d = new File(base != null ? base : getFilesDir(), "captures");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private File evpDir() {
        File base = getExternalFilesDir(null);
        File d = new File(base != null ? base : getFilesDir(), "evp");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    /** Draws camera image (with the current colour filter) and the overlay into out. */
    private void composeInto(Bitmap out, Bitmap tex) {
        Canvas c = new Canvas(out);
        c.drawColor(Color.BLACK);
        int vw = tv.getWidth(), vh = tv.getHeight();
        c.save();
        c.scale(out.getWidth() / (float) vw, out.getHeight() / (float) vh);
        if (mode != OverlayView.THERMAL) {
            c.save();
            c.concat(texMatrix);
            c.drawBitmap(tex, null, new RectF(0, 0, vw, vh), camPaint);
            c.restore();
        }
        overlay.draw(c);
        c.restore();
    }

    private void capturePhoto() {
        if (!tv.isAvailable() || tv.getWidth() == 0) {
            toast("Camera not ready");
            return;
        }
        try {
            int vw = tv.getWidth(), vh = tv.getHeight();
            Bitmap tex = tv.getBitmap(vw, vh);
            Bitmap out = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888);
            composeInto(out, tex);
            tex.recycle();
            File f = new File(capDir(), "IMG_" + stamp() + ".jpg");
            try (FileOutputStream fo = new FileOutputStream(f)) {
                out.compress(Bitmap.CompressFormat.JPEG, 92, fo);
            }
            out.recycle();
            exportToGallery(f, "image/jpeg", true);
            new MediaActionSound().play(MediaActionSound.SHUTTER_CLICK);
            flashView.setAlpha(0.8f);
            flashView.animate().alpha(0f).setDuration(250).start();
            log.add("PHOTO", "Saved " + f.getName());
            toast("Saved to Pictures/SpecterScope");
        } catch (Throwable t) {
            toast("Capture failed: " + t.getMessage());
        }
    }

    private void toggleVideo() {
        if (recorder != null) {
            stopVideo();
            return;
        }
        int vw = tv.getWidth(), vh = tv.getHeight();
        if (vw == 0 || !tv.isAvailable()) {
            toast("Camera not ready");
            return;
        }
        float s = Math.min(1f, 720f / vw);
        int w = ((int) (vw * s)) & ~15, hh = ((int) (vh * s)) & ~15;
        final Bitmap tex = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
        File f = new File(capDir(), "VID_" + stamp() + ".mp4");
        recorder = new OverlayRecorder(f, w, hh, new OverlayRecorder.Composer() {
            @Override
            public void compose(Bitmap out) {
                if (!tv.isAvailable()) return;
                tv.getBitmap(tex);
                composeInto(out, tex);
            }
        });
        if (!recorder.start()) {
            recorder = null;
            toast("Video encoder not available on this device");
            return;
        }
        recStart = SystemClock.uptimeMillis();
        btnRec.setText("Stop");
        log.add("VIDEO", "Recording started");
    }

    private void stopVideo() {
        OverlayRecorder r = recorder;
        recorder = null;
        btnRec.setText("Rec");
        if (r == null) return;
        r.stop();
        exportToGallery(r.file(), "video/mp4", false);
        log.add("VIDEO", "Saved " + r.file().getName() + " (no audio)");
        toast("Video saved to Movies/SpecterScope");
    }

    private void exportToGallery(File f, String mime, boolean image) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    (image ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_MOVIES) + "/SpecterScope");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri col = image ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            Uri u = getContentResolver().insert(col, v);
            if (u == null) return;
            try (OutputStream os = getContentResolver().openOutputStream(u); InputStream is = new FileInputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while (os != null && (n = is.read(buf)) > 0) os.write(buf, 0, n);
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(u, v, null, null);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ panels

    private LinearLayout openPanel(String title) {
        closePanel();
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(0xF2081008);
        p.setPadding(dp(16), dp(40), dp(16), dp(16));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.text(this, title, 20, st.theme());
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        top.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        top.addView(btn("Close", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closePanel();
            }
        }));
        p.addView(top, new LinearLayout.LayoutParams(MATCH, WRAP));
        panelHost.addView(p, new FrameLayout.LayoutParams(MATCH, MATCH));
        panelHost.setVisibility(View.VISIBLE);
        panelOpen = true;
        return p;
    }

    private void closePanel() {
        if (panelTick != null) h.removeCallbacks(panelTick);
        panelTick = null;
        stopPlayback();
        boxFreqView = null;
        boxWordsView = null;
        panelHost.removeAllViews();
        panelHost.setVisibility(View.GONE);
        panelOpen = false;
    }

    private void runPanelTick(Runnable r, final long periodMs) {
        final Runnable body = r;
        panelTick = new Runnable() {
            @Override
            public void run() {
                if (panelTick != this) return;
                body.run();
                h.postDelayed(this, periodMs);
            }
        };
        h.post(panelTick);
    }

    private SeekBar seek(int max, int val, final IntConsumer c) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(val);
        s.setProgressTintList(ColorStateList.valueOf(st.theme()));
        s.setThumbTintList(ColorStateList.valueOf(st.theme()));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) c.accept(p);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        return s;
    }

    private CheckBox check(String text, boolean val, final Consumer<Boolean> c) {
        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        cb.setTextColor(0xFFEEEEEE);
        cb.setChecked(val);
        cb.setButtonTintList(ColorStateList.valueOf(st.theme()));
        cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                c.accept(on);
            }
        });
        return cb;
    }

    private void openTools() {
        LinearLayout p = openPanel("TOOLS");
        String[] names = {"Spirit Box", "EVP Recorder", "EMF Meter", "Gallery", "Investigation Log",
                "Calibrate Sensors", "Settings", "About"};
        for (final String n : names) {
            p.addView(btn(n, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switch (n) {
                        case "Spirit Box": openSpiritBox(); break;
                        case "EVP Recorder": openEvp(); break;
                        case "EMF Meter": openEmf(); break;
                        case "Gallery": openGallery(); break;
                        case "Investigation Log": openLog(); break;
                        case "Calibrate Sensors": closePanel(); startCalibration(); break;
                        case "Settings": openSettings(); break;
                        default: openAbout();
                    }
                }
            }), lp(10));
        }
    }

    // ---- spirit box

    private final SpiritBox.Listener boxListener = new SpiritBox.Listener() {
        @Override
        public void onFreq(float mhz) {
            boxFreq = mhz;
            if (boxFreqView != null) boxFreqView.setText(String.format(Locale.US, "%.1f MHz", mhz));
        }

        @Override
        public void onWord(String w) {
            boxWords.add(0, w);
            while (boxWords.size() > 14) boxWords.remove(boxWords.size() - 1);
            log.add("BOX", "Word: " + w);
            if (boxWordsView != null) boxWordsView.setText(joinWords());
            if (ttsReady && st.getBool("speak", false)) tts.speak(w, TextToSpeech.QUEUE_ADD, null, "w");
        }
    };

    private String joinWords() {
        StringBuilder sb = new StringBuilder();
        for (String w : boxWords) sb.append(w.toUpperCase(Locale.US)).append("   ");
        return sb.toString();
    }

    private void openSpiritBox() {
        LinearLayout p = openPanel("SPIRIT BOX");
        boxFreqView = Ui.mono(this, String.format(Locale.US, "%.1f MHz", boxFreq), 34, st.theme());
        boxFreqView.setGravity(Gravity.CENTER);
        p.addView(boxFreqView, lp(16));
        final Button go = btn(box.isRunning() ? "STOP SWEEP" : "START SWEEP", null);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (box.isRunning()) {
                    box.stop();
                    go.setText("START SWEEP");
                    log.add("BOX", "Sweep stopped");
                } else {
                    box.start(boxListener);
                    go.setText("STOP SWEEP");
                    log.add("BOX", "Sweep started");
                }
            }
        });
        p.addView(go, lp(12));
        p.addView(label("Volume", 13), lp(14));
        p.addView(seek(100, (int) (box.gain * 100), new IntConsumer() {
            @Override
            public void accept(int v) {
                box.gain = v / 100f;
            }
        }), lp(0));
        p.addView(label("Sweep speed", 13), lp(10));
        p.addView(seek(100, (600 - box.dwellMs) / 5, new IntConsumer() {
            @Override
            public void accept(int v) {
                box.dwellMs = 600 - v * 5;
            }
        }), lp(0));
        p.addView(check("Speak words aloud", st.getBool("speak", false), new Consumer<Boolean>() {
            @Override
            public void accept(Boolean on) {
                st.putBool("speak", on);
            }
        }), lp(8));
        boxWordsView = Ui.mono(this, joinWords(), 15, 0xFFFFFFFF);
        p.addView(boxWordsView, lp(14));
        p.addView(label("The sound is synthesized radio noise and garble, and the words are picked at random from a "
                + "list. It does not receive anything. For fun only.", 11), lp(18));
    }

    // ---- EVP

    private void openEvp() {
        final LinearLayout p = openPanel("EVP RECORDER");
        if (!meter.isRunning()) {
            p.addView(label("Microphone is off. Allow the microphone permission and reopen this screen.", 14), lp(16));
            return;
        }
        final TextView status = Ui.mono(this, "Ready", 18, st.theme());
        p.addView(status, lp(14));
        final ProgressBar level = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        level.setMax(100);
        level.setProgressTintList(ColorStateList.valueOf(st.theme()));
        p.addView(level, lp(8));
        final Button rec = btn(meter.isRecording() ? "STOP RECORDING" : "RECORD", null);
        p.addView(rec, lp(12));
        ScrollView sv = new ScrollView(this);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        p.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        fillEvpList(list);
        rec.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (meter.isRecording()) {
                    stopEvp();
                    rec.setText("RECORD");
                    fillEvpList(list);
                } else if (startEvp()) {
                    rec.setText("STOP RECORDING");
                } else {
                    toast("Could not start recording");
                }
            }
        });
        runPanelTick(new Runnable() {
            @Override
            public void run() {
                level.setProgress((int) (meter.level * 100));
                if (meter.isRecording()) {
                    long s = meter.recordedMs() / 1000;
                    status.setText(String.format(Locale.US, "REC %02d:%02d", s / 60, s % 60));
                } else {
                    status.setText(String.format(Locale.US, "Listening  %.0f dB", meter.db));
                }
            }
        }, 120);
    }

    private boolean startEvp() {
        evpCurrent = new File(evpDir(), "EVP_" + stamp() + ".wav");
        boolean ok = meter.startRecording(evpCurrent);
        if (ok) log.add("EVP", "Recording started");
        return ok;
    }

    private void stopEvp() {
        File f = meter.stopRecording();
        if (f != null) log.add("EVP", "Saved " + f.getName());
    }

    private void fillEvpList(final LinearLayout list) {
        list.removeAllViews();
        File[] fs = evpDir().listFiles();
        if (fs == null || fs.length == 0) {
            list.addView(label("No recordings yet.", 14), lp(12));
            return;
        }
        Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (final File f : fs) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            long sec = f.length() / (SoundMeter.RATE * 2L);
            TextView t = Ui.mono(this, f.getName().replace("EVP_", "").replace(".wav", "")
                    + String.format(Locale.US, "  %ds", sec), 12, 0xFFDDDDDD);
            row.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
            final Button play = btn("Play", null);
            play.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (player != null) {
                        stopPlayback();
                        return;
                    }
                    playFile(f);
                }
            });
            row.addView(play);
            row.addView(btn("Del", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    stopPlayback();
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    fillEvpList(list);
                }
            }));
            list.addView(row, lp(8));
        }
    }

    private void playFile(File f) {
        try {
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.prepare();
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    stopPlayback();
                }
            });
            player.start();
        } catch (IOException | RuntimeException e) {
            stopPlayback();
            toast("Cannot play this file");
        }
    }

    private void stopPlayback() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            player.release();
            player = null;
        }
    }

    // ---- EMF

    private void openEmf() {
        LinearLayout p = openPanel("EMF METER");
        if (!hasMag) {
            p.addView(label("This device has no magnetometer, so the EMF meter is unavailable.", 14), lp(16));
            return;
        }
        final TextView big = Ui.mono(this, "0.0 uT", 38, st.theme());
        big.setGravity(Gravity.CENTER);
        p.addView(big, lp(16));
        final LedBar led = new LedBar(this);
        p.addView(led, new LinearLayout.LayoutParams(MATCH, dp(34)));
        ((LinearLayout.LayoutParams) led.getLayoutParams()).topMargin = dp(12);
        final GraphView g = new GraphView(this, st.theme());
        g.setRange(0, 30);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(MATCH, dp(180));
        gl.topMargin = dp(16);
        p.addView(g, gl);
        p.addView(label("Shows how far the magnetic field is from its slow-moving baseline. Phones, speakers, wiring and "
                + "metal all move it. Hold the phone steady and tap Calibrate to reset the baseline.", 12), lp(14));
        p.addView(btn("Calibrate (5 s)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startCalibration();
                toast("Hold the phone still for 5 seconds");
            }
        }), lp(14));
        runPanelTick(new Runnable() {
            @Override
            public void run() {
                big.setText(String.format(Locale.US, "%.1f uT", emfShown));
                led.setLevel(engine.emfLevel(emfShown));
                g.push(emfDelta);
            }
        }, 100);
    }

    // ---- gallery

    private void openGallery() {
        LinearLayout p = openPanel("GALLERY");
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        p.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        File[] fs = capDir().listFiles();
        if (fs == null || fs.length == 0) {
            list.addView(label("No captures yet. Photos and videos also appear in your phone's gallery.", 14), lp(16));
            return;
        }
        Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        int cell = (getResources().getDisplayMetrics().widthPixels - dp(32)) / 3;
        LinearLayout row = null;
        int col = 0;
        for (final File f : fs) {
            final boolean video = f.getName().endsWith(".mp4");
            if (!video && !f.getName().endsWith(".jpg")) continue;
            if (col == 0) {
                row = new LinearLayout(this);
                list.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
            }
            FrameLayout cellView = new FrameLayout(this);
            final ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFF111111);
            cellView.addView(iv, new FrameLayout.LayoutParams(MATCH, MATCH));
            if (video) {
                TextView tag = Ui.mono(this, "VIDEO", 10, Color.WHITE);
                tag.setShadowLayer(3, 0, 0, Color.BLACK);
                cellView.addView(tag, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.START));
            }
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(cell - dp(4), cell - dp(4));
            cl.setMargins(dp(2), dp(2), dp(2), dp(2));
            row.addView(cellView, cl);
            loadThumb(f, iv, cell, video);
            cellView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openViewer(f, video);
                }
            });
            col = (col + 1) % 3;
        }
    }

    private void loadThumb(final File f, final ImageView iv, final int size, final boolean video) {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Bitmap b = video
                            ? ThumbnailUtils.createVideoThumbnail(f, new Size(size, size), null)
                            : ThumbnailUtils.createImageThumbnail(f, new Size(size, size), null);
                    iv.post(new Runnable() {
                        @Override
                        public void run() {
                            iv.setImageBitmap(b);
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void openViewer(final File f, boolean video) {
        LinearLayout p = openPanel(f.getName());
        if (video) {
            VideoView vv = new VideoView(this);
            vv.setVideoPath(f.getAbsolutePath());
            MediaController mc = new MediaController(this);
            mc.setAnchorView(vv);
            vv.setMediaController(mc);
            p.addView(vv, new LinearLayout.LayoutParams(MATCH, 0, 1f));
            vv.start();
        } else {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            iv.setImageBitmap(BitmapFactory.decodeFile(f.getAbsolutePath(), o));
            p.addView(iv, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        }
        p.addView(btn("Delete", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                toast("Deleted from the app (a copy remains in your phone's gallery)");
                openGallery();
            }
        }), lp(10));
    }

    // ---- log, settings, about

    private void openLog() {
        LinearLayout p = openPanel("INVESTIGATION LOG");
        final ScrollView sv = new ScrollView(this);
        TextView t = Ui.mono(this, log.size() == 0 ? "No events yet." : log.text(), 10, 0xFFDDDDDD);
        sv.addView(t);
        p.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        sv.post(new Runnable() {
            @Override
            public void run() {
                sv.fullScroll(View.FOCUS_DOWN);
            }
        });
        LinearLayout row = new LinearLayout(this);
        row.addView(btn("Share", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, log.text());
                startActivity(Intent.createChooser(i, "Share log"));
            }
        }));
        row.addView(btn("Clear", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                log.clear();
                openLog();
            }
        }));
        p.addView(row, lp(10));
    }

    private void openSettings() {
        LinearLayout p = openPanel("SETTINGS");
        ScrollView sv = new ScrollView(this);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        sv.addView(c);
        p.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        c.addView(label("Sensitivity (higher = more activity)", 13), lp(10));
        c.addView(seek(9, st.getInt("sens", 6) - 1, new IntConsumer() {
            @Override
            public void accept(int v) {
                st.putInt("sens", v + 1);
                applySettings();
            }
        }), lp(0));
        c.addView(label("Maximum entities at once", 13), lp(10));
        c.addView(seek(5, st.getInt("maxent", 3) - 1, new IntConsumer() {
            @Override
            public void accept(int v) {
                st.putInt("maxent", v + 1);
                applySettings();
            }
        }), lp(0));

        String[][] opts = {{"sound", "Sound alerts and proximity beeps", "1"}, {"vib", "Vibrate on detection", "1"},
                {"auto", "Auto-capture photo on detection", "0"}, {"labels", "Show entity labels", "1"},
                {"radar", "Show radar", "1"}, {"feet", "Distances in feet", "0"}};
        for (final String[] o : opts) {
            c.addView(check(o[1], st.getBool(o[0], o[2].equals("1")), new Consumer<Boolean>() {
                @Override
                public void accept(Boolean on) {
                    st.putBool(o[0], on);
                    applySettings();
                }
            }), lp(4));
        }

        c.addView(label("Colour theme", 13), lp(14));
        LinearLayout themes = new LinearLayout(this);
        for (int i = 0; i < Settings.THEMES.length; i++) {
            final int idx = i;
            Button b = Ui.button(this, Settings.THEME_NAMES[i], Settings.THEMES[i]);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    st.putInt("theme", idx);
                    applySettings();
                    openSettings();
                }
            });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, WRAP, 1f);
            l.setMargins(dp(3), 0, dp(3), 0);
            themes.addView(b, l);
        }
        c.addView(themes, lp(6));
        c.addView(btn("Show disclaimer again", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDisclaimer();
            }
        }), lp(18));
    }

    private void openAbout() {
        LinearLayout p = openPanel("ABOUT");
        ScrollView sv = new ScrollView(this);
        sv.addView(label("Specter Scope is an entertainment app.\n\n"
                + "It does not detect ghosts, spirits or any paranormal activity, and nothing it shows should be "
                + "taken as evidence of any.\n\n"
                + "What is real:\n"
                + "- the camera image and the motion measured from it\n"
                + "- the EMF figure (your phone's magnetometer, which reacts to ordinary metal and electronics)\n"
                + "- the microphone level, light level and compass heading\n\n"
                + "What is simulated:\n"
                + "- the tracked stick figures, their distances and confidence values\n"
                + "- the radar blips\n"
                + "- the \"thermal\" colours (a false-colour map of image brightness, not heat)\n"
                + "- the spirit box sounds and words (random noise and random words)\n\n"
                + "The simulation reacts to the real sensors, so more motion, magnetic change or noise makes "
                + "figures appear more often. Photos and video include the overlay; video has no sound.\n\n"
                + "Captures are saved in Pictures/SpecterScope and Movies/SpecterScope.", 14));
        p.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));
    }
}
