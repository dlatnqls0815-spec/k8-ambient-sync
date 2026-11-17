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
import io.github.k8ambient.core.EspConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Settings link to the ESP32 (plan C): the phone no longer drives the lamps; it sends the settings
 * its relay used to apply as text lines ({@link EspConfig}) and reads the ESP32 status back.
 * Blocking; call from one background thread. The link stays open until {@link #close()}.
 */
final class EspLink {
    private static final UUID CCCD = GattTransport.uuid16(0x2902);
    private static final long CONNECT_MS = 12000, STEP_MS = 3000;
    private final Context context;
    private final BluetoothAdapter adapter;
    private final Object lock = new Object();
    private final List<String> replies = new ArrayList<String>();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private String address;
    private int state = -1, mtu, writeStatus;
    private boolean discovered, writeDone, descriptorDone;

    EspLink(Context context, BluetoothAdapter adapter) {
        this.context = context.getApplicationContext();
        this.adapter = adapter;
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            synchronized (lock) { state = newState; lock.notifyAll(); }
        }
        @Override public void onMtuChanged(BluetoothGatt g, int size, int status) {
            synchronized (lock) { mtu = status == BluetoothGatt.GATT_SUCCESS ? size : -1; lock.notifyAll(); }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            synchronized (lock) { discovered = true; lock.notifyAll(); }
        }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            synchronized (lock) { descriptorDone = true; lock.notifyAll(); }
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            synchronized (lock) { writeStatus = status; writeDone = true; lock.notifyAll(); }
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            String text = new String(value, StandardCharsets.UTF_8);
            synchronized (lock) {
                for (String line : text.split("\n")) if (line.trim().length() > 0) replies.add(line.trim());
                lock.notifyAll();
            }
        }
    };

    private interface Done { boolean done(); }

    private void await(long ms, Done done) throws IOException {
        long until = System.currentTimeMillis() + ms;
        while (!done.done()) {
            long left = until - System.currentTimeMillis();
            if (left <= 0) return;
            try { lock.wait(left); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
        }
    }

    boolean connected() {
        synchronized (lock) { return gatt != null && rx != null && state == BluetoothProfile.STATE_CONNECTED; }
    }

    private void open(String target) throws IOException {
        if (connected() && target.equalsIgnoreCase(address)) return;
        close();
        BluetoothDevice device = adapter.getRemoteDevice(target);
        synchronized (lock) { state = -1; mtu = 0; discovered = false; replies.clear(); }
        BluetoothGatt opened = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        if (opened == null) throw new IOException("connectGatt null");
        synchronized (lock) {
            gatt = opened;
            await(CONNECT_MS, new Done() { public boolean done() { return state >= 0; } });
            if (state != BluetoothProfile.STATE_CONNECTED) throw fail("ESP32 연결 실패");
        }
        // Status and config lines are longer than the default 20-byte payload.
        if (opened.requestMtu(247)) {
            synchronized (lock) { await(STEP_MS, new Done() { public boolean done() { return mtu != 0; } }); }
        }
        if (!opened.discoverServices()) throw fail("서비스 탐색 거부");
        synchronized (lock) { await(STEP_MS * 2, new Done() { public boolean done() { return discovered; } }); }
        BluetoothGattService service = opened.getService(EspConfig.SERVICE_UUID);
        BluetoothGattCharacteristic write = service == null ? null : service.getCharacteristic(EspConfig.RX_UUID);
        BluetoothGattCharacteristic notify = service == null ? null : service.getCharacteristic(EspConfig.TX_UUID);
        if (write == null || notify == null) throw fail("ESP32 설정 서비스 없음");
        BluetoothGattDescriptor cccd = notify.getDescriptor(CCCD);
        if (!opened.setCharacteristicNotification(notify, true) || cccd == null) throw fail("알림 설정 실패");
        synchronized (lock) { descriptorDone = false; }
        if (opened.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothStatusCodes.SUCCESS)
            throw fail("알림 설정 실패");
        synchronized (lock) {
            await(STEP_MS, new Done() { public boolean done() { return descriptorDone; } });
            rx = write;
            address = target;
        }
    }

    private IOException fail(String message) {
        close();
        return new IOException(message);
    }

    /**
     * Sends the lines (write with response, one line each) and returns every reply until a line
     * starting with {@code until} arrives or the wait ends.
     */
    List<String> exchange(String target, List<String> lines, final String until, long waitMs) throws IOException {
        if (target == null) throw new IOException("ESP32 미선택");
        try {
            try {
                open(target);
            } catch (IOException first) {
                // The first GATT connect right after a scan often fails on Samsung (status 133); one retry.
                try { Thread.sleep(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw first; }
                open(target);
            }
            synchronized (lock) { replies.clear(); }
            for (String line : lines) {
                BluetoothGatt current;
                BluetoothGattCharacteristic characteristic;
                synchronized (lock) { current = gatt; characteristic = rx; writeDone = false; }
                if (current == null || characteristic == null) throw fail("ESP32 연결 끊김");
                int result = current.writeCharacteristic(characteristic, line.getBytes(StandardCharsets.UTF_8),
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                if (result != BluetoothStatusCodes.SUCCESS) throw fail("쓰기 거부 " + result);
                synchronized (lock) {
                    await(STEP_MS, new Done() { public boolean done() { return writeDone; } });
                    if (!writeDone || writeStatus != BluetoothGatt.GATT_SUCCESS) throw fail("쓰기 실패");
                }
            }
            synchronized (lock) {
                await(waitMs, new Done() { public boolean done() {
                    if (until == null) return false;
                    for (String reply : replies) if (reply.startsWith(until)) return true;
                    return false;
                } });
                return new ArrayList<String>(replies);
            }
        } catch (SecurityException error) {
            close();
            throw new IOException("블루투스 권한 없음");
        } catch (IllegalArgumentException error) {
            close();
            throw new IOException("ESP32 주소 오류");
        }
    }

    void close() {
        BluetoothGatt old;
        synchronized (lock) {
            old = gatt;
            gatt = null;
            rx = null;
            address = null;
            state = -1;
        }
        if (old != null) {
            try { old.disconnect(); old.close(); } catch (SecurityException ignored) { }
        }
    }
}
