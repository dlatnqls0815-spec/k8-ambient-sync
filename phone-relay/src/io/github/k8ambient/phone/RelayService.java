package io.github.k8ambient.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import io.github.k8ambient.core.AmbientState;
import io.github.k8ambient.core.ColorTone;
import io.github.k8ambient.core.Fader;
import io.github.k8ambient.core.K8LotusMapping;
import io.github.k8ambient.core.RelayProtocol;
import io.github.k8ambient.core.SmlStatus;
import io.github.k8ambient.core.SyncEngine;
import io.github.k8ambient.core.ZenggeFrames;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Plan B phone relay: receives raw K8 SML status from the car relay over RFCOMM, maps it, and
 * drives the saved lamps over BLE with the captured frame formats: a LotusLamp X and/or a Zengge
 * (LEDnetWF) strip, each on its own thread so a slow or missing lamp never delays the other.
 * Lotus power frames are only used when the user enables the option.
 */
public final class RelayService extends Service {
    static final String ACTION_RELOAD_THEMES = "io.github.k8ambient.phone.RELOAD_THEMES";
    private static final String CHANNEL = "relay";
    private static final long TICK_MS = 25, LINK_TIMEOUT_MS = 30000;

    private final List<Lamp> lamps = new CopyOnWriteArrayList<Lamp>();
    private boolean started;
    private Thread carThread, watchdog;
    private volatile boolean stopping, reloadThemes;
    private volatile BluetoothSocket socket;
    private volatile long lastLineAt;

    /** One lamp: its engine, fader and tone are owned by its own thread. */
    private final class Lamp {
        final HandlerThread thread;
        final Handler handler;
        final SyncEngine engine;
        final Fader fader = new Fader();
        ColorTone tone = ColorTone.NEUTRAL;
        long fadeMs;
        AmbientState lastMapped;

        Lamp(String name, SyncEngine engine) {
            this.engine = engine;
            thread = new HandlerThread(name);
            thread.start();
            handler = new Handler(thread.getLooper());
            handler.post(new Runnable() { public void run() { Lamp.this.engine.setEnabled(true); } });
            handler.post(reloadTone);
            handler.post(tick);
        }

        final Runnable tick = new Runnable() {
            public void run() {
                if (stopping) return;
                long now = SystemClock.elapsedRealtime();
                AmbientState shown = fader.current(now);
                if (shown != null) engine.onState(shown);
                try { engine.tick(now); } catch (RuntimeException error) { RelayStatus.target = RelayStatus.stamp("오류 " + error.getClass().getSimpleName()); }
                handler.postDelayed(this, TICK_MS);
            }
        };

        /** Re-reads tone and fade settings and re-applies the last state at once (live preview). */
        final Runnable reloadTone = new Runnable() {
            public void run() {
                Prefs prefs = new Prefs(RelayService.this);
                tone = prefs.tone();
                fadeMs = prefs.fadeMs();
                if (lastMapped != null) fader.target(tone.apply(lastMapped), SystemClock.elapsedRealtime(), 0);
            }
        };

        void show(final AmbientState state) {
            handler.post(new Runnable() {
                public void run() {
                    lastMapped = state;
                    fader.target(tone.apply(state), SystemClock.elapsedRealtime(), fadeMs);
                }
            });
        }

        void stop() {
            handler.removeCallbacksAndMessages(null);
            handler.post(new Runnable() { public void run() { engine.setEnabled(false); } });
            thread.quitSafely();
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RELOAD_THEMES.equals(intent.getAction())) {
            reloadThemes = true;
            for (Lamp lamp : lamps) lamp.handler.post(lamp.reloadTone);
            return START_STICKY;
        }
        // startForegroundService() requires startForeground() on every start, even one that exits at once.
        startInForeground();
        if (started) return START_STICKY;
        final Prefs prefs = new Prefs(this);
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        final BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || prefs.car() == null || !prefs.anyLamp()) {
            RelayStatus.car("차량/조명 선택 필요");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        started = true;
        RelayStatus.reset();
        RelayStatus.running = true;

        // ColorTone already scales brightness to the lamp maximum, so the engines pass it through.
        if (prefs.lamp() != null) {
            GattTransport lotus = GattTransport.lotus(this, adapter, prefs.lamp());
            lamps.add(new Lamp("lotus-sync", prefs.power()
                    ? SyncEngine.forLotusWithStaticPower(lotus, 100) : SyncEngine.forObservedLotus(lotus, 100)));
        }
        if (prefs.zengge() != null) {
            GattTransport zengge = GattTransport.zengge(this, adapter, prefs.zengge());
            lamps.add(new Lamp("zengge-sync",
                    SyncEngine.forScaledColor(zengge, new ZenggeFrames.Codec(prefs.zenggeOrder()), 100)));
        }

        carThread = new Thread(new Runnable() {
            public void run() { carLoop(adapter, prefs); }
        }, "car-link");
        carThread.start();
        watchdog = new Thread(new Runnable() {
            public void run() { watchCarLink(); }
        }, "car-watchdog");
        watchdog.start();
        return START_STICKY;
    }

