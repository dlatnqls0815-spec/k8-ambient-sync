package io.github.k8ambient.bleprobe;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;
import java.util.UUID;

/** Foreground test for a single owner-confirmed address on the parked test unit. */
public final class ProbeActivity extends Activity {
    private static final UUID SERVICE = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothAdapter.LeScanCallback scan;
    private BluetoothGatt gatt;
    private boolean discovering;
    private EditText address;
    private Button start;
    private Button cancel;
    private TextView status;

    private final Runnable timeout = new Runnable() {
        @Override public void run() {
            finishTrial(scan != null ? "SCAN_TIMEOUT" : discovering ? "DISCOVERY_TIMEOUT" : "CONNECT_TIMEOUT");
        }
    };

    private final BluetoothGattCallback callbacks = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(final BluetoothGatt client, final int result,
                                                       final int state) {
            handler.post(new Runnable() {
                @Override public void run() {
                    if (client != gatt) return;
                    if (result != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                        finishTrial("CONNECTION_ENDED (" + result + ")");
                        return;
                    }
                    if (state != BluetoothProfile.STATE_CONNECTED || discovering) return;
                    discovering = true;
                    status.setText("DISCOVERING");
                    try {
                        if (!client.discoverServices()) finishTrial("DISCOVERY_REJECTED");
                    } catch (RuntimeException ignored) {
                        finishTrial("DISCOVERY_ERROR");
                    }
                }
            });
        }

        @Override public void onServicesDiscovered(final BluetoothGatt client, final int result) {
            handler.post(new Runnable() {
                @Override public void run() {
                    if (client != gatt || !discovering) return;
                    if (result != BluetoothGatt.GATT_SUCCESS) {
                        finishTrial("DISCOVERY_FAILED (" + result + ")");
                        return;
                    }
                    try {
                        BluetoothGattService service = client.getService(SERVICE);
                        if (service == null) {
                            finishTrial("CONNECTED_FFF0_NOT_FOUND");
                            return;
                        }
                        BluetoothGattCharacteristic characteristic = service.getCharacteristic(WRITE);
                        if (characteristic == null) {
                            finishTrial("CONNECTED_FFF3_NOT_FOUND");
                            return;
                        }
                        boolean withoutResponse = (characteristic.getProperties()
                                & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
                        finishTrial(withoutResponse ? "CONNECTED_FFF0_FFF3_WRITE_NO_RESPONSE"
                                : "CONNECTED_FFF0_FFF3");
                    } catch (RuntimeException ignored) {
                        finishTrial("DISCOVERY_RESULT_ERROR");
                    }
                }
            });
        }
    };

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 20, 24, 20);
        TextView instructions = new TextView(this);
        instructions.setText("정차·P 상태에서 본인 조명의 주소만 입력하세요. 연결과 서비스 탐색을 점검합니다.");
        layout.addView(instructions);
        address = new EditText(this);
        address.setSingleLine(true);
        address.setSaveEnabled(false);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        address.setHint("AA:BB:CC:DD:EE:FF");
        layout.addView(address);
        start = new Button(this);
        start.setText("연결 점검");
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { startTrial(); }
        });
        layout.addView(start);
        cancel = new Button(this);
        cancel.setText("중지");
        cancel.setEnabled(false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { finishTrial("CANCELLED"); }
        });
        layout.addView(cancel);
        status = new TextView(this);
        status.setText("READY");
        layout.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
        if (Build.VERSION.SDK_INT != 19 || !"4.4.2".equals(Build.VERSION.RELEASE)) {
            start.setEnabled(false);
            status.setText("UNSUPPORTED_OS");
        }
    }

    private void startTrial() {
        if (!start.isEnabled()) return;
        final String target = address.getText().toString().trim().toUpperCase(Locale.US);
        if (!BluetoothAdapter.checkBluetoothAddress(target)) {
            status.setText("INVALID_ADDRESS");
            return;
        }
        try {
            BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                status.setText(adapter == null ? "NO_ADAPTER" : "BLUETOOTH_DISABLED");
                return;
            }
            scan = new BluetoothAdapter.LeScanCallback() {
                @Override public void onLeScan(final BluetoothDevice device, int rssi, byte[] record) {
                    if (device == null || !target.equals(device.getAddress())) return;
                    final BluetoothAdapter.LeScanCallback activeScan = this;
                    handler.post(new Runnable() {
                        @Override public void run() {
                            if (scan == activeScan) connect(device);
                        }
                    });
                }
            };
            start.setEnabled(false);
            cancel.setEnabled(true);
            address.setEnabled(false);
            status.setText("SCANNING");
            if (adapter.startLeScan(scan)) handler.postDelayed(timeout, 12000);
            else finishTrial("SCAN_REJECTED");
        } catch (RuntimeException ignored) {
            finishTrial("SCAN_ERROR");
        }
    }

    private void connect(BluetoothDevice device) {
        handler.removeCallbacks(timeout);
        status.setText("CONNECTING");
        try {
            stopScan();
            gatt = device.connectGatt(this, false, callbacks);
            if (gatt == null) finishTrial("CONNECT_REJECTED");
            else handler.postDelayed(timeout, 18000);
        } catch (RuntimeException ignored) {
            finishTrial("CONNECT_ERROR");
        }
    }

    private void stopScan() {
        if (scan == null) return;
        adapter.stopLeScan(scan);
        scan = null;
    }

    private void finishTrial(String result) {
        handler.removeCallbacks(timeout);
        try { stopScan(); } catch (RuntimeException ignored) { scan = null; }
        BluetoothGatt client = gatt;
        gatt = null;
        discovering = false;
        if (client != null) {
            try { client.disconnect(); } catch (RuntimeException ignored) { }
            try { client.close(); } catch (RuntimeException ignored) { }
        }
        address.setEnabled(true);
        start.setEnabled(true);
        cancel.setEnabled(false);
        status.setText(result);
    }

    @Override protected void onStop() {
        if (scan != null || gatt != null) finishTrial("STOPPED");
        super.onStop();
    }
}
