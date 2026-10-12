package dev.calvin.droidpad;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

public class SettingsActivity extends AppCompatActivity {

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationOnClickListener(v -> finish());

        Prefs p = new Prefs(this);
        android.content.SharedPreferences sp = p.raw();
        android.content.SharedPreferences.Editor ed = sp.edit();

        MaterialSwitch swHaptics = findViewById(R.id.swHaptics);
        Slider slStrength = findViewById(R.id.slStrength);
        MaterialSwitch swTap = findViewById(R.id.swTap);
        MaterialSwitch swTwo = findViewById(R.id.swTwo);
        MaterialSwitch swForce = findViewById(R.id.swForce);
        MaterialSwitch swLanding = findViewById(R.id.swLanding);
        Slider slThresh = findViewById(R.id.slThresh);

        swHaptics.setChecked(p.haptics());
        slStrength.setValue(p.hapticStrength());
        swTap.setChecked(p.tapClick());
        swTwo.setChecked(p.twoFingerRight());
        swForce.setChecked(p.forcePress());
        swLanding.setChecked(p.blockLanding());
        slThresh.setValue(snapForce(p.forceThreshold()));

        swHaptics.setOnCheckedChangeListener((v, c) -> ed.putBoolean("haptics", c).apply());
        slStrength.addOnChangeListener((s, val, fromUser) -> {
            if (fromUser) ed.putInt("haptic_strength", (int) val).apply();
        });
        swTap.setOnCheckedChangeListener((v, c) -> ed.putBoolean("tap_click", c).apply());
        swTwo.setOnCheckedChangeListener((v, c) -> ed.putBoolean("two_finger_right", c).apply());
        swForce.setOnCheckedChangeListener((v, c) -> ed.putBoolean("force_press", c).apply());
        swLanding.setOnCheckedChangeListener((v, c) -> ed.putBoolean("block_landing", c).apply());
        slThresh.addOnChangeListener((s, val, fromUser) -> {
            if (fromUser) ed.putFloat("force_threshold", val).apply();
        });

        findViewById(R.id.btnAdapt).setOnClickListener(v ->
                startActivity(new Intent(this, CalibrateActivity.class)));

        findViewById(R.id.btnBattery).setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                catch (Exception ignored) {}
            }
        });

        findViewById(R.id.btnAutostart).setOnClickListener(v -> {
            try {
                Intent i = new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"));
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(this, "Not a MIUI device", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        Prefs p = new Prefs(this);
        ((Slider) findViewById(R.id.slThresh)).setValue(snapForce(p.forceThreshold()));
        ((MaterialSwitch) findViewById(R.id.swForce)).setChecked(p.forcePress());
    }

    /** Slider uses valueFrom=0.2, stepSize=0.05 — snap arbitrary saved values onto that grid. */
    private static float snapForce(float v) {
        float r = 0.2f + Math.round((v - 0.2f) / 0.05f) * 0.05f;
        return Math.max(0.2f, Math.min(1.0f, r));
    }
}
