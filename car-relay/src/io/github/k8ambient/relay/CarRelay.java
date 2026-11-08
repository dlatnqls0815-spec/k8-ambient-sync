package io.github.k8ambient.relay;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Looper;
import io.github.k8ambient.core.RelayProtocol;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * K8 car side of plan B, run by a NEMESIS service with app_process (API 19).
 *
 * <p>Reads the OEM SystemService SML status lines from the system log (read-only) and serves the
 * latest raw status over a classic Bluetooth RFCOMM server to one bonded phone. It sends no
 * vehicle, MICOM or lighting commands and never changes Bluetooth state.
 */
public final class CarRelay {
    private static final long PING_MS = 10000, RETRY_MS = 5000;
    private final Object lock = new Object();
    private byte[] latest;
    private long version;

    public static void main(String[] args) {
        Looper.prepareMainLooper();
        final CarRelay relay = new CarRelay();
        start(new Runnable() { public void run() { relay.readLog(); } }, "sml-log");
        start(new Runnable() { public void run() { relay.serve(); } }, "rfcomm");
        log("started " + RelayProtocol.HELLO);
        Looper.loop();
    }

    private static void start(Runnable body, String name) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
    }

    static void log(String text) {
        System.out.println(System.currentTimeMillis() / 1000 + " " + text);
        System.out.flush();
    }

    private void readLog() {
        while (true) {
            Process process = null;
            try {
                process = new ProcessBuilder("logcat", "-b", "system", "-v", "brief", "-s", "SystemService:D")
                        .redirectErrorStream(true).start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    byte[] status = RelayProtocol.parseLogLine(line);
                    if (status != null) publish(status);
                }
                log("logcat ended");
            } catch (IOException error) {
                log("logcat error " + error.getClass().getSimpleName());
            } finally {
                if (process != null) process.destroy();
            }
            sleep(2000);
        }
    }

    private void publish(byte[] status) {
        synchronized (lock) {
            if (latest != null && Arrays.equals(latest, status)) return;
            latest = status;
            version++;
            lock.notifyAll();
        }
        log("state " + RelayProtocol.encodeStatus(status));
    }

    private void serve() {
        while (true) {
            BluetoothServerSocket server = null;
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null || !adapter.isEnabled()) {
                    sleep(RETRY_MS);
                    continue;
                }
                server = adapter.listenUsingRfcommWithServiceRecord("K8 Ambient Relay", RelayProtocol.SERVICE_UUID);
                log("listening");
                while (true) {
                    BluetoothSocket client = server.accept();
                    log("phone connected");
                    try {
                        stream(client.getOutputStream());
                    } catch (IOException error) {
                        log("phone disconnected");
                    } finally {
                        try { client.close(); } catch (IOException ignored) { }
                    }
                }
            } catch (IOException error) {
                log("server error " + error.getClass().getSimpleName());
            } catch (RuntimeException error) {
                log("server error " + error.getClass().getSimpleName());
            } finally {
                if (server != null) try { server.close(); } catch (IOException ignored) { }
            }
            sleep(RETRY_MS);
        }
    }

    private void stream(OutputStream output) throws IOException {
        write(output, RelayProtocol.HELLO);
        long sent = -1;
        while (true) {
            byte[] status;
            long current;
            synchronized (lock) {
                if (latest == null || version == sent) {
                    try { lock.wait(PING_MS); } catch (InterruptedException e) { throw new IOException("interrupted"); }
                }
                status = latest;
                current = version;
            }
            if (status != null && current != sent) {
                write(output, RelayProtocol.encodeStatus(status));
                sent = current;
            } else {
                write(output, RelayProtocol.PING);
            }
        }
    }

    private static void write(OutputStream output, String line) throws IOException {
        output.write((line + "\n").getBytes("US-ASCII"));
        output.flush();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
