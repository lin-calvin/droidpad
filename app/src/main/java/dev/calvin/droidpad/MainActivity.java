package dev.calvin.droidpad;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERM = 1;

    private final ArrayList<BluetoothDevice> devices = new ArrayList<>();
    private RadioGroup deviceGroup;
    private TextInputEditText udpTarget;
    private MaterialButtonToggleGroup transport;
    private android.widget.TextView btStatus;
    private String selectedAddress;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.inflateMenu(R.menu.main_menu);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            return false;
        });

        udpTarget = findViewById(R.id.udpTarget);
        udpTarget.setText(getPrefs().getString("udp_target", ""));

        deviceGroup = findViewById(R.id.deviceGroup);
        transport = findViewById(R.id.btTransport);
        transport.check(R.id.btnBle);
        btStatus = findViewById(R.id.btStatus);

        findViewById(R.id.btnUdp).setOnClickListener(v -> startUdp());

        MaterialButton btnBt = findViewById(R.id.btnBt);
        btnBt.setOnClickListener(v -> startBluetooth());

        if (!hasPerms()) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE}, REQ_PERM);
        } else {
            loadDevices();
        }
    }

    private android.content.SharedPreferences getPrefs() {
        return getSharedPreferences("droidpad", MODE_PRIVATE);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (hasPerms()) loadDevices();
        else btStatus.setText(R.string.perm_needed);
    }

    private boolean hasPerms() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }

    private void loadDevices() {
        devices.clear();
        deviceGroup.removeAllViews();
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        if (ad == null || !ad.isEnabled()) {
            btStatus.setText(R.string.bt_disabled);
            return;
        }
        try {
            for (BluetoothDevice d : ad.getBondedDevices()) {
                BluetoothClass c = d.getBluetoothClass();
                if (c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.COMPUTER)
                    devices.add(d);
            }
            if (devices.isEmpty()) devices.addAll(ad.getBondedDevices()); // fall back to all
        } catch (SecurityException e) {
            btStatus.setText(R.string.perm_needed);
            return;
        }

        if (devices.isEmpty()) {
            btStatus.setText(R.string.no_devices);
            return;
        }
        btStatus.setText("");
        for (BluetoothDevice d : devices) {
            RadioButton rb = new RadioButton(this);
            String name;
            try { name = d.getName(); } catch (SecurityException e) { name = null; }
            if (name == null || name.isEmpty()) name = d.getAddress();
            rb.setText(name);
            rb.setTag(d.getAddress());
            deviceGroup.addView(rb);
        }
        deviceGroup.setOnCheckedChangeListener((g, id) -> {
            RadioButton rb = g.findViewById(id);
            if (rb != null) selectedAddress = (String) rb.getTag();
        });
    }

    private void startUdp() {
        String t = udpTarget.getText() == null ? "" : udpTarget.getText().toString().trim();
        if (!t.contains(":")) {
            Toast.makeText(this, "Enter PC ip:port", Toast.LENGTH_SHORT).show();
            return;
        }
        getPrefs().edit().putString("udp_target", t).apply();
        launchPad("udp", t, null);
    }

    private void startBluetooth() {
        String mode = transport.getCheckedButtonId() == R.id.btnClassic ? "classic" : "ble";
        launchPad(mode, null, selectedAddress);
    }

    private void launchPad(String mode, String udp, String address) {
        requestBatteryExemption();
        Intent i = new Intent(this, PadActivity.class);
        i.putExtra("mode", mode);
        if (udp != null) i.putExtra("udp", udp);
        if (address != null) i.putExtra("address", address);
        startActivity(i);
    }

    /** MIUI freezes restricted apps, which drops GATT/UDP. Ask to be exempt. */
    private void requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) return;
        android.os.PowerManager pm =
                (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception ignored) {}
    }
}
