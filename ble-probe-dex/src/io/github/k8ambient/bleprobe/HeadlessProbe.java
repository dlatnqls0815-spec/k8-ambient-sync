package io.github.k8ambient.bleprobe;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.os.Build;
import android.os.Looper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only BLE probe for app_process on the K8 head unit (API 19), run by a NEMESIS kind=sh app.
 *
 * <p>Scans, optionally connects to one user-supplied address, lists GATT services and the
 * properties of 0xFFF3, then disconnects. It never enables Bluetooth, writes a characteristic or
 * descriptor, pairs, or touches vehicle services. Output is plain text on stdout; the last line is
 * {@code RESULT <code>}.
 */
public final class HeadlessProbe {
    private static final long CONNECT_TIMEOUT_MS = 18000, DISCOVERY_TIMEOUT_MS = 15000;

    private final Object lock = new Object();
    private final ProbeLogic.Options options;
    private final Map<String, String> seen = new LinkedHashMap<String, String>();
    private BluetoothDevice target;
    private int connectionState = -1, connectionStatus = -1, discoveryStatus = -1;
    private boolean discovered;

    private HeadlessProbe(ProbeLogic.Options options) {
        this.options = options;
    }

    public static void main(final String[] args) {
        // Bluetooth may post work to a Handler; keep a main Looper running while the probe thread works.
        Looper.prepareMainLooper();
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                String result;
                try {
                    result = new HeadlessProbe(ProbeLogic.parse(args)).run();
                } catch (IllegalArgumentException error) {
                    result = error.getMessage();
                } catch (Throwable error) {
                    System.out.println("ERROR " + error.getClass().getName());
                    result = "PROBE_ERROR";
                }
                System.out.println("RESULT " + result);
                System.out.flush();
                System.exit(0);
            }
        }, "k8-ble-probe");
        worker.start();
        Looper.loop();
    }

    private String run() throws InterruptedException {
        System.out.println("PROBE " + ProbeLogic.VERSION + " sdk=" + Build.VERSION.SDK_INT
                + " release=" + Build.VERSION.RELEASE + " mode=" + (options.address == null ? "scan-only" : "target"));
        if (Build.VERSION.SDK_INT != 19) return "UNSUPPORTED_OS";
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return "NO_ADAPTER";
        if (!adapter.isEnabled()) return "BLUETOOTH_DISABLED";

        String scan = scan(adapter);
        if (scan != null) return scan;
        if (options.address == null) return "SCAN_DONE devices=" + seen.size();
        return connectAndDiscover();
    }

    private String scan(BluetoothAdapter adapter) throws InterruptedException {
        BluetoothAdapter.LeScanCallback callback = new BluetoothAdapter.LeScanCallback() {
            @Override public void onLeScan(BluetoothDevice device, int rssi, byte[] record) {
                if (device == null) return;
                String address = device.getAddress();
                boolean lampService = ProbeLogic.advertisesService(record, ProbeLogic.SERVICE_16);
                String name = device.getName();
                synchronized (lock) {
                    boolean isTarget = address != null && address.equals(options.address);
                    String shown = isTarget || lampService || name != null ? address : ProbeLogic.maskAddress(address);
                    if (!seen.containsKey(address)) {
                        seen.put(address, shown + " rssi=" + rssi + " fff0_adv=" + lampService
                                + " name=" + (name == null ? "-" : name.replace('\n', ' ')));
                    }
                    if (isTarget && target == null) {
                        target = device;
                        lock.notifyAll();
                    }
                }
            }
        };
        System.out.println("SCANNING seconds=" + options.scanSeconds);
        try {
            if (!adapter.startLeScan(callback)) return "SCAN_REJECTED";
        } catch (RuntimeException error) {
            System.out.println("ERROR " + error.getClass().getName());
            return "SCAN_ERROR";
        }
        long deadline = System.currentTimeMillis() + options.scanSeconds * 1000L;
        synchronized (lock) {
            long left;
            while ((options.address == null || target == null) && (left = deadline - System.currentTimeMillis()) > 0) {
                lock.wait(left);
            }
        }
        try { adapter.stopLeScan(callback); } catch (RuntimeException ignored) { }
        synchronized (lock) {
            for (String line : seen.values()) System.out.println("SEEN " + line);
            if (options.address != null && target == null) return "SCAN_TIMEOUT devices=" + seen.size();
        }
        return null;
    }

    private String connectAndDiscover() throws InterruptedException {
        BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                synchronized (lock) {
                    connectionStatus = status;
                    connectionState = newState;
                    lock.notifyAll();
                }
            }

            @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                synchronized (lock) {
                    discoveryStatus = status;
                    discovered = true;
                    lock.notifyAll();
                }
            }
        };
        System.out.println("CONNECTING");
        BluetoothGatt gatt;
        try {
            // API 19 BluetoothDevice.connectGatt does not use the Context; app_process has none.
            gatt = target.connectGatt(null, false, callback);
        } catch (RuntimeException error) {
            System.out.println("ERROR " + error.getClass().getName());
            return "CONNECT_ERROR";
        }
        if (gatt == null) return "GATT_UNAVAILABLE";
        try {
            synchronized (lock) {
                waitFor(CONNECT_TIMEOUT_MS, new Condition() {
                    public boolean done() { return connectionState >= 0; }
                });
                if (connectionState != BluetoothProfile.STATE_CONNECTED)
                    return connectionState < 0 ? "CONNECT_TIMEOUT" : "CONNECT_FAILED status=" + connectionStatus;
            }
            System.out.println("DISCOVERING");
            if (!gatt.discoverServices()) return "DISCOVERY_REJECTED";
            synchronized (lock) {
                waitFor(DISCOVERY_TIMEOUT_MS, new Condition() {
                    public boolean done() { return discovered || connectionState == BluetoothProfile.STATE_DISCONNECTED; }
                });
                if (!discovered) return connectionState == BluetoothProfile.STATE_DISCONNECTED ? "DISCONNECTED_DURING_DISCOVERY" : "DISCOVERY_TIMEOUT";
                if (discoveryStatus != BluetoothGatt.GATT_SUCCESS) return "DISCOVERY_FAILED status=" + discoveryStatus;
            }
            return report(gatt.getServices());
        } catch (RuntimeException error) {
            System.out.println("ERROR " + error.getClass().getName());
            return "DISCOVERY_ERROR";
        } finally {
            try { gatt.disconnect(); } catch (RuntimeException ignored) { }
            try { gatt.close(); } catch (RuntimeException ignored) { }
            System.out.println("DISCONNECTED");
        }
    }

    private static String report(List<BluetoothGattService> services) {
        boolean serviceFound = false, characteristicFound = false;
        int properties = 0;
        for (BluetoothGattService service : services) {
            String uuid = String.valueOf(service.getUuid());
            System.out.println("SERVICE " + uuid);
            boolean lamp = ProbeLogic.isShortUuid(uuid, ProbeLogic.SERVICE_16);
            serviceFound |= lamp;
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                String id = String.valueOf(characteristic.getUuid());
                int flags = characteristic.getProperties();
                System.out.println("  CHAR " + id + " " + ProbeLogic.describeProperties(flags));
                if (lamp && ProbeLogic.isShortUuid(id, ProbeLogic.WRITE_16)) {
                    characteristicFound = true;
                    properties = flags;
                }
            }
        }
        return ProbeLogic.classify(serviceFound, characteristicFound, properties);
    }

    private interface Condition { boolean done(); }

    /** Caller holds {@link #lock}. */
    private void waitFor(long timeoutMs, Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long left;
        while (!condition.done() && (left = deadline - System.currentTimeMillis()) > 0) lock.wait(left);
    }
}
