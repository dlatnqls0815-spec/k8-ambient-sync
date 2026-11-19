package io.github.k8ambient.relay;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Looper;
import io.github.k8ambient.core.RelayProtocol;
import io.github.k8ambient.core.TelemetryProtocol;
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
 * <p>Since 0.4.2 (package; code line 0.3.6) {@link WifiKeeper} also switches the head unit's built-in Wi-Fi on
 * after boot and ignition, unless the car's own hotspot runs.
 *
 * <p>Since 0.2.0 it also records, read-only, the head unit's own log lines that may explain the
 * factory overspeed red/blink ambient effect (navigation cluster info, MICOM frames, SML, speed and
 * camera lines) into a rotating file next to the relay log, for offline analysis.
 *
 * <p>Since 0.3.0 it also serves read-only driving telemetry (speed, P, reverse, drive mode) from the
 * same log on a second RFCOMM service for the phone engine-sound app; see {@link TelemetryProtocol}.
 */
public final class CarRelay {
    private static final long PING_MS = 10000, RETRY_MS = 5000;
    /** The relay starts well within this time after ignition; a later start is an update or restart. */
    private static final long FRESH_BOOT_MS = 4 * 60 * 1000;
    /** Drive mode code for ECO (see DriveModeLight). */
    private static final int MODE_ECO = 0x02;
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
    /** Last non-zero drive mode code from SYS_DRIVE_MODE_DATA_M_RESP (0 = not seen yet). */
    private int driveMode;
    private long modeVersion;
    /** Last ignition value seen in the log was OFF (read-log thread only). */
    private boolean ignitionOff;
    /** Log time of day (ms) of the last ignition on, -1 if none (read-log thread only). */
    private long ignitionOnAt = -1;
    /** Switches the built-in Wi-Fi on after boot and ignition (see {@link WifiKeeper}). */
    private final WifiKeeper wifiKeeper = new WifiKeeper();
    private static final int MODE_SMART = 0x06;
    private static final long IGNITION_SETTLE_MS = 20000, DAY_MS = 24L * 60 * 60 * 1000;

    /** Time of day in ms from a "-v time" line ("11-10 18:17:38.960 D/..."), or -1. */
    static long logTimeMs(String line) {
        if (line == null || line.length() < 18 || line.charAt(8) != ':' || line.charAt(11) != ':' || line.charAt(14) != '.') return -1;
        try {
            int h = Integer.parseInt(line.substring(6, 8)), m = Integer.parseInt(line.substring(9, 11));
            int s = Integer.parseInt(line.substring(12, 14)), ms = Integer.parseInt(line.substring(15, 18));
            return ((h * 60L + m) * 60 + s) * 1000 + ms;
        } catch (NumberFormatException bad) {
            return -1;
        }
    }

    /** Elapsed ms from one time of day to a later one, across midnight. */
    static long sinceMs(long from, long to) {
        return ((to - from) % DAY_MS + DAY_MS) % DAY_MS;
    }
    /** Driving telemetry for the engine-sound app. Own lock so its frequent speed updates do not wake the ambient stream. */
    private final Object telemetryLock = new Object();
    private TelemetryProtocol.Snapshot telemetry = TelemetryProtocol.Snapshot.empty();
    private long telemetryVersion;

