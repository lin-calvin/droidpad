package dev.calvin.droidpad;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bluetooth Classic HID Device profile. The system owns the SDP record and the
 * connection/pairing state; the app only registers its report descriptor and
 * pushes input reports. No advertising on our side.
 */
public final class ClassicHid {

    private static final String TAG = "DroidPad/ClassicHid";

    public interface Listener {
        /** app registered with the stack */
        void onRegistered(boolean registered);
        void onConnection(BluetoothDevice device, boolean connected);
        void onError(String msg);
    }

    private final Context ctx;
    private final Listener listener;
    private final byte[] descriptor;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private BluetoothHidDevice hid;
    private BluetoothAdapter adapter;
    private BluetoothDevice target;
    private BluetoothDevice connected;
    private boolean registered;

    public ClassicHid(Context ctx, int physW01mm, int physH01mm, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.descriptor = HidDescriptor.build(physW01mm, physH01mm);
    }

    public boolean isConnected() { return connected != null; }

    public void setTarget(BluetoothDevice device) { this.target = device; }

    public void start() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            listener.onError("bluetooth disabled");
            return;
        }
        adapter.getProfileProxy(ctx, serviceListener, BluetoothProfile.HID_DEVICE);
    }

    private final BluetoothProfile.ServiceListener serviceListener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            hid = (BluetoothHidDevice) proxy;
            String name;
            try { name = adapter.getName(); } catch (SecurityException e) { name = "DroidPad"; }
            BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                    name == null ? "DroidPad" : name,
                    "DroidPad touchpad",
                    "DroidPad",
                    (byte) 0x80,               // subclass: pointing device
                    descriptor);
            boolean ok = hid.registerApp(sdp, null, null, executor, callback);
            Log.i(TAG, "registerApp=" + ok);
            if (!ok) listener.onError("registerApp failed");
        }

        @Override public void onServiceDisconnected(int profile) {
            hid = null;
            registered = false;
        }
    };

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean isRegistered) {
            registered = isRegistered;
            Log.i(TAG, "onAppStatusChanged registered=" + isRegistered);
            listener.onRegistered(isRegistered);
            if (isRegistered && target != null && !isConnected()) {
                try { hid.connect(target); } catch (SecurityException e) { listener.onError("connect denied"); }
            }
        }

        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            boolean on = state == BluetoothProfile.STATE_CONNECTED;
            connected = on ? device : null;
            Log.i(TAG, "conn state " + state + " " + device);
            listener.onConnection(device, on);
        }

        @Override public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            // Host asks for the current report on connect; answer with a neutral report.
            byte[] data = new byte[Math.max(1, Math.min(bufferSize, HidDescriptor.REPORT_BYTES))];
            try { hid.replyReport(device, type, id, data); } catch (Exception e) { /* ignore */ }
        }

        @Override public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            Log.i(TAG, "onSetReport type=" + type + " id=" + id);
        }

        @Override public void onSetProtocol(BluetoothDevice device, byte protocol) {
            Log.i(TAG, "onSetProtocol=" + protocol);
        }

        @Override public void onInterruptData(BluetoothDevice device, byte reportId, byte[] data) {
            // no-op
        }

        @Override public void onVirtualCableUnplug(BluetoothDevice device) {
            connected = null;
            listener.onConnection(device, false);
        }
    };

    /** Send an input report (payload excludes the report id). */
    public boolean sendReport(byte[] payload) {
        BluetoothDevice d = connected;
        BluetoothHidDevice h = hid;
        if (d == null || h == null) return false;
        try {
            return h.sendReport(d, HidDescriptor.REPORT_ID, payload);
        } catch (SecurityException e) {
            return false;
        }
    }

    public void stop() {
        try {
            if (hid != null) {
                if (connected != null) hid.disconnect(connected);
                if (registered) hid.unregisterApp();
            }
        } catch (Exception ignored) {}
        if (adapter != null && hid != null) {
            try { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid); } catch (Exception ignored) {}
        }
        executor.shutdown();
        hid = null;
        connected = null;
        registered = false;
    }
}
