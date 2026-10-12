package dev.calvin.droidpad;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.UUID;

/**
 * BLE HID-over-GATT (HOGP) peripheral: exposes the HID service (0x1812) with a
 * Report Map built by {@link HidDescriptor}, advertises it, and pushes input
 * reports as notifications.
 */
public final class HogServer {

    private static final String TAG = "DroidPad/Hog";

    private static final UUID U_GAP         = uuid16(0x1800);
    private static final UUID U_GAP_NAME    = uuid16(0x2A00);
    private static final UUID U_HID         = uuid16(0x1812);
    private static final UUID U_HID_INFO    = uuid16(0x2A4A);
    private static final UUID U_REPORT_MAP  = uuid16(0x2A4B);
    private static final UUID U_CTRL_POINT  = uuid16(0x2A4C);
    private static final UUID U_REPORT      = uuid16(0x2A4D);
    private static final UUID U_PROTO_MODE  = uuid16(0x2A4E);
    private static final UUID U_REPORT_REF  = uuid16(0x2908);
    private static final UUID U_CCC         = uuid16(0x2902);

    private static UUID uuid16(int u) {
        return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", u));
    }

    public interface Listener {
        void onConnection(BluetoothDevice device, boolean connected);
        void onSubscribed(boolean subscribed);
        void onAdvertising(boolean ok, String detail);
    }

    private final Context ctx;
    private final Listener listener;
    private final byte[] descriptor;

    private BluetoothGattServer server;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattCharacteristic inputReport;
    private BluetoothDevice connectedDevice;
    private volatile boolean subscribed;
    private volatile int mtu = 23;
    private boolean started;

    public HogServer(Context ctx, int physW01mm, int physH01mm, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.descriptor = HidDescriptor.build(physW01mm, physH01mm);
    }

    public boolean isSubscribed() { return subscribed; }
    public BluetoothDevice connectedDevice() { return connectedDevice; }

    public void start() {
        if (started) return;
        started = true;

        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            listener.onAdvertising(false, "bluetooth disabled");
            return;
        }

