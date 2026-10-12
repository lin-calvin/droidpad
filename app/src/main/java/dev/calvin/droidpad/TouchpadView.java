package dev.calvin.droidpad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashMap;

/** Full-screen surface that streams multi-touch contacts as HID reports. */
public final class TouchpadView extends View implements SensorEventListener {

    public interface Sink { boolean send(byte[] payload); }

    private static final int MAX = HidDescriptor.MAX_CONTACTS;
    private static final int LOGICAL_MAX = 32767;

    // barometer-based "force press" (3D-touch lite)
    // "force" = contact area (getTouchMajor, mm) + barometer delta
    private static final float MAJ_MIN = 4.8f, MAJ_MAX = 11.0f;  // mm range seen on this panel
    private static final float PRESS_FORCE = 0.60f, RELEASE_FORCE = 0.45f;
    private static final float AREA_W = 0.65f, BARO_W = 0.35f;
    private static final float BARO_REF = 0.12f;   // hPa mapped to 1.0

    private final SensorManager sensorManager;
    private final Sensor pressureSensor;
    private volatile float pressure = Float.NaN;
    private float baseline = Float.NaN;
    private boolean forcePressed;
    private int activeCount;
    private float touchArea;   // max MotionEvent size among active pointers (0..1)
    private float touchPressure;
    private float touchMajor;
    private float force;       // combined metric
    private float sDp = Float.NaN, sDpPrev, dDp;
    private long lastSampleTime;
    private long lastBarLog;
    private long downSince;   // when the first finger landed (ignore that pressure transient)

    private final Sink sink;
    private final byte[] report = new byte[HidDescriptor.REPORT_BYTES];
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Vibrator vibrator;
    private final Prefs prefs;
    private final boolean debug;

    private final HashMap<Integer, Integer> pidToSlot = new HashMap<>();
    private final boolean[] slotDown = new boolean[MAX];
    private final int[] slotX = new int[MAX];
    private final int[] slotY = new int[MAX];

    // tap detection
    private long downTime;
    private float downX, downY, maxMove;
    private int maxPointers;
    private boolean buttonLeft, buttonRight;
    private Runnable releaseBtn;