    /** Closes a silent car link so the car loop reconnects. */
    private void watchCarLink() {
        while (!stopping) {
            BluetoothSocket open = socket;
            if (open != null && SystemClock.elapsedRealtime() - lastLineAt > LINK_TIMEOUT_MS) {
                RelayStatus.car("응답 없음, 재연결");
                try { open.close(); } catch (IOException ignored) { }
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
    }

    private void carLoop(BluetoothAdapter adapter, Prefs prefs) {
        K8LotusMapping mapping = new K8LotusMapping();
        prefs.applyThemes(mapping);
        long backoff = 3000;
        while (!stopping) {
            try {
                BluetoothDevice car = adapter.getRemoteDevice(prefs.car());
                RelayStatus.car("연결 중");
                BluetoothSocket opened = car.createRfcommSocketToServiceRecord(RelayProtocol.SERVICE_UUID);
                lastLineAt = SystemClock.elapsedRealtime();
                socket = opened;
                opened.connect();
                lastLineAt = SystemClock.elapsedRealtime();
                RelayStatus.car("연결됨");
                backoff = 3000;
                BufferedReader reader = new BufferedReader(new InputStreamReader(opened.getInputStream(), "US-ASCII"));
                String line;
                while (!stopping && (line = reader.readLine()) != null) {
                    lastLineAt = SystemClock.elapsedRealtime();
                    if (reloadThemes) {
                        reloadThemes = false;
                        mapping = new K8LotusMapping();
                        prefs.applyThemes(mapping);
                    }
                    byte[] raw;
                    try { raw = RelayProtocol.decodeLine(line); }
                    catch (IllegalArgumentException bad) { continue; }
                    if (raw == null) continue;
                    handleStatus(mapping, SmlStatus.parse(raw));
                }
                RelayStatus.car("연결 끊김");
            } catch (IOException error) {
                RelayStatus.car("연결 실패, " + backoff / 1000 + "초 후 재시도");
            } catch (SecurityException error) {
                RelayStatus.car("블루투스 권한 없음");
            } catch (IllegalArgumentException error) {
                RelayStatus.car("차량 주소 오류");
            } finally {
                BluetoothSocket open = socket;
                socket = null;
                if (open != null) try { open.close(); } catch (IOException ignored) { }
            }
            if (stopping) break;
            SystemClock.sleep(backoff);
            backoff = Math.min(30000, backoff * 2);
        }
    }

    private void handleStatus(K8LotusMapping mapping, SmlStatus status) {
        RelayStatus.raw = RelayStatus.stamp(RelayProtocol.encodeStatus(status.rawBytes()).substring(4));
        RelayStatus.themeKey = K8LotusMapping.isDirectColor(status) ? null : K8LotusMapping.themeKey(status);
        final AmbientState state = mapping.map(status);
        if (state == null) {
            RelayStatus.target = RelayStatus.stamp("알 수 없는 밝기 " + status.brightnessRaw);
            return;
        }
        RelayStatus.target = RelayStatus.stamp((state.on ? "켜짐 " + state.brightness + "%" : "꺼짐")
                + " RGB " + state.red + "," + state.green + "," + state.blue);
        for (Lamp lamp : lamps) lamp.show(state);
    }

    private void startInForeground() {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "앰비언트 중계", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("K8 앰비언트 중계 실행 중")
                .setContentText("차량 순정 조명을 사제 조명에 맞춥니다")
                .setOngoing(true)
                .build();
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    }

    @Override public void onDestroy() {
        stopping = true;
        RelayStatus.running = false;
        BluetoothSocket open = socket;
        if (open != null) try { open.close(); } catch (IOException ignored) { }
        if (carThread != null) carThread.interrupt();
        if (watchdog != null) watchdog.interrupt();
        for (Lamp lamp : lamps) lamp.stop();
        lamps.clear();
        super.onDestroy();
    }
}
