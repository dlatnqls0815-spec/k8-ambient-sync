package io.github.k8ambient.phone;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import io.github.k8ambient.core.CustomLampProfile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * One-shot BLE GATT inspector and RGB packet tester used from the custom lamp registration UI.
 * Never runs automatically; only triggered when the user taps scan/inspect or test-send.
 */
final class BleLampProbe {
    private static final long CONNECT_MS = 10000, DISCOVER_MS = 5000, WRITE_MS = 1500, MTU_MS = 750;

    static final class WritableTarget {
        final String serviceUuid;
        final String writeUuid;
        final String notifyUuid;
        final boolean writeNoResponse;
        final boolean writeWithResponse;

        WritableTarget(String serviceUuid, String writeUuid, String notifyUuid,
                       boolean writeNoResponse, boolean writeWithResponse) {
            this.serviceUuid = serviceUuid;
            this.writeUuid = writeUuid;
            this.notifyUuid = notifyUuid;
            this.writeNoResponse = writeNoResponse;
            this.writeWithResponse = writeWithResponse;
        }

        boolean preferResponse() {
            return !writeNoResponse && writeWithResponse;
        }

        String label() {
            String mode = writeNoResponse && writeWithResponse ? "무응답/응답"
                    : writeNoResponse ? "무응답" : "응답";
            String n = notifyUuid.length() > 0 ? " +알림 " + CustomLampProfile.shortUuid(notifyUuid) : "";
            return CustomLampProfile.shortUuid(serviceUuid) + " / " + CustomLampProfile.shortUuid(writeUuid)
                    + " (" + mode + n + ")";
        }
    }

    interface InspectCallback {
        void onResult(List<WritableTarget> targets, String error);
    }

    interface TestCallback {
        void onResult(boolean ok, String message);
    }

    private BleLampProbe() {}