    public TouchpadView(Context ctx, Sink sink) {
        super(ctx);
        this.sink = sink;
        this.prefs = new Prefs(ctx);
        this.debug = prefs.raw().getBoolean("debug", false);
        setBackgroundColor(Color.BLACK);
        ring.setColor(0x55FFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(4f);
        dot.setColor(0xAAFFFFFF);
        label.setColor(0x66FFFFFF);
        label.setTextSize(30f);
        Vibrator v = null;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm != null) v = vm.getDefaultVibrator();
        } else {
            v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        }
        vibrator = v;
        sensorManager = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        pressureSensor = sensorManager != null
                ? sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) : null;
        if (pressureSensor != null)
            sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() != Sensor.TYPE_PRESSURE) return;
        pressure = e.values[0];
        if (Float.isNaN(baseline)) { baseline = pressure; return; }
        long now = android.os.SystemClock.uptimeMillis();
        boolean settling = prefs.blockLanding() && activeCount > 0 && (now - downSince) < 300;
        if (activeCount == 0) {
            if (!forcePressed) baseline += 0.02f * (pressure - baseline);  // slow tracking while idle
        } else if (settling) {
            baseline = pressure;   // absorb the finger-landing pressure transient
        }
        float delta = pressure - baseline;
        sDp = Float.isNaN(sDp) ? delta : sDp + 0.4f * (delta - sDp);   // smoothed delta
        if (lastSampleTime != 0) {
            float dt = (now - lastSampleTime) / 1000f;
            if (dt > 0) dDp = (sDp - sDpPrev) / dt;                    // d(delta)/dt per second
        }
        sDpPrev = sDp;
        lastSampleTime = now;
        force = AREA_W * clamp01((touchMajor - MAJ_MIN) / (MAJ_MAX - MAJ_MIN))
                + BARO_W * clamp01(delta / BARO_REF);
        if (now - lastBarLog > 120) {
            lastBarLog = now;
            android.util.Log.i("DroidPad/Bar", String.format(
                    "force %.2f maj %.1f d %+.3f settling=%b active=%d press=%b",
                    force, touchMajor, delta, settling, activeCount, forcePressed));
        }
        if (!prefs.forcePress() || settling) {
            if (forcePressed) { forcePressed = false; emit(); }
        } else {
            float th = prefs.forceThreshold();
            float rel = th - 0.15f;
            if (!forcePressed && activeCount > 0 && force > th) {
                forcePressed = true;
                clickHaptic();
                emit();
            } else if (forcePressed && force < rel) {
                forcePressed = false;
                emit();
            }
        }
        invalidate();
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    private void tick() {
        if (!prefs.haptics()) return;
        try {
            if (vibrator != null && vibrator.hasVibrator())
                vibrator.vibrate(VibrationEffect.createOneShot(12, prefs.hapticStrength()));
        } catch (Exception ignored) {}
    }

    private void clickHaptic() {
        if (!prefs.haptics()) return;
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                int a = prefs.hapticStrength();
                vibrator.vibrate(VibrationEffect.createWaveform(
                        new long[]{0, 10, 12, 14}, new int[]{0, a, 0, a}, -1));
            }
        } catch (Exception ignored) {}
    }

    private int allocSlot() {
        for (int i = 0; i < MAX; i++) if (!slotDown[i] && !pidToSlot.containsValue(i)) return i;
        for (int i = 0; i < MAX; i++) if (!slotDown[i]) return i;
        return 0;
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        int actionIndex = ev.getActionIndex();
        int n = ev.getPointerCount();
        liveRaw = n;
        if (n > maxRawPointers) {
            maxRawPointers = n;
            android.util.Log.i("DroidPad/Touch", "raw pointerCount=" + n + " action=" + action);
        }
        if (n > maxPointers) maxPointers = n;

        if (action == MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < MAX; i++) slotDown[i] = false;
            pidToSlot.clear();
            emit();
            return true;
        }

        if (action == MotionEvent.ACTION_DOWN) {
            downTime = ev.getEventTime();
            downSince = android.os.SystemClock.uptimeMillis();
            downX = ev.getX(0);
            downY = ev.getY(0);
            maxMove = 0;
            maxPointers = 1;
        }

        float maxSz = 0f, maxPr = 0f, maxMj = 0f;
        for (int pi = 0; pi < n; pi++) {
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                    && pi == actionIndex) {
                continue; // lifting pointer, handled below
            }
            int pid = ev.getPointerId(pi);
            Integer slot = pidToSlot.get(pid);
            if (slot == null) {
                slot = allocSlot();
                pidToSlot.put(pid, slot);
            }
            slotDown[slot] = true;
            int x = clampLogical(ev.getX(pi), getWidth());
            int y = clampLogical(ev.getY(pi), getHeight());
            slotX[slot] = x;
            slotY[slot] = y;
            float dx = ev.getX(pi) - downX, dy = ev.getY(pi) - downY;
            float m = (float) Math.hypot(dx, dy);
            if (m > maxMove) maxMove = m;
            float sz = ev.getSize(pi);
            if (sz > maxSz) maxSz = sz;
            float pr = ev.getPressure(pi);
            if (pr > maxPr) maxPr = pr;
            float mj = ev.getTouchMajor(pi);
            if (mj > maxMj) maxMj = mj;
        }
        touchArea = maxSz;
        touchPressure = maxPr;
        touchMajor = maxMj;

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            Integer slot = pidToSlot.remove(ev.getPointerId(actionIndex));
            if (slot != null) slotDown[slot] = false;
        }

        if (action == MotionEvent.ACTION_UP) {
            maybeTap(ev.getEventTime());
            maxPointers = 0;
        }

        emit();
        return true;
    }

    private void maybeTap(long upTime) {
        if (!prefs.tapClick()) return;
        long dt = upTime - downTime;
        float threshold = Math.max(30f, getWidth() * 0.02f);
        if (dt < 230 && maxMove < threshold) {
            if (maxPointers >= 2 && prefs.twoFingerRight()) flashRight(); else flashLeft();
        }
    }

    private void flashLeft() { tick(); buttonLeft = true; scheduleRelease(); }
    private void flashRight() { tick(); buttonRight = true; scheduleRelease(); }

    private void scheduleRelease() {
        emit();
        if (releaseBtn != null) removeCallbacks(releaseBtn);
        releaseBtn = () -> { buttonLeft = false; buttonRight = false; emit(); };
        postDelayed(releaseBtn, 40);
    }

    private void emit() {
        int count = 0;
        for (int i = 0; i < MAX; i++) if (slotDown[i]) count++;
        activeCount = count;
        HidDescriptor.pack(report, slotDown, slotIdMap(), slotX, slotY, count,
                buttonLeft || forcePressed, buttonRight);
        sink.send(report);
        if (count != lastCount || buttonLeft != lastLeft || buttonRight != lastRight
                || forcePressed != lastForce) {
            lastCount = count; lastLeft = buttonLeft; lastRight = buttonRight; lastForce = forcePressed;
            android.util.Log.i("DroidPad/Touch", "count=" + count + " L=" + buttonLeft
                    + " R=" + buttonRight + " press=" + forcePressed);
        }
        invalidate();
    }

    private int lastCount = -1;
    private boolean lastLeft, lastRight, lastForce;
    private int maxRawPointers;

    @Override protected void onDetachedFromWindow() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        super.onDetachedFromWindow();
    }

    private int[] slotIdMap() {
        if (idMap == null) idMap = new int[MAX];
        for (int i = 0; i < MAX; i++) idMap[i] = i;
        return idMap;
    }
    private int[] idMap;

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static int clampLogical(float v, int size) {
        if (size <= 0) return 0;
        float r = v / size;
        if (r < 0) r = 0;
        if (r > 1) r = 1;
        return Math.round(r * LOGICAL_MAX);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (!debug) return;
        c.drawText("fingers now/raw max: " + liveRaw + " / " + maxRawPointers, 40, 60, label);
        c.drawText(String.format("force %.2f  maj %.1f  bar %+.2f  base %.2f  %s",
                force, touchMajor, pressure - baseline, baseline,
                forcePressed ? "PRESS" : ""), 40, 100, label);
    }

    private int liveRaw;
}
