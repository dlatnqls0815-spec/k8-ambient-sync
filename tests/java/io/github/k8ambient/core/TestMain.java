package io.github.k8ambient.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TestMain {
    private static int checks;

    private interface Action { void run(); }

    private static void check(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }

    private static void rejects(Action action) {
        checks++;
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected input rejection");
    }

    private static final class MockTransport implements SyncEngine.Transport {
        final List<byte[]> writes = new ArrayList<byte[]>();
        Action afterWrite;
        boolean connected, failWrite, failEveryWrite, silentConnect, failDisconnect;
        int failedConnects, connects, writeAttempts, disconnects;

        public boolean connected() { return connected; }
        public void connect() throws IOException {
            connects++;
            if (failedConnects > 0) { failedConnects--; throw new IOException("private synthetic detail"); }
            connected = !silentConnect;
        }
        public void write(byte[] packet) throws IOException {
            writeAttempts++;
            if (!connected) throw new IOException();
            if (failWrite || failEveryWrite) { failWrite = false; throw new IOException("private synthetic detail"); }
            WporterE1Candidate.decode(packet);
            writes.add(packet.clone());
            if (afterWrite != null) {
                Action callback = afterWrite;
                afterWrite = null;
                callback.run();
            }
        }
        public void disconnect() throws IOException {
            disconnects++;
            connected = false;
            if (failDisconnect) throw new IOException("private synthetic detail");
        }
        WporterE1Candidate.Command command(int index) { return WporterE1Candidate.decode(writes.get(index)); }
    }

    private static AmbientState state(int red, int brightness, boolean on) {
        return new AmbientState(red, 20, 30, brightness, on);
    }

    private static SmlStatus rawLevel(int level) {
        return SmlStatus.parse(new byte[] {0, 10, 0, 20, 0, 30, (byte) level, 1, 1});
    }

    private static void stateAndRawStatus() {
        AmbientState a = new AmbientState(0, 255, 128, 100, true);
        check(a.equals(new AmbientState(0, 255, 128, 100, true)), "state equality");
        check(a.hashCode() == new AmbientState(0, 255, 128, 100, true).hashCode(), "state hash");
        check(!a.equals(null) && !a.equals(state(1, 1, false)), "state inequality");
        check(a.toString().contains("255"), "state display");
        final int[][] bad = {{-1, 0, 0, 0}, {256, 0, 0, 0}, {0, -1, 0, 0}, {0, 256, 0, 0},
                {0, 0, -1, 0}, {0, 0, 256, 0}, {0, 0, 0, -1}, {0, 0, 0, 101}};
        for (final int[] b : bad) rejects(new Action() { public void run() { new AmbientState(b[0], b[1], b[2], b[3], true); } });
        rejects(new Action() { public void run() { SmlStatus.parse(null); } });
        rejects(new Action() { public void run() { SmlStatus.parse(new byte[8]); } });

        byte[] bytes = {0, 10, 0, 20, 0, 30, 6, (byte) 254, 2, 99};
        SmlStatus raw = SmlStatus.parse(bytes);
        check(raw.redRaw == 10 && raw.greenRaw == 20 && raw.blueRaw == 30, "raw RGB");
        check(raw.brightnessRaw == 6 && raw.dimRaw == 254 && raw.driveModeRaw == 2, "raw fields");
        check(raw.toEv6ReferenceState().equals(state(10, 50, true)), "explicit EV6 reference");
        check(raw.equals(SmlStatus.parse(bytes)) && raw.hashCode() == SmlStatus.parse(bytes).hashCode(), "raw equality");
        bytes[9] = 42;
        check(!raw.equals(SmlStatus.parse(bytes)) && raw.rawBytes()[9] == 99, "extra bytes preserved");
        raw.rawBytes()[1] = 0;
        check(raw.redRaw == 10 && raw.rawBytes()[1] == 10, "raw output clone");
        bytes[1] = 77;
        check(raw.rawBytes()[1] == 10, "raw input clone");
        check(raw.toString().contains("bytes=10"), "raw display");

        final SmlStatus theme = SmlStatus.parse(new byte[] {0x12, 0x34, -1, -1, 0, 1, 11, 0, 0});
        check(theme.redRaw == 0x1234 && theme.greenRaw == 65535, "16-bit channels preserved");
        rejects(new Action() { public void run() { theme.toEv6ReferenceState(); } });
        for (int level = 1; level <= 11; level++) {
            SmlStatus reference = SmlStatus.parse(new byte[] {0, 0, 0, 0, 0, 0, (byte) level, 0, 0});
            check(reference.toEv6ReferenceState().brightness == (level - 1) * 10, "reference brightness range");
            check(reference.toEv6ReferenceState().on == (level != 1), "reference on/off");
        }
        for (final int level : new int[] {0, 12, 255}) rejects(new Action() {
            public void run() { SmlStatus.parse(new byte[] {0, 0, 0, 0, 0, 0, (byte) level, 0, 0}).toEv6ReferenceState(); }
        });
    }

    private static void packets() {
        check(Arrays.equals(WporterE1Candidate.color(255, 128, 0),
                new byte[] {0x7e, 7, 5, 3, -1, (byte) 128, 0, 0x10, (byte) 0xef}), "RGB golden vector");
        check(Arrays.equals(WporterE1Candidate.brightness(100),
                new byte[] {0x7e, 7, 1, 100, -1, -1, -1, 0, (byte) 0xef}), "brightness golden vector");
        check(Arrays.equals(WporterE1Candidate.power(true),
                new byte[] {0x7e, 7, 4, 1, 0, -1, -1, 0, (byte) 0xef}), "ON golden vector");
        check(Arrays.equals(WporterE1Candidate.power(false),
                new byte[] {0x7e, 7, 4, 0, 0, -1, -1, 0, (byte) 0xef}), "OFF golden vector");
        WporterE1Candidate.Command color = WporterE1Candidate.decode(WporterE1Candidate.color(255, 128, 0));
        check(color.kind == WporterE1Candidate.Kind.COLOR && color.red == 255 && color.green == 128 && color.blue == 0, "color decoder");
        check(WporterE1Candidate.decode(WporterE1Candidate.brightness(0)).brightness == 0, "zero brightness");
        check(WporterE1Candidate.decode(WporterE1Candidate.power(true)).on, "power decoder");
        rejects(new Action() { public void run() { WporterE1Candidate.decode(null); } });
        rejects(new Action() { public void run() { WporterE1Candidate.decode(new byte[8]); } });
        rejects(new Action() { public void run() { WporterE1Candidate.decode(new byte[10]); } });
        rejects(new Action() { public void run() { WporterE1Candidate.color(-1, 0, 0); } });
        rejects(new Action() { public void run() { WporterE1Candidate.color(0, 256, 0); } });
        rejects(new Action() { public void run() { WporterE1Candidate.color(0, 0, 256); } });
        rejects(new Action() { public void run() { WporterE1Candidate.brightness(-1); } });
        rejects(new Action() { public void run() { WporterE1Candidate.brightness(101); } });
        for (final int index : new int[] {0, 1, 2, 3, 7, 8}) {
            final byte[] packet = WporterE1Candidate.color(1, 2, 3);
            packet[index] = 0x42;
            rejects(new Action() { public void run() { WporterE1Candidate.decode(packet); } });
        }
        for (final int index : new int[] {3, 4, 5, 6, 7}) {
            final byte[] packet = WporterE1Candidate.brightness(100);
            packet[index] = (byte) 101;
            rejects(new Action() { public void run() { WporterE1Candidate.decode(packet); } });
        }
        for (final int index : new int[] {3, 4, 5, 6, 7}) {
            final byte[] packet = WporterE1Candidate.power(true);
            packet[index] = 2;
            rejects(new Action() { public void run() { WporterE1Candidate.decode(packet); } });
        }
        final byte[] otherProfile = WporterE1Candidate.brightness(50);
        otherProfile[1] = 4;
        rejects(new Action() { public void run() { WporterE1Candidate.decode(otherProfile); } });
    }

    private static void k8ProvisionalBrightness() {
        check(rawLevel(1).toK8ProvisionalBrightnessPercent() == 9, "K8 lit minimum normalizes above zero");
        check(rawLevel(11).toK8ProvisionalBrightnessPercent() == 100, "K8 maximum normalizes to 100");
        int previousOutput = 0;
        for (int level = 1; level <= 11; level++) {
            int normalized = rawLevel(level).toK8ProvisionalBrightnessPercent();
            int output = (normalized * 20 + 50) / 100;
            check(output > previousOutput && output <= 20, "K8 provisional levels remain lit and ordered");
            previousOutput = output;
        }
        for (final int level : new int[] {0, 12, 255}) rejects(new Action() {
            public void run() { rawLevel(level).toK8ProvisionalBrightnessPercent(); }
        });
        check(!rawLevel(1).toEv6ReferenceState().on, "EV6 reference off remains separate from K8 lit minimum");

        MockTransport mock = new MockTransport();
        SyncEngine engine = new SyncEngine(mock);
        engine.onState(state(10, rawLevel(1).toK8ProvisionalBrightnessPercent(), true));
        engine.setEnabled(true);
        engine.tick(0);
        engine.tick(50);
        engine.tick(100);
        check(mock.command(0).on && mock.command(2).brightness == 2,
                "explicitly lit K8 minimum becomes mock ON at aftermarket 2 percent");
        engine.onState(state(10, rawLevel(11).toK8ProvisionalBrightnessPercent(), true));
        engine.tick(150);
        check(mock.command(3).brightness == 20, "K8 maximum becomes mock aftermarket 20 percent");
    }

    private static void deduplicationAndCoalescing() {
        MockTransport mock = new MockTransport();
        SyncEngine engine = new SyncEngine(mock, 100);
        engine.onState(state(10, 50, true));
        engine.tick(0);
        check(mock.connects == 0 && mock.writes.isEmpty(), "disabled by default");
        engine.setEnabled(true);
        engine.tick(0);
        engine.tick(49);
        check(mock.writes.size() == 1, "50ms packet limit");
        engine.onState(state(11, 51, true));
        engine.onState(state(12, 52, true));
        engine.tick(50);
        check(mock.command(1).red == 12, "latest RGB coalesced");
        engine.onState(state(12, 53, true));
        engine.tick(100);
        check(mock.command(2).brightness == 53, "latest brightness coalesced");
        engine.tick(150);
        engine.onState(state(12, 53, true));
        engine.tick(200);
        check(mock.writes.size() == 3, "duplicates suppressed");
        engine.onState(state(13, 53, true));
        engine.tick(250);
        engine.tick(300);
        check(mock.writes.size() == 4 && mock.command(3).kind == WporterE1Candidate.Kind.COLOR, "RGB-only update");
        engine.onState(state(13, 54, true));
        engine.tick(350);
        engine.tick(400);
        check(mock.writes.size() == 5 && mock.command(4).kind == WporterE1Candidate.Kind.BRIGHTNESS, "brightness-only update");
        check(engine.getSuccessfulWrites() == 5 && engine.getConnectionAttempts() == 1 && engine.getFailures() == 0, "counters");
        engine.setEnabled(false);
        engine.onState(state(99, 99, true));
        engine.tick(500);
        check(mock.writes.size() == 5 && !mock.connected && mock.connects == 1, "disable disconnects and stops");
        engine.setEnabled(true);
        engine.tick(550);
        engine.tick(600);
        engine.tick(650);
        check(mock.command(6).red == 99 && mock.command(7).brightness == 99, "re-enable replays latest");
    }

    private static void powerAndReconnect() {
        MockTransport mock = new MockTransport();
        SyncEngine engine = new SyncEngine(mock, 100);
        engine.onState(state(10, 50, false));
        engine.setEnabled(true);
        engine.tick(0);
        engine.onState(state(20, 60, false));
        engine.tick(50);
        engine.tick(100);
        check(mock.writes.size() == 1 && !mock.command(0).on, "OFF suppresses color and brightness");
        engine.onState(state(20, 60, true));
        engine.tick(150);
        engine.tick(200);
        engine.tick(250);
        check(mock.command(1).on && mock.command(2).red == 20 && mock.command(3).brightness == 60, "ON replays suppressed values");
        mock.connected = false;
        engine.tick(300);
        engine.tick(350);
        engine.tick(400);
        check(mock.connects == 2 && mock.writes.size() == 7 && mock.command(4).on, "reconnect replay");
        engine.onState(state(20, 60, false));
        engine.tick(450);
        engine.onState(state(21, 61, false));
        engine.tick(500);
        check(mock.writes.size() == 8 && !mock.command(7).on, "turn off immediately suppresses later fields");
    }

    private static void reentrantInputUsesPacketSnapshot() {
        MockTransport mock = new MockTransport();
        final SyncEngine engine = new SyncEngine(mock, 100);
        engine.onState(state(10, 50, true));
        engine.setEnabled(true);
        engine.tick(0);
        mock.afterWrite = new Action() { public void run() { engine.onState(state(20, 60, true)); } };
        engine.tick(50);
        engine.tick(100);
        engine.tick(150);
        check(mock.writes.size() == 4 && mock.command(1).red == 10 && mock.command(2).brightness == 60
                && mock.command(3).red == 20, "write callback cannot acknowledge a newer unsent state");
    }

    private static void continuousUpdatesDoNotStarveEitherField() {
        MockTransport mock = new MockTransport();
        SyncEngine engine = new SyncEngine(mock, 100);
        engine.onState(state(0, 20, true));
        engine.setEnabled(true);
        engine.tick(0);
        for (int i = 1; i <= 12; i++) {
            engine.onState(state(i, 20 + i, true));
            engine.tick(i * 50);
            WporterE1Candidate.Command command = mock.command(i);
            if (i % 2 == 1) {
                check(command.kind == WporterE1Candidate.Kind.COLOR && command.red == i, "continuous updates send latest color");
            } else {
                check(command.kind == WporterE1Candidate.Kind.BRIGHTNESS && command.brightness == 20 + i,
                        "continuous color updates cannot starve brightness");
            }
        }
        engine.tick(650);
        engine.tick(700);
        check(mock.writes.size() == 14 && mock.command(13).red == 12, "continuous stream settles to latest state");
    }

    private static void failuresAndBackoff() {
        MockTransport mock = new MockTransport();
        SyncEngine engine = new SyncEngine(mock, 100);
        engine.onState(state(10, 50, true));
        engine.setEnabled(true);
        engine.tick(0);
        mock.failWrite = true;
        engine.tick(50);
        engine.onState(state(11, 51, true));
        engine.tick(149);
        check(mock.connects == 1 && mock.writes.size() == 1, "failed write waits for backoff");
        engine.tick(150);
        engine.tick(200);
        engine.tick(250);
        check(mock.writes.size() == 4 && mock.command(1).on && mock.command(2).red == 11
                && mock.command(3).brightness == 51, "partial failure replays latest without acknowledgment");
        check(engine.getFailures() == 1 && mock.connects == 2, "write failure recovered");

        MockTransport unavailable = new MockTransport();
        unavailable.failedConnects = 20;
        SyncEngine retry = new SyncEngine(unavailable, 100);
        retry.onState(state(1, 1, true));
        retry.setEnabled(true);
        long[] due = {0, 100, 300, 700, 1500, 3100, 6300, 11300, 16300};
        for (int i = 0; i < due.length; i++) {
            if (due[i] > 0) retry.tick(due[i] - 1);
            check(unavailable.connects == i, "no early connection retry");
            retry.tick(due[i]);
            check(unavailable.connects == i + 1 && unavailable.writes.isEmpty(), "exponential capped backoff");
        }
        check(retry.getFailures() == due.length, "connection failures counted");

        MockTransport repeated = new MockTransport();
        repeated.failEveryWrite = true;
        SyncEngine failedWrites = new SyncEngine(repeated, 100);
        failedWrites.onState(state(1, 1, true));
        failedWrites.setEnabled(true);
        failedWrites.tick(0);
        failedWrites.tick(100);
        failedWrites.tick(299);
        check(repeated.writeAttempts == 2, "connection success alone does not reset backoff");
        repeated.failEveryWrite = false;
        failedWrites.tick(300);
        failedWrites.tick(350);
        failedWrites.tick(400);
        check(repeated.writes.size() == 3, "eventual full recovery");

        MockTransport silent = new MockTransport();
        silent.silentConnect = true;
        SyncEngine silentEngine = new SyncEngine(silent, 100);
        silentEngine.onState(state(1, 1, true));
        silentEngine.setEnabled(true);
        silentEngine.tick(0);
        check(silentEngine.getFailures() == 1 && silent.writes.isEmpty(), "connect must establish connection");
    }

    private static void invalidInputsAndClock() {
        rejects(new Action() { public void run() { new SyncEngine(null); } });
        rejects(new Action() { public void run() { new SyncEngine(new MockTransport(), -1); } });
        rejects(new Action() { public void run() { new SyncEngine(new MockTransport(), 101); } });
        final SyncEngine engine = new SyncEngine(new MockTransport());
        rejects(new Action() { public void run() { engine.onState(null); } });
        rejects(new Action() { public void run() { engine.tick(-1); } });
        engine.tick(100);
        rejects(new Action() { public void run() { engine.tick(99); } });
        engine.tick(100);
        MockTransport mock = new MockTransport();
        SyncEngine noState = new SyncEngine(mock, 100);
        noState.setEnabled(true);
        noState.tick(0);
        check(mock.connects == 0 && mock.writes.isEmpty(), "no state means no connection");
    }

    private static void userBrightnessCalibration() {
        MockTransport mock = new MockTransport();
        SyncEngine engine = new SyncEngine(mock);
        engine.onState(state(10, 0, true));
        engine.setEnabled(true);
        engine.tick(0);
        engine.tick(50);
        engine.tick(100);
        check(mock.command(0).on && mock.command(2).brightness == 0, "zero brightness is not power off");
        int[] input = {25, 50, 75, 100};
        int[] expected = {5, 10, 15, 20};
        for (int i = 0; i < input.length; i++) {
            engine.onState(state(10, input[i], true));
            engine.tick(150 + i * 50);
            check(mock.command(3 + i).brightness == expected[i], "calibrated brightness " + input[i]);
        }
        engine.onState(state(10, 99, true));
        engine.tick(350);
        check(mock.writes.size() == 7, "equivalent calibrated outputs are deduplicated");
        engine.onState(state(10, 100, false));
        engine.tick(400);
        check(!mock.command(7).on, "power remains separate from brightness");
    }

    public static void main(String[] args) {
        stateAndRawStatus();
        packets();
        k8ProvisionalBrightness();
        userBrightnessCalibration();
        deduplicationAndCoalescing();
        powerAndReconnect();
        reentrantInputUsesPacketSnapshot();
        continuousUpdatesDoNotStarveEitherField();
        failuresAndBackoff();
        invalidInputsAndClock();
        System.out.println("Core checks passed: " + checks);
    }
}
