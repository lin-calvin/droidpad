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
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Mini touchpad used on the Settings screen to feel the current tap-buzz and
 * force-press-buzz, and to eyeball the force metric while tuning the threshold.
 * Does not send HID — display + haptics only.
 */
public final class ForceTestView extends View implements SensorEventListener {

    public interface PressListener { void onPress(float peakForce); }

    private static final float MAJ_MIN = 4.8f, MAJ_MAX = 11.0f;
    private static final float AREA_W = 0.65f, BARO_W = 0.35f, BARO_REF = 0.12f;

    private final Prefs prefs;
    private final Vibrator vibrator;
    private final SensorManager sm;
    private final Sensor baro;

    private PressListener listener;
    private float peak;
    private boolean interactive = true;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float pressure = Float.NaN, baseline = Float.NaN;
    private float touchMajor, force;
    private boolean down, pressed;
    private long downTime;
    private long downSince;
    private float downX, downY, maxMove;

    public ForceTestView(Context c) { this(c, null); }

    public ForceTestView(Context c, AttributeSet a) {
        super(c, a);
        prefs = new Prefs(c);
        Vibrator v = null;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm != null) v = vm.getDefaultVibrator();
        } else {
            v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        }
        vibrator = v;
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        baro = sm != null ? sm.getDefaultSensor(Sensor.TYPE_PRESSURE) : null;
        if (baro != null) sm.registerListener(this, baro, SensorManager.SENSOR_DELAY_GAME);

        bg.setColor(0xFF1E211D);
        txt.setColor(0xFFCCCCCC);
        txt.setTextSize(34f);
        track.setColor(0xFF3A3F38);
        fill.setColor(0xFF7FDBCA);
    }

    private void tick() {
        if (!interactive || !prefs.haptics() || vibrator == null || !vibrator.hasVibrator()) return;
        try { vibrator.vibrate(VibrationEffect.createOneShot(12, prefs.hapticStrength())); }
        catch (Exception ignored) {}
    }

    private void click() {
        if (!interactive || !prefs.haptics() || vibrator == null || !vibrator.hasVibrator()) return;
        try {
            int a = prefs.hapticStrength();
            vibrator.vibrate(VibrationEffect.createWaveform(
                    new long[]{0, 10, 12, 14}, new int[]{0, a, 0, a}, -1));
        } catch (Exception ignored) {}
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            down = true;
            downTime = ev.getEventTime();
            downSince = android.os.SystemClock.uptimeMillis();
            downX = ev.getX(); downY = ev.getY(); maxMove = 0;
        }
        touchMajor = 0;
        for (int i = 0; i < ev.getPointerCount(); i++) {
            touchMajor = Math.max(touchMajor, ev.getTouchMajor(i));
            maxMove = Math.max(maxMove, (float) Math.hypot(ev.getX(i) - downX, ev.getY(i) - downY));
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            down = false; pressed = false;
            if (ev.getEventTime() - downTime < 230 && maxMove < 30) tick();  // tap buzz
            float p = peak; peak = 0;
            if (listener != null && p > 0) listener.onPress(p);
        }
        invalidate();
        return true;
    }

    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() != Sensor.TYPE_PRESSURE) return;
        pressure = e.values[0];
        if (Float.isNaN(baseline)) { baseline = pressure; return; }
        long now = android.os.SystemClock.uptimeMillis();
        boolean settling = prefs.blockLanding() && down && (now - downSince) < 300;
        if (!down && !pressed) baseline += 0.02f * (pressure - baseline);
        else if (settling) baseline = pressure;   // ignore the finger-landing transient
        float delta = pressure - baseline;
        force = AREA_W * clamp01((touchMajor - MAJ_MIN) / (MAJ_MAX - MAJ_MIN))
                + BARO_W * clamp01(delta / BARO_REF);
        if (prefs.forcePress() && down && !settling) {
            float th = prefs.forceThreshold();
            if (!pressed && force > th) { pressed = true; click(); }
            else if (pressed && force < th - 0.15f) pressed = false;
        }
        if (down) peak = Math.max(peak, force);
        invalidate();
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float pad = 8f, w = getWidth(), h = getHeight(), r = 24f;
        c.drawRoundRect(pad, pad, w - pad, h - pad, r, r, bg);

        c.drawText("Tap = buzz · press hard = click", pad + 24f, pad + 44f, txt);
        c.drawText(String.format("force %.2f   %s", force, pressed ? "PRESS" : ""),
                pad + 24f, pad + 88f, txt);

        float bx = pad + 24f, by = h - pad - 40f, bw = w - 2 * pad - 48f, bh = 16f;
        c.drawRoundRect(bx, by, bx + bw, by + bh, bh / 2, bh / 2, track);
        float th = prefs.forceThreshold();
        // threshold marker
        c.drawRect(bx + bw * clamp01(th) - 2f, by - 6f, bx + bw * clamp01(th) + 2f, by + bh + 6f, fill);
        // value
        c.drawRoundRect(bx, by, bx + bw * clamp01(force), by + bh, bh / 2, bh / 2, fill);
    }

    @Override protected void onDetachedFromWindow() {
        if (sm != null) sm.unregisterListener(this);
        super.onDetachedFromWindow();
    }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    public float getForce() { return force; }
    public boolean isDown() { return down; }
    public void setPressListener(PressListener l) { listener = l; }
    public void setInteractive(boolean b) { interactive = b; }
}
