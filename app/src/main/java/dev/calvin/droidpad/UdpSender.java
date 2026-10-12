package dev.calvin.droidpad;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends the HID report descriptor once (handshake) and then packed input reports
 * over UDP to a PC-side daemon that feeds them into /dev/uhid.
 *
 * Wire format (one datagram each):
 *   0x01 HELLO: [1][u16 descriptorLen LE][descriptor...]
 *   0x02 INPUT: [2][report payload...]   (report id implied = 1)
 *
 * All socket I/O happens on a private single thread: DatagramSocket.send on the
 * main thread throws NetworkOnMainThreadException. Single-thread == ordering.
 */
public final class UdpSender {

    private static final String TAG = "DroidPad/Udp";

    private final DatagramSocket sock;
    private final InetAddress addr;
    private final int port;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "droidpad-udp");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed;
    private Thread heartbeat;

    public UdpSender(String host, int port) throws Exception {
        this.sock = new DatagramSocket();
        this.addr = InetAddress.getByName(host);
        this.port = port;
        Log.i(TAG, "-> " + addr + ":" + port);
    }

    public void hello(byte[] descriptor) {
        byte[] p = new byte[3 + descriptor.length];
        p[0] = 0x01;
        p[1] = (byte) (descriptor.length & 0xFF);
        p[2] = (byte) ((descriptor.length >> 8) & 0xFF);
        System.arraycopy(descriptor, 0, p, 3, descriptor.length);
        raw(p);
    }

    /** Keep announcing the descriptor so a daemon started later still creates the device. */
    public void startHeartbeat(byte[] descriptor) {
        if (heartbeat != null) return;
        heartbeat = new Thread(() -> {
            while (!closed) {
                hello(descriptor);
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }, "droidpad-udp-hello");
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    /** payload is the packed report (no report id). */
    public boolean send(byte[] payload) {
        byte[] p = new byte[1 + payload.length];
        p[0] = 0x02;
        System.arraycopy(payload, 0, p, 1, payload.length);
        return raw(p);
    }

    private boolean raw(byte[] data) {
        if (closed) return false;
        try {
            io.execute(() -> {
                try {
                    sock.send(new DatagramPacket(data, data.length, addr, port));
                } catch (Exception e) {
                    Log.w(TAG, "send failed: " + e);
                }
            });
            return true;
        } catch (Exception e) {
            Log.w(TAG, "enqueue failed: " + e);
            return false;
        }
    }

    public void close() {
        closed = true;
        io.shutdownNow();
        try { sock.close(); } catch (Exception ignored) {}
    }
}
