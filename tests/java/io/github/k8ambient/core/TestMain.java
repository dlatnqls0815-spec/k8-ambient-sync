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
        boolean acceptObservedLotus;
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
            if (!acceptObservedLotus) WporterE1Candidate.decode(packet);
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

    /** Status bytes copied from the 2026-11-07 K8 SML captures (no identifiers). */
    private static void k8CapturedStatusesUnderOffLadderCandidate() {
        final byte[] dreamPurpleOff = {1, 0x6b, 1, 0x5a, 1, (byte) 0x95, 1, 1, 1};
        byte[] customPurpleMax = {0, (byte) 0x9b, 0, 0x21, 0, (byte) 0xef, 11, 1, 1};
        byte[] customPurpleLevel1 = {0, (byte) 0x9b, 0, 0x21, 0, (byte) 0xef, 2, 1, 1};
        AmbientState max = SmlStatus.parse(customPurpleMax).toEv6ReferenceState();
        check(max.equals(new AmbientState(0x9b, 0x21, 0xef, 100, true)), "captured custom color at raw 11");
        check(SmlStatus.parse(customPurpleLevel1).toEv6ReferenceState().brightness == 10, "raw 2 is menu level 1");
        check((SmlStatus.parse(customPurpleLevel1).toEv6ReferenceState().brightness * 20 + 50) / 100 == 2,
                "menu level 1 becomes aftermarket 2 percent");
        check(!SmlStatus.parse(new byte[] {0, (byte) 0x9b, 0, 0x21, 0, (byte) 0xef, 1, 1, 1})
                .toEv6ReferenceState().on, "raw 1 is OFF under the candidate");
        rejects(new Action() { public void run() { SmlStatus.parse(dreamPurpleOff).toEv6ReferenceState(); } });
    }

    private static void relayProtocol() {
        String log = "D/SystemService(  688): SYS_SML_STATUS_M_RESP: 0x00 0x9b 0x00 0x21 0x00 0xef 0x0b 0x01 0x01 ";
        byte[] status = RelayProtocol.parseLogLine(log);
        check(Arrays.equals(status, new byte[] {0, (byte) 0x9b, 0, 0x21, 0, (byte) 0xef, 11, 1, 1}), "parse OEM log");
        check(Arrays.equals(RelayProtocol.parseLogLine(
                "D/SystemService(  691): [MicomEventHandler.handleMessage] SYS_SML_STATUS_M_RESP: 0x01 0x6b 0x01 0x5a 0x01 0x95 0x01 0x01 0x01"),
                new byte[] {1, 0x6b, 1, 0x5a, 1, (byte) 0x95, 1, 1, 1}), "parse handler variant");
        check(RelayProtocol.parseLogLine("D/SystemService(  688): [SystemService.setSMLConfiguration] mode: 5, data: 3") == null,
                "request lines are not status");
        check(RelayProtocol.parseLogLine("SYS_SML_STATUS_M_RESP: 0x00 0x9b") == null, "short status ignored");
        check(RelayProtocol.parseLogLine("SYS_SML_STATUS_M_RESP: 0x00 0x9b 0x00 0x21 0x00 0xzz 0x0b 0x01 0x01") == null, "bad hex ignored");
        check(RelayProtocol.parseLogLine(null) == null, "null log line");

        String line = RelayProtocol.encodeStatus(status);
        check("SML 009b002100ef0b0101".equals(line), "encode status line");
        check(Arrays.equals(RelayProtocol.decodeLine(line + "\r"), status), "decode round trip");
        check(RelayProtocol.decodeLine(RelayProtocol.HELLO) == null && RelayProtocol.decodeLine(" PING ") == null, "control lines");
        rejects(new Action() { public void run() { RelayProtocol.decodeLine("SET 00"); } });
        rejects(new Action() { public void run() { RelayProtocol.decodeLine("SML 009b"); } });
        rejects(new Action() { public void run() { RelayProtocol.decodeLine("SML 009b002100ef0b010"); } });
        rejects(new Action() { public void run() { RelayProtocol.encodeStatus(new byte[8]); } });
    }

    private static void k8LotusMapping() {
        K8LotusMapping mapping = new K8LotusMapping();
        SmlStatus customMax = SmlStatus.parse(new byte[] {0, (byte) 0x9b, 0, 0x21, 0, (byte) 0xef, 11, 1, 1});
        check(new AmbientState(0x9b, 0x21, 0xef, 100, true).equals(mapping.map(customMax)), "direct color at level 10");
        SmlStatus customOff = SmlStatus.parse(new byte[] {0, (byte) 0x9b, 0, 0x21, 0, (byte) 0xef, 1, 1, 1});
        AmbientState off = mapping.map(customOff);
        check(!off.on && off.brightness == 0, "raw 1 is OFF");
        SmlStatus level1 = SmlStatus.parse(new byte[] {0, (byte) 0x9b, 0, 0x21, 0, (byte) 0xef, 2, 1, 1});
        check(mapping.map(level1).brightness == 10, "raw 2 is level 1");
        check(mapping.map(SmlStatus.parse(new byte[] {0, 1, 0, 1, 0, 1, 12, 1, 1})) == null, "unknown brightness ignored");

        SmlStatus dreamPurple = SmlStatus.parse(new byte[] {1, 0x6b, 1, 0x5a, 1, (byte) 0x95, 11, 1, 1});
        check("016b015a0195".equals(K8LotusMapping.themeKey(dreamPurple)), "theme key");
        AmbientState unknownTheme = mapping.map(dreamPurple);
        check(unknownTheme.red == 0x6b && unknownTheme.green == 0x5a && unknownTheme.blue == 0x95,
                "unknown theme uses its low bytes even after a direct color");
        SmlStatus otherTheme = SmlStatus.parse(new byte[] {1, (byte) 0xd9, 1, (byte) 0xae, 1, 0x5f, 11, 1, 1});
        AmbientState switched = mapping.map(otherTheme);
        check(switched.red == 0xd9 && switched.green == 0xae && switched.blue == 0x5f, "switching themes changes color");
        mapping.setThemeColor("016b015a0195", 126, 0, 255);
        AmbientState assigned = mapping.map(dreamPurple);
        check(assigned.red == 126 && assigned.green == 0 && assigned.blue == 255, "assigned theme color");
        mapping.clearThemeColor("016b015a0195");
        check(new K8LotusMapping().map(dreamPurple).red == 0x6b, "first unknown theme falls back to low bytes");
        rejects(new Action() { public void run() { new K8LotusMapping().setThemeColor("016B015A0195", 1, 2, 3); } });
        rejects(new Action() { public void run() { new K8LotusMapping().setThemeColor("016b015a0195", 256, 2, 3); } });
    }

    private static void colorTone() {
        AmbientState pink = new AmbientState(255, 0, 128, 100, true);
        check(ColorTone.NEUTRAL.apply(pink).equals(pink), "neutral tone is identity");
        ColorTone deep = new ColorTone(20, 100, 100, 80, 20);
        AmbientState toned = deep.apply(pink);
        check(toned.red == 255 && toned.green == 0, "gamma keeps endpoints");
        check(toned.blue == (int) Math.round(255 * Math.pow(128 / 255.0, 2.0) * 0.8), "gamma deepens mid blue, gain trims");
        check(toned.brightness == 20 && toned.on, "max brightness scales 100 to 20");
        check(deep.apply(new AmbientState(10, 10, 10, 10, true)).brightness == 2, "level 1 maps to 2 percent");
        check(!deep.apply(new AmbientState(1, 2, 3, 0, false)).on, "power passes through");
        rejects(new Action() { public void run() { new ColorTone(9, 100, 100, 100, 20); } });
        rejects(new Action() { public void run() { new ColorTone(31, 100, 100, 100, 20); } });
        rejects(new Action() { public void run() { new ColorTone(10, 101, 100, 100, 20); } });
        rejects(new Action() { public void run() { new ColorTone(10, 100, 100, 100, 0); } });
    }

    private static void fader() {
        Fader fader = new Fader();
        check(fader.current(0) == null && !fader.active(0), "empty fader");
        AmbientState red = new AmbientState(200, 0, 0, 20, true);
        AmbientState blue = new AmbientState(0, 0, 200, 10, true);
        fader.target(red, 0, 1000);
        check(red.equals(fader.current(0)), "first target jumps");
        fader.target(blue, 1000, 1000);
        AmbientState half = fader.current(1500);
        check(half.red == 100 && half.blue == 100 && half.brightness == 15 && half.on, "linear midpoint");
        check(fader.active(1500) && !fader.active(2000), "active only during fade");
        check(blue.equals(fader.current(2500)), "ends at target");
        fader.target(red, 3000, 1000);
        fader.target(new AmbientState(0, 200, 0, 20, true), 3500, 1000);
        AmbientState retarget = fader.current(3500);
        check(retarget.red == 100 && retarget.blue == 100, "retarget starts from shown color");
        AmbientState off = new AmbientState(0, 200, 0, 0, false);
        fader.target(off, 3600, 1000);
        check(off.equals(fader.current(3600)), "turning off is immediate");
        fader.target(red, 3700, 1000);
        check(red.equals(fader.current(3700)), "turning on is immediate");
        fader.target(blue, 4000, 0);
        check(blue.equals(fader.current(4000)), "zero duration jumps");
        rejects(new Action() { public void run() { new Fader().target(null, 0, 0); } });
        rejects(new Action() { public void run() { new Fader().target(new AmbientState(0, 0, 0, 0, true), 0, -1); } });
    }

    private static void observedLotusReplay() {
        MockTransport mock = new MockTransport();
        mock.acceptObservedLotus = true;
        SyncEngine engine = SyncEngine.forObservedLotus(mock);
        AmbientState maximum = new AmbientState(255, 0, 0, rawLevel(11).toK8ProvisionalBrightnessPercent(), true);
        AmbientState low = new AmbientState(255, 0, 0, rawLevel(3).toK8ProvisionalBrightnessPercent(), true);
        engine.setEnabled(true);
        engine.onState(new AmbientState(255, 0, 0, 0, false));
        engine.tick(0);
        check(mock.connects == 0 && mock.writes.isEmpty(), "observed profile never connects for OFF");
        engine.onState(maximum);
        engine.tick(50);
        engine.tick(100);
        check(Arrays.equals(mock.writes.get(0), new byte[] {0x7e, 7, 5, 3, -1, 0, 0, 0x10, (byte) 0xef}),
                "observed red frame");
        check(Arrays.equals(mock.writes.get(1), new byte[] {0x7e, 4, 1, 20, 1, -1, 2, 1, (byte) 0xef}),
                "K8 maximum emits captured 20 percent frame");
        engine.onState(low);
        engine.tick(150);
        check(Arrays.equals(mock.writes.get(2), new byte[] {0x7e, 4, 1, 5, 1, -1, 2, 1, (byte) 0xef}),
                "K8 level three emits captured five percent frame");
        engine.onState(new AmbientState(255, 0, 0, 0, false));
        engine.tick(200);
        check(mock.writes.size() == 3, "observed profile emits no speculative OFF frame");
        engine.onState(low);
        engine.tick(250);
        engine.tick(300);
        check(mock.writes.size() == 5 && Arrays.equals(mock.writes.get(2), mock.writes.get(4)),
                "observed profile resumes color and brightness after OFF input");
    }

    private static void lotusStaticPower() {
        byte[] on = {0x7e, 7, 4, -1, 1, 1, 2, 1, (byte) 0xef};
        byte[] off = {0x7e, 7, 4, 0, 1, 0, 2, 1, (byte) 0xef};
        check(Arrays.equals(LotusE1Frames.power(true), on), "static ON frame from app bytecode");
        check(Arrays.equals(LotusE1Frames.power(false), off), "static OFF frame from app bytecode");
        check(Arrays.equals(LotusE1Frames.brightness(20), new byte[] {0x7e, 4, 1, 20, 1, -1, 2, 1, (byte) 0xef}),
                "brightness builder matches captured frame");
        rejects(new Action() { public void run() { LotusE1Frames.brightness(101); } });
        rejects(new Action() { public void run() { LotusE1Frames.color(256, 0, 0); } });

        MockTransport mock = new MockTransport();
        mock.acceptObservedLotus = true;
        SyncEngine engine = SyncEngine.forLotusWithStaticPower(mock);
        AmbientState red = new AmbientState(255, 0, 0, rawLevel(11).toK8ProvisionalBrightnessPercent(), true);
        engine.setEnabled(true);
        engine.onState(new AmbientState(255, 0, 0, 0, false));
        engine.tick(0);
        check(mock.writes.size() == 1 && Arrays.equals(mock.writes.get(0), off), "OFF input sends OFF frame");
        engine.tick(50);
        engine.tick(100);
        check(mock.writes.size() == 1, "OFF frame is not repeated while off");
        engine.onState(red);
        engine.tick(150);
        engine.tick(200);
        engine.tick(250);
        check(mock.writes.size() == 4 && Arrays.equals(mock.writes.get(1), on), "ON frame precedes data");
        check(Arrays.equals(mock.writes.get(2), LotusE1Frames.color(255, 0, 0))
                && Arrays.equals(mock.writes.get(3), LotusE1Frames.brightness(20)), "color and brightness after ON");
        engine.onState(new AmbientState(255, 0, 0, 0, false));
        engine.tick(300);
        engine.tick(350);
        check(mock.writes.size() == 5 && Arrays.equals(mock.writes.get(4), off), "turning off sends one OFF frame");
        engine.onState(red);
        engine.tick(400);
        engine.tick(450);
        engine.tick(500);
        check(mock.writes.size() == 8 && Arrays.equals(mock.writes.get(5), on)
                && Arrays.equals(mock.writes.get(6), mock.writes.get(2)), "data is resent after OFF");
        mock.connected = false;
        engine.tick(550);
        check(Arrays.equals(mock.writes.get(8), on), "reconnect restores power state first");
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

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b & 255));
        return out.toString();
    }

    private static void zengge() {
        // Exact writes captured from the Zengge app (sequence numbers 6, 7, 8).
        check(hex(ZenggeFrames.staticColor(6, 255, 0, 0)).equals("00068000000d0e0b4101ff0000000000010000f032"), "zengge red frame");
        check(hex(ZenggeFrames.staticColor(7, 0, 255, 0)).equals("00078000000d0e0b410100ff00000000010000f032"), "zengge green frame");
        check(hex(ZenggeFrames.staticColor(8, 0, 0, 255)).equals("00088000000d0e0b41010000ff000000010000f032"), "zengge blue frame");
        rejects(new Action() { public void run() { ZenggeFrames.staticColor(0, 256, 0, 0); } });
        rejects(new Action() { public void run() { ZenggeFrames.wrap(256, new byte[] {1}); } });
        check(Arrays.equals(ColorOrder.GRB.toWire(255, 10, 20), new int[] {10, 255, 20}), "GRB swaps red and green");
        check(Arrays.equals(ColorOrder.BGR.toWire(1, 2, 3), new int[] {3, 2, 1}), "BGR reverses");
        check(Arrays.equals(ColorOrder.RGB.toWire(1, 2, 3), new int[] {1, 2, 3}), "RGB identity");
        check(ColorOrder.parse("grb") == ColorOrder.GRB && ColorOrder.parse("x") == ColorOrder.RGB, "order parse");
        for (ColorOrder order : ColorOrder.values()) {
            int[] w = order.toWire(1, 2, 4);
            check(w[0] + w[1] + w[2] == 7 && w[0] != w[1] && w[1] != w[2], "order is a permutation " + order);
        }

        ZenggeFrames.Codec codec = new ZenggeFrames.Codec(ColorOrder.GRB);
        byte[] first = codec.color(255, 0, 0);
        check(first[1] == 1 && (first[10] & 255) == 0 && (first[11] & 255) == 255, "codec numbers frames and swaps to GRB");
        check(codec.color(0, 0, 0)[1] == 2, "codec sequence increments");

        MockTransport mock = new MockTransport();
        mock.acceptObservedLotus = true;
        SyncEngine engine = SyncEngine.forScaledColor(mock, new ZenggeFrames.Codec(ColorOrder.RGB), 100);
        engine.setEnabled(true);
        engine.onState(new AmbientState(200, 100, 0, 50, true));
        engine.tick(0);
        check(mock.writes.size() == 1 && hex(mock.writes.get(0)).substring(16, 26).equals("4101643200"), "brightness scales RGB");
        engine.onState(new AmbientState(200, 100, 0, 50, true));
        engine.tick(100);
        check(mock.writes.size() == 1, "scaled color deduplicated");
        engine.onState(new AmbientState(200, 100, 0, 50, false));
        engine.tick(200);
        check(mock.writes.size() == 2 && hex(mock.writes.get(1)).substring(16, 26).equals("4101000000"), "off is black");
        engine.onState(new AmbientState(200, 100, 0, 100, true));
        engine.tick(260);
        check(mock.writes.size() == 3 && hex(mock.writes.get(2)).substring(16, 26).equals("4101c86400"), "on restores color");
        rejects(new Action() { public void run() { SyncEngine.forScaledColor(new MockTransport(), null, 100); } });
    }

    public static void main(String[] args) {
        stateAndRawStatus();
        packets();
        k8ProvisionalBrightness();
        k8CapturedStatusesUnderOffLadderCandidate();
        relayProtocol();
        k8LotusMapping();
        colorTone();
        fader();
        zengge();
        observedLotusReplay();
        lotusStaticPower();
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
