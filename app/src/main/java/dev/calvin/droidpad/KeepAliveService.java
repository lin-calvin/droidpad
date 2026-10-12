package dev.calvin.droidpad;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Minimal foreground service whose only job is to keep the process (and thus the
 * HID registration) alive when the user leaves PadActivity to pair from Settings.
 * Without it, MIUI kills the app and the HID SDP record disappears.
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL = "droidpad";
    private static final int ID = 1;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "DroidPad",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("DroidPad")
                .setContentText("Touchpad active")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
        startForeground(ID, n);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