    static void inspect(final Context context, final BluetoothAdapter adapter, final String address,
                        final InspectCallback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() {
                final Object lock = new Object();
                final int[] state = {-1};
                final int[] discStatus = {-1};
                final boolean[] discovered = {false};
                BluetoothGatt gatt = null;
                try {
                    BluetoothDevice device = adapter.getRemoteDevice(address);
                    BluetoothGattCallback cb = new BluetoothGattCallback() {
                        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                            synchronized (lock) { state[0] = newState; lock.notifyAll(); }
                        }
                        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                            synchronized (lock) { discStatus[0] = status; discovered[0] = true; lock.notifyAll(); }
                        }
                    };
                    gatt = device.connectGatt(context.getApplicationContext(), false, cb, BluetoothDevice.TRANSPORT_LE);
                    if (gatt == null) {
                        postInspect(main, callback, null, "BLE 연결 시작 실패");
                        return;
                    }
                    synchronized (lock) {
                        waitUntil(lock, CONNECT_MS, new Cond() { public boolean ok() { return state[0] >= 0; } });
                        if (state[0] != BluetoothProfile.STATE_CONNECTED) {
                            postInspect(main, callback, null, "BLE 기기 연결 실패 (다른 조명 앱을 종료해 주세요)");
                            return;
                        }
                    }
                    if (!gatt.discoverServices()) {
                        postInspect(main, callback, null, "GATT 서비스 탐색 실패");
                        return;
                    }
                    synchronized (lock) {
                        waitUntil(lock, DISCOVER_MS, new Cond() {
                            public boolean ok() { return discovered[0] || state[0] == BluetoothProfile.STATE_DISCONNECTED; }
                        });
                        if (!discovered[0] || discStatus[0] != BluetoothGatt.GATT_SUCCESS) {
                            postInspect(main, callback, null, "GATT 서비스 응답 없음");
                            return;
                        }
                    }
                    List<WritableTarget> targets = collectTargets(gatt.getServices());
                    if (targets.isEmpty()) {
                        postInspect(main, callback, targets, "쓰기 가능한 BLE 특성을 찾지 못했습니다");
                    } else {
                        postInspect(main, callback, targets, null);
                    }
                } catch (SecurityException error) {
                    postInspect(main, callback, null, "블루투스 권한 없음");
                } catch (IllegalArgumentException error) {
                    postInspect(main, callback, null, "잘못된 블루투스 주소");
                } finally {
                    closeQuiet(gatt);
                }
            }
        }, "ble-probe-inspect").start();
    }

    static void testWrite(final Context context, final BluetoothAdapter adapter,
                          final String address, final String serviceUuid, final String writeUuid,
                          final String notifyUuid, final boolean writeWithResponse,
                          final byte[] packet, final TestCallback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() {
                final Object lock = new Object();
                final int[] state = {-1};
                final int[] discStatus = {-1};
                final int[] mtu = {0};
                final int[] writeStatus = {-1};
                final boolean[] discovered = {false};
                final boolean[] descDone = {false};
                final boolean[] writeDone = {false};
                BluetoothGatt gatt = null;
                try {
                    UUID svcId = UUID.fromString(CustomLampProfile.normalizeUuid(serviceUuid));
                    UUID wrtId = UUID.fromString(CustomLampProfile.normalizeUuid(writeUuid));
                    UUID ntfId = notifyUuid == null || notifyUuid.trim().length() == 0
                            ? null : UUID.fromString(CustomLampProfile.normalizeUuid(notifyUuid));
                    BluetoothDevice device = adapter.getRemoteDevice(address);
                    BluetoothGattCallback cb = new BluetoothGattCallback() {
                        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                            synchronized (lock) { state[0] = newState; lock.notifyAll(); }
                        }
                        @Override public void onMtuChanged(BluetoothGatt g, int size, int status) {
                            synchronized (lock) { mtu[0] = status == BluetoothGatt.GATT_SUCCESS ? size : -1; lock.notifyAll(); }
                        }
                        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                            synchronized (lock) { discStatus[0] = status; discovered[0] = true; lock.notifyAll(); }
                        }
                        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
                            synchronized (lock) { descDone[0] = true; lock.notifyAll(); }
                        }
                        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                            synchronized (lock) { writeStatus[0] = status; writeDone[0] = true; lock.notifyAll(); }
                        }
                    };
                    gatt = device.connectGatt(context.getApplicationContext(), false, cb, BluetoothDevice.TRANSPORT_LE);
                    if (gatt == null) { postTest(main, callback, false, "BLE 연결 시작 실패"); return; }
                    synchronized (lock) {
                        waitUntil(lock, CONNECT_MS, new Cond() { public boolean ok() { return state[0] >= 0; } });
                        if (state[0] != BluetoothProfile.STATE_CONNECTED) {
                            postTest(main, callback, false, "연결 실패 (다른 조명 앱 종료 확인)");
                            return;
                        }
                    }
                    if (packet.length > 20 && gatt.requestMtu(Math.max(64, packet.length + 3))) {
                        synchronized (lock) {
                            waitUntil(lock, MTU_MS, new Cond() {
                                public boolean ok() { return mtu[0] != 0 || state[0] == BluetoothProfile.STATE_DISCONNECTED; }
                            });
                        }
                    }
                    if (!gatt.discoverServices()) { postTest(main, callback, false, "서비스 탐색 실패"); return; }
                    synchronized (lock) {
                        waitUntil(lock, DISCOVER_MS, new Cond() {
                            public boolean ok() { return discovered[0] || state[0] == BluetoothProfile.STATE_DISCONNECTED; }
                        });
                        if (!discovered[0] || discStatus[0] != BluetoothGatt.GATT_SUCCESS) {
                            postTest(main, callback, false, "서비스 탐색 응답 없음");
                            return;
                        }
                    }
                    BluetoothGattService svc = gatt.getService(svcId);
                    BluetoothGattCharacteristic ch = svc == null ? null : svc.getCharacteristic(wrtId);
                    if (ch == null) {
                        postTest(main, callback, false, "지정한 서비스/쓰기 특성을 찾지 못했습니다");
                        return;
                    }
                    if (ntfId != null) {
                        BluetoothGattCharacteristic ntf = svc.getCharacteristic(ntfId);
                        BluetoothGattDescriptor cccd = ntf == null ? null : ntf.getDescriptor(GattTransport.CCCD);
                        if (cccd != null && gatt.setCharacteristicNotification(ntf, true)) {
                            synchronized (lock) { descDone[0] = false; }
                            if (gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                    == BluetoothStatusCodes.SUCCESS) {
                                synchronized (lock) {
                                    waitUntil(lock, WRITE_MS, new Cond() { public boolean ok() { return descDone[0]; } });
                                }
                            }
                        }
                    }
                    int writeType = GattTransport.chooseWriteType(ch, writeWithResponse);
                    int rc = gatt.writeCharacteristic(ch, packet, writeType);
                    if (rc != BluetoothStatusCodes.SUCCESS) {
                        postTest(main, callback, false, "쓰기 거부됨 (코드 " + rc + ")");
                        return;
                    }
                    synchronized (lock) {
                        waitUntil(lock, WRITE_MS, new Cond() { public boolean ok() { return writeDone[0]; } });
                        if (!writeDone[0]) {
                            postTest(main, callback, false, "쓰기 확인 시간 초과");
                            return;
                        }
                        if (writeStatus[0] != BluetoothGatt.GATT_SUCCESS) {
                            postTest(main, callback, false, "쓰기 오류 (상태 " + writeStatus[0] + ")");
                            return;
                        }
                    }
                    // Brief pause so the controller finishes applying the color before disconnect.
                    try { Thread.sleep(150); } catch (InterruptedException ignored) { }
                    postTest(main, callback, true, "전송 완료! 조명 색이 바뀌었는지 확인하세요");
                } catch (SecurityException error) {
                    postTest(main, callback, false, "블루투스 권한 없음");
                } catch (IllegalArgumentException error) {
                    postTest(main, callback, false, "입력값 오류: " + error.getMessage());
                } finally {
                    closeQuiet(gatt);
                }
            }
        }, "ble-probe-test").start();
    }

    private static List<WritableTarget> collectTargets(List<BluetoothGattService> services) {
        List<WritableTarget> preferred = new ArrayList<WritableTarget>();
        List<WritableTarget> generic = new ArrayList<WritableTarget>();
        if (services == null) return preferred;
        for (BluetoothGattService svc : services) {
            String svcUuid = svc.getUuid().toString().toLowerCase();
            String shortSvc = CustomLampProfile.shortUuid(svcUuid);
            boolean standardGap = "1800".equals(shortSvc) || "1801".equals(shortSvc) || "180A".equals(shortSvc);
            String notifyCandidate = "";
            for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                int p = c.getProperties();
                if ((p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                    notifyCandidate = c.getUuid().toString().toLowerCase();
                    break;
                }
            }
            for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                int p = c.getProperties();
                boolean noResp = (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
                boolean withResp = (p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
                if (!noResp && !withResp) continue;
                String wrtUuid = c.getUuid().toString().toLowerCase();
                String ntf = wrtUuid.equals(notifyCandidate) ? "" : notifyCandidate;
                WritableTarget target = new WritableTarget(svcUuid, wrtUuid, ntf, noResp, withResp);
                if (standardGap) generic.add(target);
                else preferred.add(target);
            }
        }
        List<WritableTarget> out = preferred.isEmpty() ? generic : preferred;
        Collections.sort(out, new Comparator<WritableTarget>() {
            public int compare(WritableTarget a, WritableTarget b) {
                return Integer.compare(rank(a), rank(b));
            }
        });
        return out;
    }

    private static int rank(WritableTarget t) {
        for (int i = 0; i < CustomLampProfile.PRESETS.size(); i++) {
            CustomLampProfile.Preset p = CustomLampProfile.PRESETS.get(i);
            if (p.serviceUuid.equalsIgnoreCase(t.serviceUuid) && p.writeUuid.equalsIgnoreCase(t.writeUuid)) {
                return i;
            }
        }
        return t.writeNoResponse ? 50 : 60;
    }

    private static void closeQuiet(BluetoothGatt gatt) {
        if (gatt == null) return;
        try { gatt.disconnect(); } catch (SecurityException ignored) { }
        try { gatt.close(); } catch (SecurityException ignored) { }
    }

    private static void postInspect(Handler main, final InspectCallback cb,
                                    final List<WritableTarget> targets, final String error) {
        main.post(new Runnable() { public void run() { cb.onResult(targets, error); } });
    }

    private static void postTest(Handler main, final TestCallback cb, final boolean ok, final String message) {
        main.post(new Runnable() { public void run() { cb.onResult(ok, message); } });
    }

    private interface Cond { boolean ok(); }

    private static void waitUntil(Object lock, long timeoutMs, Cond cond) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long left;
        while (!cond.ok() && (left = deadline - System.currentTimeMillis()) > 0) {
            try { lock.wait(left); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }
}
