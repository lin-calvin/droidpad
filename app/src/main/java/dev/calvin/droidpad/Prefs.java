package dev.calvin.droidpad;

import android.content.Context;
import android.content.SharedPreferences;

/** App preferences (SharedPreferences "droidpad"). */
public final class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("droidpad", Context.MODE_PRIVATE);
    }

    public boolean haptics()        { return sp.getBoolean("haptics", true); }
    public int hapticStrength()     { return sp.getInt("haptic_strength", 255); }
    public boolean tapClick()       { return sp.getBoolean("tap_click", true); }
    public boolean twoFingerRight() { return sp.getBoolean("two_finger_right", true); }
    public boolean forcePress()     { return sp.getBoolean("force_press", false); }
    public float forceThreshold()   { return sp.getFloat("force_threshold", 0.60f); }
    public boolean blockLanding()   { return sp.getBoolean("block_landing", true); }

    public SharedPreferences raw()  { return sp; }
}
