package io.github.k8ambient.phone;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts the relay when the saved car connects over Bluetooth (ignition on) or after a phone reboot,
 * but only while the user left the relay switched on. The battery-optimization exemption lets this
 * start a foreground service from the background.
 */
public final class AutoStartReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Prefs prefs = new Prefs(context);
        if (!prefs.auto() || prefs.car() == null || !prefs.anyLamp() || RelayStatus.running) return;
        String action = intent.getAction();
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            if (device == null || !prefs.car().equalsIgnoreCase(device.getAddress())) return;
        } else if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        try {
            context.startForegroundService(new Intent(context, RelayService.class));
        } catch (RuntimeException error) {
            // ForegroundServiceStartNotAllowedException when battery optimization is on; the user can start manually.
            RelayStatus.car("자동 시작 거부: 배터리 '제한 없음' 필요");
        }
    }
}
