package io.github.k8ambient.relay;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Looper;
import io.github.k8ambient.core.RelayProtocol;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * K8 car side of plan B, run by a NEMESIS service with app_process (API 19).
 *
 * <p>Reads the OEM SystemService SML status lines from the system log (read-only) and serves the
 * latest raw status over a classic Bluetooth RFCOMM server to one bonded phone. It sends no
 * vehicle, MICOM or lighting commands and never changes Bluetooth state.
 *
 * <p>Since 0.2.0 it also records, read-only, the head unit's own log lines that may explain the
 * factory overspeed red/blink ambient effect (navigation cluster info, MICOM frames, SML, speed and
 * camera lines) into a rotating file next to the relay log, for offline analysis.
 */
public final class CarRelay {
    private static final long PING_MS = 10000, RETRY_MS = 5000;
    static final String RECORD_DIR = "/data/nemesis/logs/k8ambient.relay";
    /** About 20 MB/hour parked; 6 x 16 MB keeps a few hours while using ~13 % of the free /data. */
    private static final long RECORD_FILE_BYTES = 16L * 1024 * 1024;
    private static final int RECORD_FILES = 6;
    /** Tags recorded in full ("-v time" lines: date, time, then "X/Tag"). */
    static final Pattern RECORD_TAGS = Pattern.compile("^\\S+ \\S+ [VDIWEF]/(ClusterService|NavigationCluster|SystemService|MicomD|Navi|CoNA)\\b.*");
    /** Other lines recorded when they mention one of these words. */
    static final Pattern RECORD_WORDS = Pattern.compile("(?i).*(speed|trap|camera|alarm|warning|blink|mood|ambient|sml).*");
    /** High-volume lines with no value for this question. */
    static final Pattern RECORD_SKIP = Pattern.compile(".*(getCANSTatus|getCANStatus|GetSpeedLimitForISLW|rearcamera|TIME_TICK|FrameCluster::updateTurn).*");
    private final Object lock = new Object();
    private byte[] latest;
    private long version;
    /** Navigation speed camera alert (factory light blinks red); forwarded so the phone skips fades. */
    private boolean alert;
    private long alertVersion;

    public static void main(String[] args) {
        Looper.prepareMainLooper();
        final CarRelay relay = new CarRelay();
        start(new Runnable() { public void run() { relay.readLog(); } }, "sml-log");
        start(new Runnable() { public void run() { relay.serve(); } }, "rfcomm");
        start(new Runnable() { public void run() { record(); } }, "recorder");
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
                // LightService (vehicle settings app) logs the navigation camera alert in the main buffer.
                process = new ProcessBuilder("logcat", "-b", "main", "-b", "system", "-v", "brief", "-s",
                        "SystemService:D", "LightService:D")
                        .redirectErrorStream(true).start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    byte[] status = RelayProtocol.parseLogLine(line);
                    if (status != null) publish(status);
                    Boolean camera = RelayProtocol.parseAlertLogLine(line);
                    if (camera != null) publishAlert(camera.booleanValue());
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

    /** True when a "logcat -v time" line belongs in the overspeed recording. */
    static boolean shouldRecord(String line) {
        if (line == null || RECORD_SKIP.matcher(line).matches()) return false;
        return RECORD_TAGS.matcher(line).matches() || RECORD_WORDS.matcher(line).matches();
    }

    private static void record() {
        File dir = new File(RECORD_DIR);
        while (true) {
            Process process = null;
            FileOutputStream out = null;
            try {
                // API 19 logcat has no -T: a restart re-records the current buffer once (harmless duplicates).
                process = new ProcessBuilder("logcat", "-v", "time").redirectErrorStream(true).start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
                File file = new File(dir, "overspeed.log");
                out = new FileOutputStream(file, true);
                long size = file.length();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!shouldRecord(line)) continue;
                    byte[] bytes = (line + "\n").getBytes("UTF-8");
                    if (size + bytes.length > RECORD_FILE_BYTES) {
                        out.close();
                        rotate(dir);
                        out = new FileOutputStream(file, false);
                        size = 0;
                    }
                    out.write(bytes);
                    size += bytes.length;
                }
                log("recorder logcat ended");
            } catch (IOException error) {
                log("recorder error " + error.getClass().getSimpleName());
            } finally {
                if (out != null) try { out.close(); } catch (IOException ignored) { }
                if (process != null) process.destroy();
            }
            sleep(5000);
        }
    }

    private static void rotate(File dir) {
        new File(dir, "overspeed." + (RECORD_FILES - 1) + ".log").delete();
        for (int i = RECORD_FILES - 2; i >= 1; i--) {
            new File(dir, "overspeed." + i + ".log").renameTo(new File(dir, "overspeed." + (i + 1) + ".log"));
        }
        new File(dir, "overspeed.log").renameTo(new File(dir, "overspeed.1.log"));
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

    private void publishAlert(boolean active) {
        synchronized (lock) {
            if (alertVersion > 0 && alert == active) return;
            alert = active;
            alertVersion++;
            lock.notifyAll();
        }
        log("alert " + (active ? 1 : 0));
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
        long sent = -1, sentAlert = 0;
        while (true) {
            byte[] status;
            long current, currentAlert;
            boolean active;
            synchronized (lock) {
                if ((latest == null || version == sent) && alertVersion == sentAlert) {
                    try { lock.wait(PING_MS); } catch (InterruptedException e) { throw new IOException("interrupted"); }
                }
                status = latest;
                current = version;
                active = alert;
                currentAlert = alertVersion;
            }
            boolean wrote = false;
            // The alert precedes the status it explains, so the phone already knows not to fade.
            if (currentAlert != sentAlert) {
                write(output, RelayProtocol.encodeAlert(active));
                sentAlert = currentAlert;
                wrote = true;
            }
            if (status != null && current != sent) {
                write(output, RelayProtocol.encodeStatus(status));
                sent = current;
                wrote = true;
            }
            if (!wrote) write(output, RelayProtocol.PING);
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
