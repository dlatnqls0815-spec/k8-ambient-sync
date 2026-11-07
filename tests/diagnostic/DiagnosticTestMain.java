import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import hmgapi.hook.ModContext;
import io.github.k8ambient.core.SmlStatus;
import io.github.k8ambient.diagnostic.PassiveDiagnostics;

/** Runs the production receiver with deterministic test-only Android/HMG scheduling. */
public final class DiagnosticTestMain {
    private static final String ACTION = "com.mobis.intent.action.SML_STATUS_CHANGED";
    private static final String EXTRA = "com.mobis.intent.extra.SML_STATUS";
    private static int checks;

    private static final class FakeContext extends Context {
        BroadcastReceiver receiver;
        Handler handler;
        boolean failRegistration;
        int registrations;
        int unregistrations;

        @Override public Intent registerReceiver(BroadcastReceiver candidate, IntentFilter filter,
                String permission, Handler candidateHandler) {
            registrations++;
            check(ACTION.equals(filter.action) && permission == null, "passive filter");
            if (failRegistration) throw new SecurityException("synthetic-private-detail");
            check(receiver == null, "one receiver only");
            receiver = candidate;
            handler = candidateHandler;
            return null;
        }

        @Override public void unregisterReceiver(BroadcastReceiver candidate) {
            if (receiver != candidate) throw new IllegalArgumentException("not registered");
            unregistrations++;
            receiver = null;
        }

        void deliver(final Intent intent) {
            final BroadcastReceiver target = receiver;
            if (target == null) return;
            handler.postDelayed(new Runnable() {
                @Override public void run() { target.onReceive(FakeContext.this, intent); }
            }, 0);
            handler.advanceBy(0);
        }
    }

    private static void check(boolean result, String name) {
        checks++;
        if (!result) throw new AssertionError(name);
    }

    private static byte[] status(int red) {
        return new byte[] { (byte) (red >> 8), (byte) red, 0, 20, 0, 30, 6, 1, 2 };
    }

    private static Intent event(int red) { return new Intent(ACTION).putExtra(EXTRA, status(red)); }

    private static long observations(ModContext mod) {
        int count = 0;
        for (String log : mod.logs) if (log.startsWith("SML observed ")) count++;
        return count;
    }

    private static void defaultsAndHookValidation() throws Throwable {
        ModContext mod = new ModContext();
        PassiveDiagnostics diagnostic = new PassiveDiagnostics();
        FakeContext context = new FakeContext();
        diagnostic.zygoteInit(mod);
        mod.hook.simulate(null, null, context);
        mod.hook.simulate(new Object(), new Exception("synthetic-private-detail"), context);
        mod.hook.simulate(new Object(), null);
        mod.hook.simulate(new Object(), null, "not a Context");
        mod.hook.simulate(new Object(), null, context);
        check(context.registrations == 0 && HandlerThread.activeCount == 0, "default off");
        mod.diagnostics = "true";
        diagnostic.onSettingsChanged(mod);
        check(context.registrations == 0, "string cannot enable boolean setting");
        mod.diagnostics = Boolean.TRUE;
        diagnostic.onSettingsChanged(mod);
        mod.hook.simulate(new Object(), null, context);
        check(context.registrations == 1 && HandlerThread.activeCount == 1, "captured context registers once");
        mod.diagnostics = Boolean.FALSE;
        diagnostic.onSettingsChanged(mod);
        check(context.unregistrations == 1 && HandlerThread.activeCount == 0, "settings off stops receiver");
    }

    private static void receiveCoalesceAndStop() throws Throwable {
        ModContext mod = new ModContext();
        mod.diagnostics = Boolean.TRUE;
        PassiveDiagnostics diagnostic = new PassiveDiagnostics();
        FakeContext context = new FakeContext();
        diagnostic.zygoteInit(mod);
        mod.hook.simulate(new Object(), null, context);
        context.deliver(event(10));
        context.handler.advanceBy(249);
        check(observations(mod) == 0, "no sample before 250ms");
        context.deliver(event(11));
        context.handler.advanceBy(1);
        check(observations(mod) == 1, "coalesced one sample");
        check(mod.logs.get(0).equals("SML observed " + SmlStatus.parse(status(11))), "latest state retained");
        context.deliver(event(11));
        context.handler.advanceBy(500);
        check(observations(mod) == 1, "duplicates suppressed");

        context.deliver(null);
        context.deliver(new Intent("other.action").putExtra(EXTRA, status(99)));
        context.deliver(new Intent(ACTION));
        context.deliver(new Intent(ACTION).putExtra(EXTRA, new byte[8]));
        context.deliver(new Intent(ACTION).putExtra(EXTRA, new byte[65]));
        context.deliver(new Intent(ACTION).putExtra(EXTRA, "synthetic-private-detail"));
        context.handler.advanceBy(250);
        check(mod.logs.size() == 2 && mod.logs.get(1).equals("SML observation rejected: invalid payload"),
                "malformed events log once without payload details");

        context.deliver(event(300));
        context.handler.advanceBy(250);
        check(observations(mod) == 2 && mod.logs.get(2).contains("rgbRaw=300,20,30"), "theme raw preserved");
        context.deliver(event(40));
        mod.enabled = false;
        context.deliver(event(50));
        context.handler.advanceBy(500);
        check(observations(mod) == 2, "disabled guard blocks receiver and pending logs");
        diagnostic.onEnabled(mod, false);
        check(context.receiver == null && context.unregistrations == 1 && HandlerThread.activeCount == 0,
                "disabled callback unregisters and quits");
        diagnostic.onEnabled(mod, false);
        check(context.unregistrations == 1, "repeat stop harmless");
        mod.enabled = true;
        diagnostic.onEnabled(mod, true);
        check(context.registrations == 2 && HandlerThread.activeCount == 1, "same-process re-enable");
        mod.enabled = false;
        diagnostic.onEnabled(mod, false);
        check(HandlerThread.activeCount == 0, "no worker left after test");
    }

    private static void failuresDoNotEscape() throws Throwable {
        ModContext mod = new ModContext();
        mod.diagnostics = Boolean.TRUE;
        PassiveDiagnostics diagnostic = new PassiveDiagnostics();
        FakeContext context = new FakeContext();
        context.failRegistration = true;
        diagnostic.zygoteInit(mod);
        mod.hook.simulate(new Object(), null, context);
        mod.hook.simulate(new Object(), null, context);
        check(context.registrations == 2 && context.receiver == null && HandlerThread.activeCount == 0,
                "registration failures release worker and do not escape stock hook");
        check(mod.logs.size() == 1 && !mod.logs.get(0).contains("synthetic-private-detail"),
                "failure log fixed and bounded");

        ModContext hookFailure = new ModContext();
        hookFailure.failHook = true;
        new PassiveDiagnostics().zygoteInit(hookFailure);
        check(hookFailure.logs.size() == 1 && !hookFailure.logs.get(0).contains("synthetic-private-detail"),
                "unavailable hook failure contained");
    }

    public static void main(String[] args) throws Throwable {
        defaultsAndHookValidation();
        receiveCoalesceAndStop();
        failuresDoNotEscape();
        System.out.println("DiagnosticTestMain: " + checks + " checks passed (offline fakes)");
    }
}