    public static void main(String[] args) {
        Looper.prepareMainLooper();
        final CarRelay relay = new CarRelay();
        relay.restoreFromOwnLog(new File(RECORD_DIR, "relay.log"),
                android.os.SystemClock.elapsedRealtime() < FRESH_BOOT_MS);
        start(new Runnable() { public void run() { relay.readLog(); } }, "sml-log");
        start(new Runnable() { public void run() { relay.serve(); } }, "rfcomm");
        start(new Runnable() { public void run() { relay.serveTelemetry(); } }, "telemetry");
        start(new Runnable() { public void run() { record(); } }, "recorder");
        log("started " + RelayProtocol.HELLO);
        relay.wifiKeeper.trigger("relay start");
        relay.wifiKeeper.startWatch();
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
                // SystemService at V: the reverse status is only logged at verbose level.
                // "-v time": the ignition rule below compares the head unit's own log times.
                process = new ProcessBuilder("logcat", "-b", "main", "-b", "system", "-v", "time", "-s",
                        "SystemService:V", "LightService:D", "WifiService:D")
                        .redirectErrorStream(true).start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    handleLine(line);
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

    /** Applies one "-v time" log line; separate from the logcat loop so it can be replayed in tests. */
    void handleLine(String line) {
        byte[] status = RelayProtocol.parseLogLine(line);
        if (status != null) publish(status);
        else publishPrediction(line);
        Boolean camera = RelayProtocol.parseAlertLogLine(line);
        if (camera != null) publishAlert(camera.booleanValue());
        Integer mode = RelayProtocol.parseDriveModeLogLine(line);
        if (mode != null) {
            // A few seconds after ignition the car reports 0x06 (SMART) while the cluster and the
            // factory light are in ECO (2026-11-10: +8 s and +13 s); a real SMART selection also
            // reports 0x06, so only 0x06 before any other mode report in this short window is ignored
            // (the user selecting modes right after ignition starts with another mode, as at 18:01:01).
            long at = logTimeMs(line);
            if (mode.intValue() == MODE_SMART && ignitionOnAt >= 0 && at >= 0
                    && sinceMs(ignitionOnAt, at) < IGNITION_SETTLE_MS) {
                log("mode 06 ignored after ignition");
            } else {
                if (mode.intValue() != MODE_SMART) ignitionOnAt = -1;  // a real selection ends the window
                publishMode(mode.intValue());
            }
        }
        Boolean ignition = RelayProtocol.parseIgnitionLogLine(line);
        if (ignition != null) {
            // The car returns to ECO at every ignition on, with no drive mode line of its own.
            if (ignition.booleanValue() && ignitionOff) {
                ignitionOnAt = logTimeMs(line);
                publishMode(MODE_ECO);
                wifiKeeper.trigger("ignition on");
            }
            ignitionOff = !ignition.booleanValue();
        }
        publishTelemetryLine(line);
        wifiKeeper.onLogLine(line);
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

    /**
     * The head unit logs its own color/brightness command about 0.3 s before the status response.
     * Send the predicted status at once; the real response follows and corrects it if it differs.
     */
    private void publishPrediction(String line) {
        byte[] predicted;
        synchronized (lock) {
            predicted = RelayProtocol.predictStatus(latest, line);
            if (predicted == null || Arrays.equals(latest, predicted)) return;
            latest = predicted;
            version++;
            lock.notifyAll();
        }
        log("state " + RelayProtocol.encodeStatus(predicted) + " (cmd)");
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

    /**
     * The car reports the ambient status and drive mode only when they change, so after a restart the
     * relay would have nothing to send. Restore the last values this relay itself logged
     * ("<time> state SML <hex>", "<time> mode <hex>"). Reads only its own log file.
     *
     * <p>On a fresh head unit boot (ignition on) the previous trip's drive mode is wrong: the user
     * reports the car returns to ECO at every start, and that reset is not seen as a mode line. So a
     * fresh boot starts in ECO; any real mode line in the log still overrides it.
     */
    void restoreFromOwnLog(File file, boolean freshBoot) {
        byte[] status = null;
        int mode = 0;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(file), "US-ASCII"));
            String line;
            while ((line = reader.readLine()) != null) {
                int at = line.indexOf(" state ");
                if (at > 0) {
                    try {
                        String value = line.substring(at + 7);
                        int note = value.indexOf(" (");  // predicted states end in " (cmd)"
                        byte[] raw = RelayProtocol.decodeLine(note < 0 ? value : value.substring(0, note));
                        if (raw != null) status = raw;
                    } catch (IllegalArgumentException ignored) { }
                    continue;
                }
                // Only "<time> mode <hex>" lines: "restored ... mode <decimal>" summaries must not be read back.
                at = line.indexOf(" mode ");
                if (at > 0 && line.indexOf(' ') == at) {
                    Integer parsed = RelayProtocol.decodeDriveMode(RelayProtocol.MODE_PREFIX + line.substring(at + 6).trim());
                    if (parsed != null) mode = parsed.intValue();
                }
            }
        } catch (IOException ignored) {
            // No previous log: start empty as before.
        } finally {
            if (reader != null) try { reader.close(); } catch (IOException ignored) { }
        }
        if (freshBoot) mode = MODE_ECO;
        synchronized (lock) {
            if (status != null) { latest = status; version++; }
            if (mode != 0) { driveMode = mode; modeVersion++; }
        }
        if (mode != 0) updateTelemetry(null, null, null, Integer.valueOf(mode));
        log("restored " + (status == null ? "-" : RelayProtocol.encodeStatus(status)) + " mode " + mode
                + (freshBoot ? " (fresh boot: ECO)" : ""));
    }

