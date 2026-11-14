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
    private static final long CONNECT_MS = 12000, WRITE_MS = 1000;
    /** Normal discovery takes about 0.6 s; a hung one (seen after ignition) never finishes. */
    private static final long DISCOVER_MS = 3000;
    /**
     * After our own close of a live link, Android keeps the ACL for about 4 s ("link idle timer").
     * Reconnecting inside that window reuses it with a stuck or emptied GATT state (2026-11-11 HCI:
     * discovery never sent for 40 s, or instant empty service lists), so wait until it has dropped.
     */
    private static final long FRESH_LINK_WAIT_MS = 5000;
    /** Let the stack finish its own name/appearance reads on the Lotus before asking for discovery. */
    private static final long LOTUS_SETTLE_MS = 500;
    /** Transient "queue full" results are resent instead of dropping the link. */
    private static final int BUSY_RETRIES = 4;
    private static final int GATT_CONNECTION_CONGESTED = 0x8f;
    private static final long MTU_WAIT_MS = 750;
    private static final int ZENGGE_MIN_MTU = 24; // 21-byte color frame + 3-byte ATT Write Command header.

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
    private int mtu;
    /** System.currentTimeMillis() before which a new connection would reuse the old link. */
    private long freshLinkAfter;
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

        @Override public void onMtuChanged(BluetoothGatt g, int size, int status) {
            synchronized (lock) {
                if (g != gatt) return;
                mtu = status == BluetoothGatt.GATT_SUCCESS ? size : -1;
                lock.notifyAll();
            }
        }
    };

    @Override public boolean connected() {
        return ready;
    }

    @Override public void connect() throws IOException {
        closeGatt();
        long wait = freshLinkAfter - System.currentTimeMillis();
        if (wait > 0) {
            RelayStatus.lamp(slot, "이전 연결 정리 대기");
            try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
        }
        RelayStatus.lamp(slot, "연결 중");
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            synchronized (lock) {
                state = -1;
                discovered = false;
                mtu = 0;
            }
            BluetoothGatt opened = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (opened == null) throw new IOException("connectGatt returned null");
            synchronized (lock) {
                gatt = opened;
                await(CONNECT_MS, new Done() { public boolean done() { return state >= 0; } });
                if (state != BluetoothProfile.STATE_CONNECTED) throw new IOException("connect failed");
            }
            if (slot == RelayStatus.ZENGGE && opened.requestMtu(64)) {
                synchronized (lock) {
                    await(MTU_WAIT_MS, new Done() { public boolean done() {
                        return mtu != 0 || state == BluetoothProfile.STATE_DISCONNECTED;
                    } });
                }
            }
            if (slot == RelayStatus.LOTUS) {
                synchronized (lock) {
                    await(LOTUS_SETTLE_MS, new Done() { public boolean done() { return state == BluetoothProfile.STATE_DISCONNECTED; } });
                    if (state != BluetoothProfile.STATE_CONNECTED) throw new IOException("connect lost");
                }
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
            synchronized (lock) {
                RelayStatus.lamp(slot, slot != RelayStatus.ZENGGE ? "준비됨"
                        : mtu >= ZENGGE_MIN_MTU ? "준비됨 (MTU " + mtu + ")"
                        : mtu > 0 ? "MTU 부족 (" + mtu + ")" : "준비됨 (MTU 미확인)");
            }
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
            if (slot == RelayStatus.ZENGGE && mtu > 0 && mtu < ZENGGE_MIN_MTU) {
                throw new IOException("ATT MTU too small for Zengge color");
            }
            writeDone = false;
        }
        // A congested link or a full write queue is transient: resend the same (idempotent) frame
        // instead of failing, which used to drop and reconnect the lamp (2026-11-11, status 0x8f).
        for (int attempt = 0; ; attempt++) {
            int result;
            try {
                result = current.writeCharacteristic(characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } catch (SecurityException error) {
                throw new IOException("permission");
            }
            boolean busy;
            if (result == BluetoothStatusCodes.SUCCESS) {
                synchronized (lock) {
                    await(WRITE_MS, new Done() { public boolean done() { return writeDone; } });
                    // Android still reports a callback for write-without-response; a missing one is treated as lost.
                    if (!writeDone) throw fail("write not confirmed");
                    if (writeStatus == BluetoothGatt.GATT_SUCCESS) break;
                    busy = writeStatus == GATT_CONNECTION_CONGESTED;
                    if (!busy) throw fail("write failed " + writeStatus);
                    writeDone = false;
                }
            } else {
                busy = result == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY;
                if (!busy) throw fail("write rejected " + result);
            }
            DiagLog.event(RelayStatus.lampTag(slot), "쓰기 혼잡, 재전송");
            if (attempt >= BUSY_RETRIES || !ready || gatt != current) throw fail("write busy");
            try { Thread.sleep(20L * (attempt + 1)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
        }
        RelayStatus.sent(slot, packet);
    }

    /** Records why a write failed (the engine then reconnects silently). */
    private IOException fail(String message) {
        DiagLog.event(RelayStatus.lampTag(slot), "쓰기 실패: " + message);
        return new IOException(message);
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
        synchronized (lock) {
            // Closing a link that is still up: the next connect must wait until Android drops it.
            // A link the lamp already lost (power off at ignition) can be reconnected at once.
            if (state == BluetoothProfile.STATE_CONNECTED) freshLinkAfter = System.currentTimeMillis() + FRESH_LINK_WAIT_MS;
        }
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
