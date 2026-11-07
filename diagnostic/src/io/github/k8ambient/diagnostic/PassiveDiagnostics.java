package io.github.k8ambient.diagnostic;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;

import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.ModEntry;
import io.github.k8ambient.core.SmlStatus;

/** Passive status observation only; never requests or changes the stock light. */
public final class PassiveDiagnostics extends ModEntry {
    private static final String ACTION = "com.mobis.intent.action.SML_STATUS_CHANGED";
    private static final String EXTRA = "com.mobis.intent.extra.SML_STATUS";
    private Context context;
    private BroadcastReceiver receiver;
    private HandlerThread worker;
    private boolean failureLogged;

    @Override
    public void zygoteInit(final ModContext mod) {
        try {
            mod.hook(null, "framework.hkmc_loader.automotive_service", new MethodHook() {
                @Override
                protected void afterHookedMethod(Param param) {
                    if (param.hasThrowable() || param.getResult() == null
                            || param.args.length == 0 || !(param.args[0] instanceof Context)) return;
                    synchronized (PassiveDiagnostics.this) {
                        if (context == null) context = (Context) param.args[0];
                        refresh(mod);
                    }
                }
            });
        } catch (Throwable ignored) {
            failed(mod);
        }
    }

    @Override
    public void onEnabled(ModContext mod, boolean enabled) {
        if (enabled) refresh(mod);
        else stop(mod);
    }

    @Override
    public void onSettingsChanged(ModContext mod) {
        refresh(mod);
    }

    private static boolean wanted(ModContext mod) {
        return mod.enabled() && mod.settingBoolean("diagnostics", false);
    }

    private synchronized void refresh(final ModContext mod) {
        if (!wanted(mod)) {
            stop(mod);
            return;
        }
        if (context == null || receiver != null) return;
        try {
            worker = new HandlerThread("k8-ambient-diagnostics");
            worker.start();
            final Handler handler = new Handler(worker.getLooper());
            receiver = new BroadcastReceiver() {
                private SmlStatus latest;
                private SmlStatus logged;
                private boolean scheduled;
                private boolean invalidLogged;
                private final Runnable flush = new Runnable() {
                    @Override
                    public void run() {
                        scheduled = false;
                        if (!wanted(mod) || latest == null || latest.equals(logged)) return;
                        mod.log("SML observed " + latest.toString());
                        logged = latest;
                    }
                };

                @Override
                public void onReceive(Context unused, Intent intent) {
                    if (!wanted(mod) || intent == null || !ACTION.equals(intent.getAction())) return;
                    try {
                        byte[] payload = intent.getByteArrayExtra(EXTRA);
                        // shortcut: cap diagnostic payloads at 64 bytes; revise after a verified format extension.
                        if (payload == null || payload.length < 9 || payload.length > 64) {
                            throw new IllegalArgumentException();
                        }
                        latest = SmlStatus.parse(payload);
                        if (!scheduled && !latest.equals(logged)) {
                            scheduled = handler.postDelayed(flush, 250);
                        }
                    } catch (RuntimeException ignored) {
                        if (!invalidLogged) {
                            mod.log("SML observation rejected: invalid payload");
                            invalidLogged = true;
                        }
                    }
                }
            };
            context.registerReceiver(receiver, new IntentFilter(ACTION), null, handler);
        } catch (Throwable ignored) {
            stop(mod);
            failed(mod);
        }
    }

    private synchronized void stop(ModContext mod) {
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Throwable ignored) {
                failed(mod);
            }
            receiver = null;
        }
        if (worker != null) {
            try {
                worker.quit();
            } catch (Throwable ignored) {
                failed(mod);
            }
            worker = null;
        }
    }

    private synchronized void failed(ModContext mod) {
        if (!failureLogged) {
            mod.log("Passive diagnostics unavailable; inspect compatibility before retrying");
            failureLogged = true;
        }
    }
}