    private void publishMode(int mode) {
        synchronized (lock) {
            if (driveMode == mode) return;
            driveMode = mode;
            modeVersion++;
            lock.notifyAll();
        }
        updateTelemetry(null, null, null, Integer.valueOf(mode));
        log("mode " + RelayProtocol.encodeDriveMode(mode).substring(5));
    }

    /** Folds a speed, P or reverse log line into the telemetry snapshot (no own log: speed is chatty). */
    private void publishTelemetryLine(String line) {
        Integer speed = TelemetryProtocol.parseSpeedLogLine(line);
        Boolean park = speed == null ? TelemetryProtocol.parseParkLogLine(line) : null;
        Boolean reverse = speed == null && park == null ? TelemetryProtocol.parseReverseLogLine(line) : null;
        if (speed == null && park == null && reverse == null) return;
        updateTelemetry(speed, park, reverse, null);
    }

    private void updateTelemetry(Integer speed, Boolean park, Boolean reverse, Integer mode) {
        synchronized (telemetryLock) {
            TelemetryProtocol.Snapshot next = telemetry;
            if (speed != null) next = next.withSpeed(speed.intValue());
            if (park != null) next = next.withPark(park.booleanValue());
            if (reverse != null) next = next.withReverse(reverse.booleanValue());
            if (mode != null) next = next.withMode(mode.intValue());
            if (next.equals(telemetry)) return;
            telemetry = next;
            telemetryVersion++;
            telemetryLock.notifyAll();
        }
    }

    /** Second RFCOMM service, one engine-sound client at a time, independent of the ambient channel. */
    private void serveTelemetry() {
        while (true) {
            BluetoothServerSocket server = null;
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null || !adapter.isEnabled()) {
                    sleep(RETRY_MS);
                    continue;
                }
                server = adapter.listenUsingRfcommWithServiceRecord("K8 Telemetry", TelemetryProtocol.SERVICE_UUID);
                log("telemetry listening");
                while (true) {
                    BluetoothSocket client = server.accept();
                    log("telemetry client connected");
                    try {
                        streamTelemetry(client.getOutputStream());
                    } catch (IOException error) {
                        log("telemetry client disconnected");
                    } finally {
                        try { client.close(); } catch (IOException ignored) { }
                    }
                }
            } catch (IOException error) {
                log("telemetry server error " + error.getClass().getSimpleName());
            } catch (RuntimeException error) {
                log("telemetry server error " + error.getClass().getSimpleName());
            } finally {
                if (server != null) try { server.close(); } catch (IOException ignored) { }
            }
            sleep(RETRY_MS);
        }
    }

    private void streamTelemetry(OutputStream output) throws IOException {
        write(output, TelemetryProtocol.HELLO);
        long sent = -1;
        while (true) {
            TelemetryProtocol.Snapshot snapshot;
            synchronized (telemetryLock) {
                if (telemetryVersion == sent) {
                    try { telemetryLock.wait(TelemetryProtocol.HEARTBEAT_MS); } catch (InterruptedException e) { throw new IOException("interrupted"); }
                }
                snapshot = telemetry;
                sent = telemetryVersion;
            }
            // Sent on every change and as the heartbeat: the head unit repeats nothing at steady speed.
            write(output, TelemetryProtocol.encode(snapshot));
        }
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
        long sent = -1, sentAlert = 0, sentMode = 0;
        while (true) {
            byte[] status;
            long current, currentAlert, currentMode;
            boolean active;
            int mode;
            synchronized (lock) {
                if ((latest == null || version == sent) && alertVersion == sentAlert && modeVersion == sentMode) {
                    try { lock.wait(PING_MS); } catch (InterruptedException e) { throw new IOException("interrupted"); }
                }
                status = latest;
                current = version;
                active = alert;
                currentAlert = alertVersion;
                mode = driveMode;
                currentMode = modeVersion;
            }
            boolean wrote = false;
            if (currentMode != sentMode) {
                write(output, RelayProtocol.encodeDriveMode(mode));
                sentMode = currentMode;
                wrote = true;
            }
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
