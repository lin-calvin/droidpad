package dev.calvin.droidpad;

import androidx.appcompat.app.AppCompatActivity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Fullscreen black landscape touchpad. Transport selectable via intent extra
 * "mode": "ble" (default, HOGP) or "classic" (BluetoothHidDevice).
 */
public class PadActivity extends AppCompatActivity {

    private static final String TAG = "DroidPad/Pad";

    private HogServer hog;
    private ClassicHid classic;
    private UdpSender udp;
    private String setStatus2;
    private TextView status;
    private long lastBack;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        goImmersive();
        startForegroundService(new Intent(this, KeepAliveService.class));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        int[] mm = physicalSize01mm();
        String mode = getIntent().getStringExtra("mode");
        String addr = getIntent().getStringExtra("address");
        boolean useClassic = "classic".equals(mode);

        TouchpadView pad;
        if ("udp".equals(mode)) {
            String target = getIntent().getStringExtra("udp");
            int c = target.lastIndexOf(':');
            String host = target.substring(0, c);
            int port = Integer.parseInt(target.substring(c + 1));
            byte[] desc = HidDescriptor.build(mm[0], mm[1]);
            try {
                udp = new UdpSender(host, port);
                udp.hello(desc);
                udp.startHeartbeat(desc);
                setStatus2 = "UDP → " + target + " — sending";
            } catch (Exception e) {
                setStatus2 = "UDP error: " + e;
            }
            final UdpSender u = udp;
            pad = new TouchpadView(this, p -> u != null && u.send(p));
        } else if (useClassic) {
            classic = new ClassicHid(this, mm[0], mm[1], new ClassicHid.Listener() {
                @Override public void onRegistered(boolean registered) {
                    setStatus(registered ? "HID registered — pair the PC, then it connects"
                                         : "HID registration failed");
                }
                @Override public void onConnection(BluetoothDevice device, boolean connected) {
                    setStatus(connected ? ("connected: " + name(device) + " — touchpad ready")
                                        : "waiting for host…");
                    toast(connected ? "connected" : "disconnected");
                }
                @Override public void onError(String msg) { setStatus("error: " + msg); }
            });
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
            if (adapter != null && addr != null) {
                try { classic.setTarget(adapter.getRemoteDevice(addr)); } catch (IllegalArgumentException ignored) {}
            }
            pad = new TouchpadView(this, classic::sendReport);
        } else {
            hog = new HogServer(this, mm[0], mm[1], new HogServer.Listener() {
                @Override public void onConnection(BluetoothDevice device, boolean connected) {
                    setStatus(connected ? ("connected: " + name(device) + " — touchpad ready")
                                        : "advertising — pair from the PC");
                    toast(connected ? "connected" : "disconnected");
                }
                @Override public void onSubscribed(boolean subscribed) {
                    if (subscribed) setStatus("touchpad ready");
                }
                @Override public void onAdvertising(boolean ok, String detail) {
                    if (!ok) setStatus("advertise failed: " + detail);
                }
            });
            pad = new TouchpadView(this, hog::sendReport);
        }

        root.addView(pad, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        pad.post(() -> excludeSystemGestures(pad));

        status = new TextView(this);
        status.setTextColor(0xCCFFFFFF);
        status.setTextSize(15);
        status.setGravity(android.view.Gravity.CENTER);
        status.setText(setStatus2 != null ? setStatus2
                : (useClassic ? "registering HID device…" : "advertising as BLE HID…"));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER);
        root.addView(status, lp);
        status.postDelayed(fadeRunnable, 2500);

        setContentView(root);
        if ("udp".equals(mode)) { /* already started */ }
        else if (useClassic) classic.start(); else hog.start();
    }

    @Override protected void onDestroy() {
        if (udp != null) udp.close();
        if (hog != null) hog.stop();
        if (classic != null) classic.stop();
        stopService(new Intent(this, KeepAliveService.class));
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    private void setStatus(String s) {
        runOnUiThread(() -> {
            if (status == null) return;
            status.removeCallbacks(fadeRunnable);
            status.setText(s);
            status.setAlpha(1f);
            status.postDelayed(fadeRunnable, 2500);
        });
    }

    private final Runnable fadeRunnable = () -> {
        if (status != null) status.animate().alpha(0f).setDuration(400).start();
    };
    private void toast(String s) { runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    private void goImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void excludeSystemGestures(View v) {
        if (Build.VERSION.SDK_INT >= 29) {
            int w = v.getWidth(), h = v.getHeight();
            if (w > 0 && h > 0) {
                v.setSystemGestureExclusionRects(
                        java.util.Collections.singletonList(new android.graphics.Rect(0, 0, w, h)));
            }
        }
    }

    @Override public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) super.onBackPressed();
        else { lastBack = now; toast("press back again to exit"); }
    }

    private int[] physicalSize01mm() {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        float wmm = dm.widthPixels / dm.xdpi * 25.4f;
        float hmm = dm.heightPixels / dm.ydpi * 25.4f;
        if (!(wmm > 30 && wmm < 400)) wmm = dm.widthPixels / 12.0f;
        if (!(hmm > 30 && hmm < 400)) hmm = dm.heightPixels / 12.0f;
        Log.i(TAG, "phys " + wmm + "x" + hmm + "mm (xdpi=" + dm.xdpi + " ydpi=" + dm.ydpi + ")");
        return new int[]{Math.round(wmm * 10f), Math.round(hmm * 10f)};
    }

    private static String name(BluetoothDevice d) {
        try { String n = d.getName(); return n != null ? n : d.getAddress(); }
        catch (SecurityException e) { return d.getAddress(); }
    }
}