        server = bm.openGattServer(ctx, gattCb);
        if (server == null) {
            listener.onAdvertising(false, "openGattServer failed");
            return;
        }
        for (BluetoothGattService svc : buildServices()) {
            server.addService(svc);
        }

        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            listener.onAdvertising(false, "no LE advertiser");
            return;
        }
        startAdvertising();
    }

    private void startAdvertising() {
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(U_HID))
                .setIncludeDeviceName(false)
                .build();
        AdvertiseData scanResp = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();
        try {
            advertiser.startAdvertising(settings, data, scanResp, advCb);
        } catch (SecurityException e) {
            listener.onAdvertising(false, "no advertise permission");
        }
    }

    public void stop() {
        started = false;
        subscribed = false;
        try { if (advertiser != null) advertiser.stopAdvertising(advCb); } catch (Exception ignored) {}
        try { if (server != null) server.clearServices(); } catch (Exception ignored) {}
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
        advertiser = null;
        inputReport = null;
        connectedDevice = null;
    }

    /** Send an input report. Returns false if not connected/subscribed. */
    public boolean sendReport(byte[] payload) {
        BluetoothGattServer s = server;
        BluetoothGattCharacteristic c = inputReport;
        BluetoothDevice d = connectedDevice;
        if (s == null || c == null || d == null || !subscribed) return false;
        if (payload.length + 3 > mtu) {
            Log.w(TAG, "report " + payload.length + "B exceeds MTU " + mtu);
        }
        c.setValue(payload);
        return s.notifyCharacteristicChanged(d, c, false);
    }

    // ---- services -------------------------------------------------------

    private java.util.List<BluetoothGattService> buildServices() {
        java.util.List<BluetoothGattService> out = new java.util.ArrayList<>();

        BluetoothGattService gap = new BluetoothGattService(U_GAP,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic name = new BluetoothGattCharacteristic(U_GAP_NAME,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        name.setValue("DroidPad".getBytes());
        gap.addCharacteristic(name);
        out.add(gap);

        BluetoothGattService hid = new BluetoothGattService(U_HID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        BluetoothGattCharacteristic info = new BluetoothGattCharacteristic(U_HID_INFO,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        info.setValue(new byte[]{0x11, 0x01, 0x00, 0x02}); // bcdHID 1.11, country 0, flags normally-connectable
        hid.addCharacteristic(info);

        BluetoothGattCharacteristic reportMap = new BluetoothGattCharacteristic(U_REPORT_MAP,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        reportMap.setValue(descriptor);
        hid.addCharacteristic(reportMap);

        BluetoothGattCharacteristic ctrlPoint = new BluetoothGattCharacteristic(U_CTRL_POINT,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        hid.addCharacteristic(ctrlPoint);

        BluetoothGattCharacteristic protoMode = new BluetoothGattCharacteristic(U_PROTO_MODE,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
        protoMode.setValue(new byte[]{0x01}); // report protocol
        hid.addCharacteristic(protoMode);

        inputReport = new BluetoothGattCharacteristic(U_REPORT,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        inputReport.setValue(new byte[HidDescriptor.REPORT_BYTES]);
        BluetoothGattDescriptor ref = new BluetoothGattDescriptor(U_REPORT_REF,
                BluetoothGattDescriptor.PERMISSION_READ);
        ref.setValue(new byte[]{(byte) HidDescriptor.REPORT_ID, 0x01}); // id, type=input
        inputReport.addDescriptor(ref);
        BluetoothGattDescriptor ccc = new BluetoothGattDescriptor(U_CCC,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        ccc.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        inputReport.addDescriptor(ccc);
        hid.addCharacteristic(inputReport);

        out.add(hid);
        return out;
    }

    // ---- callbacks ------------------------------------------------------

    private final AdvertiseCallback advCb = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(TAG, "advertising started");
            listener.onAdvertising(true, null);
        }
        @Override public void onStartFailure(int errorCode) {
            Log.w(TAG, "advertising failed: " + errorCode);
            listener.onAdvertising(false, "advertise error " + errorCode);
        }
    };

    private final BluetoothGattServerCallback gattCb = new BluetoothGattServerCallback() {
        @Override public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device;
                Log.i(TAG, "connected " + device);
            } else {
                if (device != null && device.equals(connectedDevice)) {
                    connectedDevice = null;
                    subscribed = false;
                    listener.onSubscribed(false);
                }
                Log.i(TAG, "disconnected " + device);
                if (advertiser != null) {
                    try { advertiser.stopAdvertising(advCb); } catch (Exception ignored) {}
                    startAdvertising();
                }
            }
            listener.onConnection(device, newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED);
        }

        @Override public void onMtuChanged(BluetoothDevice device, int mtu) {
            HogServer.this.mtu = mtu;
            Log.i(TAG, "mtu=" + mtu);
        }

        @Override public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                                                          int offset, BluetoothGattCharacteristic chr) {
            byte[] value = chr.getValue();
            Log.i(TAG, "read req " + chr.getUuid() + " off=" + offset + " vlen=" + (value == null ? -1 : value.length));
            if (value == null) value = new byte[0];
            if (offset > value.length) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
                return;
            }
            byte[] slice = new byte[value.length - offset];
            System.arraycopy(value, offset, slice, 0, slice.length);
            server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice);
        }

        @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                           BluetoothGattCharacteristic chr, boolean preparedWrite,
                                                           boolean responseNeeded, int offset, byte[] value) {
            Log.i(TAG, "write " + chr.getUuid() + " = " + toHex(value));
            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
        }

        @Override public void onDescriptorReadRequest(BluetoothDevice device, int requestId,
                                                      int offset, BluetoothGattDescriptor dsc) {
            Log.i(TAG, "desc read " + dsc.getUuid() + " off=" + offset);
            byte[] value = dsc.getValue();
            if (value == null) value = new byte[0];
            if (offset > value.length) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
                return;
            }
            byte[] slice = new byte[value.length - offset];
            System.arraycopy(value, offset, slice, 0, slice.length);
            server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice);
        }

        @Override public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                                       BluetoothGattDescriptor dsc, boolean preparedWrite,
                                                       boolean responseNeeded, int offset, byte[] value) {
            Log.i(TAG, "descriptor write " + dsc.getUuid() + " = " + toHex(value));
            boolean enable = value != null && value.length > 0 && value[0] != 0x00;
            if (U_CCC.equals(dsc.getUuid())) {
                dsc.setValue(value);
                subscribed = enable;
                listener.onSubscribed(enable);
            } else {
                dsc.setValue(value);
            }
            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
        }
    };

    private static String toHex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
