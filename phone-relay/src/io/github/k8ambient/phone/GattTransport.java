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
import io.github.k8ambient.core.SyncEngine;
import java.io.IOException;
import java.util.UUID;

/**
 * Blocking BLE transport (write without response), matching the SyncEngine contract: every call
 * comes from the engine's single thread and completes synchronously.
 * LotusLamp X: service 0xFFF0, write 0xFFF3. Zengge/LEDnetWF: service 0xFFFF, write 0xFF01, notify 0xFF02
 * (the Zengge app enables it before writing, so this does too).
 */
final class GattTransport implements SyncEngine.Transport {
    static final UUID LOTUS_SERVICE = uuid16(0xfff0), LOTUS_WRITE = uuid16(0xfff3);
    static final UUID ZENGGE_SERVICE = uuid16(0xffff), ZENGGE_WRITE = uuid16(0xff01), ZENGGE_NOTIFY = uuid16(0xff02);
    private static final UUID CCCD = uuid16(0x2902);
    private static final long CONNECT_MS = 12000, DISCOVER_MS = 8000, WRITE_MS = 1000;

    static UUID uuid16(int value) {
        return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", value));
    }

    static GattTransport lotus(Context context, BluetoothAdapter adapter, String address) {
        return new GattTransport(context, adapter, address, RelayStatus.LOTUS, LOTUS_SERVICE, LOTUS_WRITE, null);
    }

    static GattTransport zengge(Context context, BluetoothAdapter adapter, String address) {
        return new GattTransport(context, adapter, address, RelayStatus.ZENGGE, ZENGGE_SERVICE, ZENGGE_WRITE, ZENGGE_NOTIFY);
    }

    private final Context context;
    private final BluetoothAdapter adapter;
    private final String address;
    private final int slot;
    private final UUID serviceId, writeId, notifyId;
    private final Object lock = new Object();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic characteristic;
    private volatile boolean ready;
    private int state = -1, discoverStatus = -1, writeStatus = -1;
    private boolean discovered, writeDone, descriptorDone;

    private GattTransport(Context context, BluetoothAdapter adapter, String address, int slot,
                          UUID serviceId, UUID writeId, UUID notifyId) {
        this.context = context.getApplicationContext();
        this.adapter = adapter;
        this.address = address;
        this.slot = slot;
        this.serviceId = serviceId;
        this.writeId = writeId;
        this.notifyId = notifyId;
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            synchronized (lock) {
                state = newState;
                if (newState != BluetoothProfile.STATE_CONNECTED) ready = false;
                lock.notifyAll();
            }
            RelayStatus.lamp(slot, newState == BluetoothProfile.STATE_CONNECTED ? "연결됨" : "끊김(" + status + ")");
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            synchronized (lock) {
                discoverStatus = status;
                discovered = true;
                lock.notifyAll();
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            synchronized (lock) {
                descriptorDone = true;
                lock.notifyAll();
            }
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            synchronized (lock) {
                writeStatus = status;
                writeDone = true;
                lock.notifyAll();
            }
        }
    };

    @Override public boolean connected() {
        return ready;
    }

    @Override public void connect() throws IOException {
        closeGatt();
        RelayStatus.lamp(slot, "연결 중");
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            synchronized (lock) {
                state = -1;
                discovered = false;
            }
            BluetoothGatt opened = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (opened == null) throw new IOException("connectGatt returned null");
            synchronized (lock) {
                gatt = opened;
                await(CONNECT_MS, new Done() { public boolean done() { return state >= 0; } });
                if (state != BluetoothProfile.STATE_CONNECTED) throw new IOException("connect failed");
            }
            if (!opened.discoverServices()) throw new IOException("discovery rejected");
            synchronized (lock) {
                await(DISCOVER_MS, new Done() { public boolean done() { return discovered || state == BluetoothProfile.STATE_DISCONNECTED; } });
                if (!discovered || discoverStatus != BluetoothGatt.GATT_SUCCESS) throw new IOException("discovery failed");
            }
            BluetoothGattService service = opened.getService(serviceId);
            BluetoothGattCharacteristic found = service == null ? null : service.getCharacteristic(writeId);
            if (found == null) throw new IOException("조명 종류가 다름(서비스 없음)");
            BluetoothGattCharacteristic notify = notifyId == null || service == null ? null : service.getCharacteristic(notifyId);
            BluetoothGattDescriptor cccd = notify == null ? null : notify.getDescriptor(CCCD);
            if (cccd != null && opened.setCharacteristicNotification(notify, true)) {
                synchronized (lock) { descriptorDone = false; }
                if (opened.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS) {
                    synchronized (lock) { await(WRITE_MS, new Done() { public boolean done() { return descriptorDone; } }); }
                }
            }
            // Shortest connection interval, so each color write leaves within a few milliseconds.
            opened.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
            characteristic = found;
            ready = true;
            RelayStatus.lamp(slot, "준비됨");
        } catch (SecurityException error) {
            closeGatt();
            RelayStatus.lamp(slot, "권한 없음");
            throw new IOException("permission");
        } catch (IOException error) {
            closeGatt();
            RelayStatus.lamp(slot, "연결 실패: " + error.getMessage());
            throw error;
        } catch (IllegalArgumentException error) {
            closeGatt();
            RelayStatus.lamp(slot, "주소 오류");
            throw new IOException("address");
        }
    }

    @Override public void write(byte[] packet) throws IOException {
        BluetoothGatt current = gatt;
        if (!ready || current == null || characteristic == null) throw new IOException("not ready");
        synchronized (lock) {
            writeDone = false;
        }
        int result;
        try {
            result = current.writeCharacteristic(characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        } catch (SecurityException error) {
            throw new IOException("permission");
        }
        if (result != BluetoothStatusCodes.SUCCESS) throw new IOException("write rejected " + result);
        synchronized (lock) {
            await(WRITE_MS, new Done() { public boolean done() { return writeDone; } });
            // Android still reports a callback for write-without-response; a missing one is treated as busy.
            if (!writeDone || writeStatus != BluetoothGatt.GATT_SUCCESS) throw new IOException("write not confirmed");
        }
        RelayStatus.sent(slot, packet);
    }

    @Override public void disconnect() {
        closeGatt();
    }

    private void closeGatt() {
        ready = false;
        characteristic = null;
        BluetoothGatt old = gatt;
        gatt = null;
        if (old == null) return;
        try { old.disconnect(); } catch (SecurityException ignored) { }
        try { old.close(); } catch (SecurityException ignored) { }
    }

    private interface Done { boolean done(); }

    /** Caller holds {@link #lock}. */
    private void await(long timeoutMs, Done condition) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long left;
        while (!condition.done() && (left = deadline - System.currentTimeMillis()) > 0) {
            try { lock.wait(left); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
        }
    }
}
