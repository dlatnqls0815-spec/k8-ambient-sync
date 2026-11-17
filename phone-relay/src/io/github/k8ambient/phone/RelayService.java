package io.github.k8ambient.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import io.github.k8ambient.core.AmbientState;
import io.github.k8ambient.core.ColorTone;
import io.github.k8ambient.core.DriveModeLight;
import io.github.k8ambient.core.Fader;
import io.github.k8ambient.core.HueBrightness;
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
    private static final long STARTUP_FADE_MS = 600;

    private final List<Lamp> lamps = new CopyOnWriteArrayList<Lamp>();
    private boolean started;
    private Thread carThread, watchdog;
    private volatile boolean stopping, reloadThemes;
    private volatile BluetoothSocket socket;
    private volatile long lastLineAt;
    /**
     * While the car is off the retry interval grows to 30 s, so at ignition the phone used to reach the
     * relay up to 30 s late (2026-11-11: relay ready 17:46:34, phone 17:47:01). When the phone's
     * Bluetooth reconnects to the car, retry at once and then every 3 s for 2 minutes.
     */
    private static final long FAST_RETRY_MS = 3000, FAST_WINDOW_MS = 120000, SLOW_RETRY_MAX_MS = 30000;
    private final Object carWake = new Object();
    private volatile long fastRetryUntil;
    private BroadcastReceiver carAclReceiver;

    /** One lamp: its engine, fader and tone are owned by its own thread. */
    private final class Lamp {
        final HandlerThread thread;
        final Handler handler;
        final SyncEngine engine;
        final Fader fader = new Fader();
        ColorTone tone = ColorTone.NEUTRAL;
        HueBrightness hue = HueBrightness.NEUTRAL;
        long fadeMs;
        AmbientState lastMapped;
        boolean cameraWarning, recoveryFade;
        long warningOnWrites, warningOffWrites;

        final boolean zengge;

        Lamp(String name, boolean zengge, SyncEngine engine) {
            this.engine = engine;
            this.zengge = zengge;
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
                render(SystemClock.elapsedRealtime());
                handler.postDelayed(this, TICK_MS);
            }
        };

        private void render(long now) {
            AmbientState shown = fader.current(now);
            if (shown == null) return;
            engine.onState(shown);
            long startupFade = fadeMs == 0 || cameraWarning ? 0 : STARTUP_FADE_MS;
            long sentBefore = engine.getSuccessfulWrites();
            try { engine.tick(now, startupFade); } catch (RuntimeException error) {
                RelayStatus.target = RelayStatus.stamp("오류 " + error.getClass().getSimpleName());
                DiagLog.event("error", (zengge ? "zengge" : "lotus") + " " + error);
            }
            if (zengge && cameraWarning && engine.getSuccessfulWrites() != sentBefore) {
                if (shown.on) warningOnWrites++;
                else warningOffWrites++;
                RelayStatus.zenggeWarningWrites = warningOnWrites + " / " + warningOffWrites;
            }
        }

        /** Re-reads tone and fade settings and re-applies the last state at once (live preview). */
        final Runnable reloadTone = new Runnable() {
            public void run() {
                Prefs prefs = new Prefs(RelayService.this);
                tone = prefs.tone(zengge);
                hue = prefs.hueBrightness();
                fadeMs = prefs.fadeMs();
                if (lastMapped != null) fader.target(toned(lastMapped), SystemClock.elapsedRealtime(), 0);
            }
        };

        /** Tone correction, then the per-color brightness ratio (hue of the factory color). */
        AmbientState toned(AmbientState state) {
            return hue.apply(state, tone.apply(state));
        }

        void show(final AmbientState state, final boolean warningFrame) {
            handler.post(new Runnable() {
                public void run() {
                    // The cached recovery state and the next SML response can have the same target.
                    if (state.equals(lastMapped) && warningFrame == cameraWarning) return;
                    long now = SystemClock.elapsedRealtime();
                    recoveryFade = !warningFrame && (cameraWarning || (recoveryFade && fader.active(now)));
                    lastMapped = state;
                    cameraWarning = warningFrame;
                    // The factory alert blinks once a second; a fade would blur and delay it.
                    // On Zengge send one ordinary color, not a queue of intermediate frames.
                    long duration = warningFrame || (zengge && !recoveryFade) ? 0 : fadeMs;
                    fader.target(toned(state), now, duration);
                    // Keep the engine's write pacing; if still rate-limited, the next tick retries.
                    if (zengge) render(now);
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
        DiagLog.init(this);
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
        DiagLog.event("app", "중계 시작 " + versionName()
                + (prefs.lamp() != null ? " Lotus" : "") + (prefs.zengge() != null ? " Zengge" : "")
                + (prefs.power() ? " 전원옵션" : ""));
        RelayStatus.reset();
        RelayStatus.running = true;

        // ColorTone already scales brightness to the lamp maximum, so the engines pass it through.
        if (prefs.lamp() != null) {
            GattTransport lotus = GattTransport.lotus(this, adapter, prefs.lamp());
            lamps.add(new Lamp("lotus-sync", false, prefs.power()
                    ? SyncEngine.forLotusWithStaticPower(lotus, 100) : SyncEngine.forObservedLotus(lotus, 100)));
        }
        if (prefs.zengge() != null) {
            GattTransport zengge = GattTransport.zengge(this, adapter, prefs.zengge());
            lamps.add(new Lamp("zengge-sync", true,
                    SyncEngine.forScaledColor(zengge, new ZenggeFrames.Codec(prefs.zenggeOrder()), 100)));
        }

        carThread = new Thread(new Runnable() {
            public void run() { carLoop(adapter, prefs); }
        }, "car-link");
        carThread.start();
        final String carAddress = prefs.car();
        carAclReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                if (device == null || !carAddress.equalsIgnoreCase(device.getAddress())) return;
                fastRetryUntil = SystemClock.elapsedRealtime() + FAST_WINDOW_MS;
                DiagLog.event("car", "차량 블루투스 연결 감지 → 즉시 재접속");
                synchronized (carWake) { carWake.notifyAll(); }
            }
        };
        registerReceiver(carAclReceiver, new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED), Context.RECEIVER_EXPORTED);
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
        DriveModeLight driveLight = prefs.driveModeLight();
        int driveMode = 0;
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
                SmlStatus lastNormalStatus = null;
                boolean warning = false;
                String line;
                while (!stopping && (line = reader.readLine()) != null) {
                    lastLineAt = SystemClock.elapsedRealtime();
                    if (reloadThemes) {
                        reloadThemes = false;
                        mapping = new K8LotusMapping();
                        prefs.applyThemes(mapping);
                        driveLight = prefs.driveModeLight();
                        if (!warning && lastNormalStatus != null)
                            handleStatus(mapping, driveLight, driveMode, lastNormalStatus, false);
                    }
                    Integer mode = RelayProtocol.decodeDriveMode(line);
                    if (mode != null) DiagLog.event("rx", line.trim());
                    if (mode != null) {
                        driveMode = mode.intValue();
                        RelayStatus.driveMode = driveMode;
                        if (!warning && lastNormalStatus != null)
                            handleStatus(mapping, driveLight, driveMode, lastNormalStatus, false);
                        continue;
                    }
                    Boolean alert = RelayProtocol.decodeAlert(line);
                    if (alert != null) DiagLog.event("rx", line.trim());
                    if (alert != null) {
                        boolean ended = warning && !alert.booleanValue();
                        warning = alert.booleanValue();
                        RelayStatus.target = RelayStatus.stamp(warning ? "과속 카메라 경고 시작" : "과속 카메라 경고 끝");
                        // Restore promptly even if the relay deduplicates the next normal SML.
                        // If we joined mid-alert, wait for the first normal SML instead.
                        if (ended && lastNormalStatus != null)
                            handleStatus(mapping, driveLight, driveMode, lastNormalStatus, false);
                        continue;
                    }
                    byte[] raw;
                    try { raw = RelayProtocol.decodeLine(line); }
                    catch (IllegalArgumentException bad) { continue; }
                    if (raw == null) continue;
                    DiagLog.event("rx", line.trim());
                    SmlStatus status = SmlStatus.parse(raw);
                    if (!warning) lastNormalStatus = status;
                    handleStatus(mapping, driveLight, driveMode, status, warning);
                }
                RelayStatus.car("연결 끊김");
            } catch (IOException error) {
                RelayStatus.car("연결 실패, " + (SystemClock.elapsedRealtime() < fastRetryUntil ? FAST_RETRY_MS : backoff) / 1000
                        + "초 후 재시도 (시동 시 즉시)");
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
            boolean fast = SystemClock.elapsedRealtime() < fastRetryUntil;
            long wait = fast ? FAST_RETRY_MS : backoff;
            synchronized (carWake) {
                // An ACL connection to the car (ignition on) wakes this wait early.
                long wokeBefore = fastRetryUntil;
                try { carWake.wait(wait); } catch (InterruptedException e) { if (stopping) break; }
                if (fastRetryUntil != wokeBefore) backoff = FAST_RETRY_MS;
            }
            backoff = SystemClock.elapsedRealtime() < fastRetryUntil ? FAST_RETRY_MS : Math.min(SLOW_RETRY_MAX_MS, backoff * 2);
        }
    }

    private void handleStatus(K8LotusMapping mapping, DriveModeLight driveLight, int driveMode,
                              SmlStatus status, boolean warning) {
        RelayStatus.raw = RelayStatus.stamp(RelayProtocol.encodeStatus(status.rawBytes()).substring(4));
        RelayStatus.themeKey = K8LotusMapping.isDirectColor(status) ? null : K8LotusMapping.themeKey(status);
        AmbientState mapped = mapping.map(status);
        RelayStatus.driveLinked = DriveModeLight.linked(status);
        // A camera alert (red blink) always wins over the drive mode color, as on the car.
        AmbientState state = warning ? mapped : driveLight.apply(mapped, status, driveMode);
        if (state == null) {
            RelayStatus.target = RelayStatus.stamp("알 수 없는 밝기 " + status.brightnessRaw);
            return;
        }
        String target = (state.on ? "켜짐 " + state.brightness + "%" : "꺼짐")
                + " RGB " + state.red + "," + state.green + "," + state.blue
                + (warning ? " (경고)" : "") + " 모드 " + driveMode + (DriveModeLight.linked(status) ? " 연동" : "");
        RelayStatus.target = RelayStatus.stamp(target);
        DiagLog.event("target", target);
        for (Lamp lamp : lamps) lamp.show(state, warning);
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

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception error) { return "?"; }
    }

    @Override public void onDestroy() {
        DiagLog.event("app", "중계 중지");
        stopping = true;
        RelayStatus.running = false;
        BluetoothSocket open = socket;
        if (open != null) try { open.close(); } catch (IOException ignored) { }
        if (carAclReceiver != null) try { unregisterReceiver(carAclReceiver); } catch (IllegalArgumentException ignored) { }
        synchronized (carWake) { carWake.notifyAll(); }
        if (carThread != null) carThread.interrupt();
        if (watchdog != null) watchdog.interrupt();
        for (Lamp lamp : lamps) lamp.stop();
        lamps.clear();
        super.onDestroy();
    }
}
