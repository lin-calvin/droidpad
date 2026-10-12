package dev.calvin.droidpad;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.os.Vibrator;
import android.os.VibratorManager;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;

/**
 * Fullscreen calibration. Each round: put your finger on → 3s countdown → a 1.5s
 * window where you press to the force you want; we record the peak in that window.
 * After N rounds the threshold is the average peak (snapped).
 */
public class CalibrateActivity extends AppCompatActivity {

    private static final int N = 3;
    private static final long COUNT_MS = 3000, WINDOW_MS = 1500;

    private enum State { WAIT_DOWN, COUNT, WINDOW, WAIT_UP }

    private ForceTestView view;
    private TextView instr, prog;
    private MaterialButton done;
    private Vibrator vibrator;

    private final ArrayList<Float> maxes = new ArrayList<>();
    private int round;
    private State state = State.WAIT_DOWN;
    private long t0;
    private float windowMax;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() { tick(); h.postDelayed(this, 50); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_calibrate);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        goImmersive();

        view = findViewById(R.id.calView);
        instr = findViewById(R.id.tvInstr);
        prog = findViewById(R.id.tvProg);
        done = findViewById(R.id.btnDone);
        done.setOnClickListener(v -> finish());
        view.setInteractive(false);

        vibrator = getVibrator();

        state = State.WAIT_DOWN;
        h.post(poll);
    }

    private void tick() {
        long now = android.os.SystemClock.uptimeMillis();
        switch (state) {
            case WAIT_DOWN:
                instr.setText("Put your finger on the pad\nand hold it");
                prog.setText("Round " + (round + 1) + " / " + N);
                if (view.isDown()) { state = State.COUNT; t0 = now; }
                break;
            case COUNT: {
                long left = COUNT_MS - (now - t0);
                int s = (int) Math.ceil(left / 1000.0);
                instr.setText("Get ready…\n" + Math.max(0, s));
                if (left <= 0) { state = State.WINDOW; t0 = now; windowMax = 0; buzz(30, 200); }
                break;
            }
            case WINDOW:
                windowMax = Math.max(windowMax, view.getForce());
                instr.setText("PRESS NOW");
                prog.setText(String.format("%.2f", windowMax));
                if (now - t0 >= WINDOW_MS) {
                    maxes.add(windowMax);
                    round++;
                    buzz(40, 255);
                    state = State.WAIT_UP;
                }
                break;
            case WAIT_UP:
                instr.setText("Lift your finger");
                if (!view.isDown()) {
                    if (round >= N) { result(); } else { state = State.WAIT_DOWN; }
                }
                break;
        }
    }

    private void result() {
        h.removeCallbacks(poll);
        float sum = 0;
        for (float p : maxes) sum += p;
        float mean = sum / maxes.size();
        float thr = clamp(0.2f + Math.round((mean - 0.2f) / 0.05f) * 0.05f, 0.2f, 0.9f);
        new Prefs(this).raw().edit()
                .putFloat("force_threshold", thr)
                .putBoolean("force_press", true)
                .apply();
        instr.setText(String.format("Click at force %.2f\n(your avg %.2f)", thr, mean));
        prog.setText("");
        buzz(60, 255);
        done.setVisibility(View.VISIBLE);
        h.postDelayed(this::finish, 2500);
    }

    private Vibrator getVibrator() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
            return vm != null ? vm.getDefaultVibrator() : null;
        }
        return (Vibrator) getSystemService(VIBRATOR_SERVICE);
    }

    private void buzz(int ms, int amp) {
        if (!new Prefs(this).haptics() || vibrator == null || !vibrator.hasVibrator()) return;
        try { vibrator.vibrate(VibrationEffect.createOneShot(ms, amp)); } catch (Exception ignored) {}
    }

    private void goImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
