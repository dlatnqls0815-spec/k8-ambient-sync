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

        String start = "D/LightService( 2060): action : com.mnsoft.navi.action.NaviCameraAlertState, mTempCameraAlertState : 1, "
                + "mTempDelaySec : 1000,mPermanentCameraAlertState : -1LightsUtils.mDelayMsec: 1000";
        String end = "D/LightService( 2060): action : com.mnsoft.navi.action.NaviCameraAlertState, mTempCameraAlertState : 0, "
                + "mTempDelaySec : 3000,mPermanentCameraAlertState : -1LightsUtils.mDelayMsec: 3000";
        check(Boolean.TRUE.equals(RelayProtocol.parseAlertLogLine(start)), "camera alert start");
        check(Boolean.FALSE.equals(RelayProtocol.parseAlertLogLine(end)), "camera alert end");
        check(RelayProtocol.parseAlertLogLine("E/LightService( 2060): mSMLIntentBraodcastReciever reciever actioncom.mnsoft.navi.action.NaviCameraAlertState") == null,
                "receiver line without state ignored");
        check(RelayProtocol.parseAlertLogLine(start.replace("State : 1,", "State : 12,")) == null, "other alert values ignored");
        check(RelayProtocol.parseAlertLogLine(log) == null && RelayProtocol.parseAlertLogLine(null) == null, "unrelated lines");
        check(Boolean.TRUE.equals(RelayProtocol.decodeAlert(RelayProtocol.encodeAlert(true) + "\r")), "alert on round trip");
        check(Boolean.FALSE.equals(RelayProtocol.decodeAlert(RelayProtocol.encodeAlert(false))), "alert off round trip");
        check(RelayProtocol.decodeAlert(line) == null, "status line is not an alert");
        check(RelayProtocol.decodeLine(RelayProtocol.ALERT_ON) == null, "alert line is a valid non-status line");

        String modeLog = "D/SystemService(  693): SYS_DRIVE_MODE_DATA_M_RESP: 0x0f 0x01 0x03 0x00 0x60 0x00 0x45 0x52 0xff 0xff 0xff 0xff 0xff 0xff 0x00 0x00 0x07 0x06 ";
        check(Integer.valueOf(3).equals(RelayProtocol.parseDriveModeLogLine(modeLog)), "drive mode from log");
        check(RelayProtocol.parseDriveModeLogLine(modeLog.replace("0x01 0x03", "0x01 0x00")) == null, "transient zero ignored");
        check(RelayProtocol.parseDriveModeLogLine("D/SystemService(  693): [SystemService.updateDriveModeData] data: 0x0f 0x01 0x03") == null,
                "update echo line ignored");
        check(RelayProtocol.parseDriveModeLogLine("SYS_DRIVE_MODE_DATA_M_RESP: 0x0f") == null && RelayProtocol.parseDriveModeLogLine(null) == null,
                "short or null mode line");
        check("MODE 06".equals(RelayProtocol.encodeDriveMode(6)), "encode mode");
        check(Integer.valueOf(2).equals(RelayProtocol.decodeDriveMode(" MODE 02\r")), "decode mode");
        check(RelayProtocol.decodeDriveMode("MODE 00") == null && RelayProtocol.decodeDriveMode("MODE 0g") == null
                && RelayProtocol.decodeDriveMode("MODE 123") == null, "bad mode lines");
        check(RelayProtocol.decodeLine("MODE 03") == null, "mode line is a valid non-status line");
        check(RelayProtocol.parseDriveModeLogLine(modeLog.replace("0x01 0x03", "0x01 0x0f")) == null, "ignition value 0x0f is not a mode");
        check(RelayProtocol.decodeDriveMode("MODE 0f") == null, "MODE 0f from an older relay is ignored");
        check(Boolean.TRUE.equals(RelayProtocol.parseIgnitionLogLine("D/SystemService(  691): SYS_IGN_STATUS_M: 1")), "ignition on");
        check(Boolean.FALSE.equals(RelayProtocol.parseIgnitionLogLine("D/SystemService(  691): SYS_IGN_STATUS_M: 0 ")), "ignition off");
        check(RelayProtocol.parseIgnitionLogLine("V/SystemService(  691): [RCV] FUNC : [SYS_IGN_STATUS_M(0x810a)]") == null,
                "function name line is not a value");
        check(RelayProtocol.parseIgnitionLogLine("D/SystemService(  691): SYS_IGN_STATUS_M: 2") == null
                && RelayProtocol.parseIgnitionLogLine(null) == null, "other ignition values ignored");
        rejects(new Action() { public void run() { RelayProtocol.encodeDriveMode(0); } });
    }

    private static void telemetryProtocol() {
        check(Integer.valueOf(42).equals(TelemetryProtocol.parseSpeedLogLine("11-09 17:21:00.160 D/SystemService(  693): SYS_SPEED_M: 42")), "speed log");
        check(Integer.valueOf(0).equals(TelemetryProtocol.parseSpeedLogLine("D/SystemService(  693): SYS_SPEED_M: 0\r")), "speed zero");
        check(TelemetryProtocol.parseSpeedLogLine("V/SystemService(  693): [RCV] FUNC : [SYS_SPEED_M(0x8131)]") == null, "speed func header ignored");
        check(TelemetryProtocol.parseSpeedLogLine("SYS_SPEED_REG_TYPE_B_M_RESP: 0x00") == null
                && TelemetryProtocol.parseSpeedLogLine("SYS_SPEED_M: 999") == null
                && TelemetryProtocol.parseSpeedLogLine("SYS_SPEED_M: -3") == null
                && TelemetryProtocol.parseSpeedLogLine(null) == null, "bad speed lines");
        check(Boolean.TRUE.equals(TelemetryProtocol.parseParkLogLine("D/SystemService(  693): SYS_GEAR_STATUS_M: 0")), "P");
        check(Boolean.FALSE.equals(TelemetryProtocol.parseParkLogLine("D/SystemService(  693): SYS_GEAR_STATUS_M: 1")), "not P");
        check(TelemetryProtocol.parseParkLogLine("V/SystemService(  693): [RCV] FUNC : [SYS_GEAR_STATUS_M(0x8126)]") == null, "gear header ignored");
        check(Boolean.TRUE.equals(TelemetryProtocol.parseReverseLogLine("V/SystemService(  693): Reverse status changed to : true")), "reverse on");
        check(Boolean.FALSE.equals(TelemetryProtocol.parseReverseLogLine("V/SystemService(  693): Reverse status changed to : false")), "reverse off");
        check(TelemetryProtocol.parseReverseLogLine("D/SystemService(  693): onReverseStatus before :x") == null, "reverse other");
        TelemetryProtocol.Snapshot empty = TelemetryProtocol.Snapshot.empty();
        check("TEL -1 -1 -1 -1".equals(TelemetryProtocol.encode(empty)), "empty encode");
        TelemetryProtocol.Snapshot s = empty.withSpeed(57).withPark(false).withReverse(false).withMode(3);
        check("TEL 57 0 0 3".equals(TelemetryProtocol.encode(s)), "encode");
        check(s.equals(TelemetryProtocol.decode(" TEL 57 0 0 3\r")) && s.hashCode() == TelemetryProtocol.decode("TEL 57 0 0 3").hashCode(), "round trip");
        check(!s.equals(s.withPark(true)), "park changes snapshot");
        check(TelemetryProtocol.decode(TelemetryProtocol.HELLO) == null && TelemetryProtocol.decode("PING") == null
                && TelemetryProtocol.decode("TEL 57 0 0") == null && TelemetryProtocol.decode("TEL 57 2 0 3") == null
                && TelemetryProtocol.decode("TEL 400 0 0 3") == null && TelemetryProtocol.decode("TEL 5 0 0 0") == null
                && TelemetryProtocol.decode("TEL x 0 0 3") == null && TelemetryProtocol.decode(null) == null, "bad TEL lines");
        check(!TelemetryProtocol.SERVICE_UUID.equals(RelayProtocol.SERVICE_UUID), "separate channel");
        rejects(new Action() { public void run() { new TelemetryProtocol.Snapshot(-2, 0, 0, 3); } });
    }

    private static void predictedStatus() {
        // Captured command lines and the status responses that followed about 0.3 s later.
        byte[] before = RelayProtocol.decodeLine("SML 00fa000400510b0101");
        byte[] unconfirmed = RelayProtocol.predictStatus(before,
                "D/SystemService(  693): [SystemService.setSMLRGB] red: 0x00 0xc1 , green: 0x00 0x21 , blue: 0x00 0xe0 , called by 2060");
        check(unconfirmed == null, "an RGB command without a response cannot change relay color");
        check("SML 00fa000400510b0101".equals(RelayProtocol.encodeStatus(before)), "prediction does not modify its input");
        byte[] rgb = RelayProtocol.parseLogLine("SYS_SML_STATUS_M_RESP: 0x00 0xc1 0x00 0x21 0x00 0xe0 0x0b 0x01 0x01");
        check("SML 00c1002100e00b0101".equals(RelayProtocol.encodeStatus(rgb)), "confirmed restored RGB is relayed");
        check(RelayProtocol.predictStatus(rgb,
                "[SystemService.setSMLRGB] red: 0x01 0xd9 , green: 0x01 0xae , blue: 0x01 0x5f") == null,
                "a theme command also waits for confirmation");
        K8LotusMapping mapping = new K8LotusMapping();
        check(mapping.map(SmlStatus.parse(before)).equals(new AmbientState(250, 4, 81, 100, true))
                && mapping.map(SmlStatus.parse(rgb)).equals(new AmbientState(193, 33, 224, 100, true)),
                "confirmed sport red and restored RGB map to distinct lamp colors");
        byte[] level = RelayProtocol.predictStatus(rgb, "D/SystemService(  693): [SystemService.setSMLConfiguration] mode: 5, data: 7, called by 2060");
        check("SML 00c1002100e0070101".equals(RelayProtocol.encodeStatus(level)), "mode 5 sets brightness raw");
        byte[] dim = RelayProtocol.predictStatus(rgb, "D/SystemService(  693): [SystemService.setSMLConfiguration] mode: 8, data: 2, called by 2060");
        check("SML 00c1002100e00b0201".equals(RelayProtocol.encodeStatus(dim)), "mode 8 sets the dim byte");
        byte[] link = RelayProtocol.predictStatus(rgb, "D/SystemService(  693): [SystemService.setSMLConfiguration] mode: 9, data: 2, called by 2060");
        check("SML 00c1002100e00b0102".equals(RelayProtocol.encodeStatus(link)), "mode 9 sets the drive mode link");
        check(RelayProtocol.predictStatus(rgb, "D/SystemService(  693): [SystemService.setSMLConfiguration] mode: 3, data: 2, called by 2060") == null,
                "unknown configuration modes are not predicted");
        check(RelayProtocol.predictStatus(rgb, "D/SystemService(  693): [SystemService.setSMLConfiguration] mode: 5, data: 300, called by 2060") == null,
                "out-of-range data ignored");
        check(RelayProtocol.predictStatus(rgb, "D/SystemService(  693): [SystemService.setSMLRGB] red: 0x00 0xzz , green: 0x00 0x21 , blue: 0x00 0xe0") == null,
                "bad hex ignored");
        check(RelayProtocol.predictStatus(rgb, "D/SystemService(  693): [SystemService.setSMLRGB] red: 0x00 , green: 0x00 0x21 , blue: 0x00 0xe0") == null,
                "short channel ignored");
        check(RelayProtocol.predictStatus(null, "D/SystemService(  693): [SystemService.setSMLConfiguration] mode: 5, data: 7") == null
                && RelayProtocol.predictStatus(rgb, null) == null
                && RelayProtocol.predictStatus(rgb, "D/SystemService(  693): SYS_SPEED_M: 8") == null, "no status or unrelated line");
    }

    private static void driveModeLight() {
        SmlStatus linked = SmlStatus.parse(new byte[] {0, (byte) 0xd5, 0, 0x1b, 0, (byte) 0xc0, 11, 1, 2});
        SmlStatus unlinked = SmlStatus.parse(new byte[] {0, (byte) 0xd5, 0, 0x1b, 0, (byte) 0xc0, 11, 1, 1});
        check(DriveModeLight.linked(linked) && !DriveModeLight.linked(unlinked), "link flag is status byte 8");
        DriveModeLight light = new DriveModeLight();
        light.setColor(DriveModeLight.SPORT, 255, 0, 0);
        light.setColor(DriveModeLight.ECO, 0, 120, 200);
        AmbientState user = new K8LotusMapping().map(linked);
        AmbientState sport = light.apply(user, linked, DriveModeLight.SPORT);
        check(sport.red == 255 && sport.green == 0 && sport.brightness == user.brightness && sport.on, "sport color keeps brightness");
        check(light.apply(user, linked, DriveModeLight.ECO).blue == 200, "eco color");
        check(light.apply(user, linked, DriveModeLight.SMART) == user, "smart keeps the user color");
        check(light.apply(user, linked, 0) == user, "unknown mode keeps the user color");
        check(light.apply(user, unlinked, DriveModeLight.SPORT) == user, "option off keeps the user color");
        AmbientState off = new AmbientState(0xd5, 0x1b, 0xc0, 0, false);
        check(!light.apply(off, linked, DriveModeLight.SPORT).on, "mode color does not turn the light on");
        check(DriveModeLight.known(3) && !DriveModeLight.known(4), "known modes");
        rejects(new Action() { public void run() { new DriveModeLight().setColor(DriveModeLight.SMART, 1, 2, 3); } });
        rejects(new Action() { public void run() { new DriveModeLight().setColor(DriveModeLight.ECO, 256, 2, 3); } });
    }

    private static void myDriveProtocol() {
        String sample = "11-16 18:30:01.234 D/SystemService( 2060):   mIndividualModeFeedback:   [0] 4  [1] 5  [2] 5  [3] 2  [4] 15";
        int[] parsed = RelayProtocol.parseMyDriveLogLine(sample);
        check(Arrays.equals(parsed, new int[] {4, 5, 5}), "parse My Drive feedback log");
        check(Arrays.equals(RelayProtocol.parseMyDriveLogLine("D/SystemService(2060): mIndividualModeFeedback: [0] 2 [1] 2 [2] 2\r"),
                new int[] {2, 2, 2}), "parse compact spacing without trailing items");
        check(Arrays.equals(RelayProtocol.parseMyDriveLogLine("mIndividualModeFeedback:   [0]  0   [1]  255   [2]  5   [3] bad"),
                new int[] {0, 255, 5}), "parse wide spacing and ignore index 3+");
        check(RelayProtocol.parseMyDriveLogLine("mIndividualModeFeedback: [0] 4 [1] 5") == null, "short feedback ignored");
        check(RelayProtocol.parseMyDriveLogLine("mIndividualModeFeedback: [1] 4 [2] 5 [3] 2") == null, "wrong indices ignored");
        check(RelayProtocol.parseMyDriveLogLine("mIndividualModeFeedback: [0] -1 [1] 5 [2] 5") == null
                && RelayProtocol.parseMyDriveLogLine("mIndividualModeFeedback: [0] 256 [1] 5 [2] 5") == null
                && RelayProtocol.parseMyDriveLogLine("mIndividualModeFeedback: [0] x [1] 5 [2] 5") == null
                && RelayProtocol.parseMyDriveLogLine("mIndividualModeFeedback:   ") == null
                && RelayProtocol.parseMyDriveLogLine("D/SystemService( 2060): SYS_SPEED_M: 40") == null
                && RelayProtocol.parseMyDriveLogLine(null) == null, "invalid or unrelated feedback lines ignored");

        String encoded = RelayProtocol.encodeMyDrive(parsed);
        check("MYDRIVE 4 5 5".equals(encoded), "encode MYDRIVE from array");
        check("MYDRIVE 2 2 2".equals(RelayProtocol.encodeMyDrive(2, 2, 2)), "encode MYDRIVE from values");
        check(Arrays.equals(RelayProtocol.decodeMyDrive(" " + encoded + "\r"), new int[] {4, 5, 5}), "decode MYDRIVE round trip");
        check(RelayProtocol.decodeLine(encoded) == null, "MYDRIVE line is a valid non-status line");
        check(RelayProtocol.decodeMyDrive("MYDRIVE 4 5") == null
                && RelayProtocol.decodeMyDrive("MYDRIVE 4 5 5 2") == null
                && RelayProtocol.decodeMyDrive("MYDRIVE -1 5 5") == null
                && RelayProtocol.decodeMyDrive("MYDRIVE 256 5 5") == null
                && RelayProtocol.decodeMyDrive("MYDRIVE 4 x 5") == null
                && RelayProtocol.decodeMyDrive("MYDRIVE ") == null
                && RelayProtocol.decodeMyDrive("MODE 06") == null
                && RelayProtocol.decodeMyDrive(null) == null, "bad MYDRIVE lines ignored");
        rejects(new Action() { public void run() { RelayProtocol.encodeMyDrive(null); } });
        rejects(new Action() { public void run() { RelayProtocol.encodeMyDrive(new int[] {4, 5}); } });
        rejects(new Action() { public void run() { RelayProtocol.encodeMyDrive(new int[] {4, 5, 5, 2}); } });
        rejects(new Action() { public void run() { RelayProtocol.encodeMyDrive(-1, 5, 5); } });
        rejects(new Action() { public void run() { RelayProtocol.encodeMyDrive(4, 256, 5); } });
        rejects(new Action() { public void run() { RelayProtocol.encodeMyDrive(4, 5, 300); } });
        rejects(new Action() { public void run() { RelayProtocol.decodeLine("MYDRIVE bad"); } });
    }

    private static void directColorDriveModeReplay() {
        K8LotusMapping mapping = new K8LotusMapping();
        DriveModeLight light = new DriveModeLight();
        light.setColor(DriveModeLight.SPORT, 255, 0, 0);
        light.setColor(DriveModeLight.ECO, 50, 154, 158);

        // Synthetic sequence: choose a direct color while link is already on, then switch modes
        // without another SML response. This does not assume what the vehicle reports in the field.
        SmlStatus direct = SmlStatus.parse(RelayProtocol.decodeLine("SML 0091002400cf060102"));
        AmbientState user = mapping.map(direct);
        check(new AmbientState(255, 0, 0, 50, true).equals(
                light.apply(user, direct, RelayProtocol.decodeDriveMode("MODE 03"))),
                "direct color selection preserves SPORT override");
        check(new AmbientState(50, 154, 158, 50, true).equals(
                light.apply(user, direct, RelayProtocol.decodeDriveMode("MODE 02"))),
                "ECO overrides cached direct color without a new SML response");
        check(new AmbientState(0x91, 0x24, 0xcf, 50, true).equals(
                light.apply(user, direct, RelayProtocol.decodeDriveMode("MODE 06"))),
                "SMART restores the selected direct color");
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

    private static void hueBrightness() {
        HueBrightness user = HueBrightness.USER_DEFAULT;
        ColorTone lotus = new ColorTone(18, 100, 100, 85, 20);
        // The user's targets against the 20 % Lotus maximum at factory level 10.
        AmbientState warm = new AmbientState(255, 166, 70, 100, true);   // factory orange 00ff00a60046
        check(user.apply(warm, lotus.apply(warm)).brightness >= 27, "warm orange near 30 %");
        AmbientState red = new AmbientState(255, 0, 0, 100, true);
        check(user.apply(red, lotus.apply(red)).brightness == 13, "red 13 %");
        AmbientState blue = new AmbientState(0, 0, 255, 100, true);
        check(user.apply(blue, lotus.apply(blue)).brightness == 13, "blue 13 %");
        AmbientState purple = new AmbientState(0x9b, 0x21, 0xef, 100, true); // factory purple 009b002100ef
        int purpleLevel = user.apply(purple, lotus.apply(purple)).brightness;
        check(purpleLevel >= 12 && purpleLevel <= 15, "purple about 13 %");
        AmbientState yellow = new AmbientState(255, 220, 0, 100, true);
        check(user.apply(yellow, lotus.apply(yellow)).brightness == 30, "yellow 30 %");
        AmbientState white = new AmbientState(255, 255, 255, 100, true);
        check(user.apply(white, lotus.apply(white)).brightness == 20, "white unchanged");
        AmbientState green = new AmbientState(0, 255, 0, 100, true);
        check(user.apply(green, lotus.apply(green)).brightness == 20, "green unchanged by default");
        check(Math.abs(user.ratioFor(255, 0, 0) - 65) < 0.01 && Math.abs(user.ratioFor(255, 128, 0) - 150) < 0.5,
                "anchor ratios at red and warm hue");
        double between = user.ratioFor(255, 64, 0); // hue 15, halfway red→warm
        check(Math.abs(between - 107.5) < 1, "hues between anchors blend");
        double peach = user.ratioFor(255, 200, 150);
        check(peach > 120 && peach < 150, "pale warm (peach) is brightened less than full orange");
        double pink = user.ratioFor(255, 0, 128); // hue 330, between purple and red
        check(Math.abs(pink - 65) < 0.01, "wraps around the hue circle");
        check(HueBrightness.NEUTRAL.apply(warm, lotus.apply(warm)).equals(lotus.apply(warm)), "neutral is identity");
        AmbientState off = new AmbientState(255, 166, 70, 0, false);
        check(!user.apply(off, lotus.apply(off)).on && user.apply(off, lotus.apply(off)).brightness == 0, "off stays off");
        AmbientState full = new AmbientState(255, 166, 70, 100, true);
        check(new HueBrightness(new int[] {300, 300, 300, 300, 300, 300}).apply(full, full).brightness == 100, "capped at 100 %");
        check(user.ratioFor(0, 0, 0) == 100, "black has no hue");
        rejects(new Action() { public void run() { new HueBrightness(new int[] {100, 100, 100}); } });
        rejects(new Action() { public void run() { new HueBrightness(new int[] {10, 100, 100, 100, 100, 100}); } });
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

    private static void lotusStartupFade() {
        MockTransport mock = new MockTransport();
        mock.acceptObservedLotus = true;
        SyncEngine engine = SyncEngine.forLotusWithStaticPower(mock);
        engine.setEnabled(true);
        engine.onState(new AmbientState(200, 100, 60, 100, true));
        engine.tick(0, 600);
        // Simulate a slow BLE setup/write: elapsed time before initialization must not count.
        engine.tick(5000, 600);
        engine.tick(5050, 600);
        check(mock.writes.size() == 3
                && Arrays.equals(mock.writes.get(0), LotusE1Frames.color(0, 0, 0))
                && Arrays.equals(mock.writes.get(1), LotusE1Frames.brightness(20))
                && Arrays.equals(mock.writes.get(2), LotusE1Frames.power(true)),
                "Lotus loads black and calibrated brightness before ON");
        engine.tick(5100, 600);
        engine.tick(5400, 600);
        engine.tick(5700, 600);
        check(mock.writes.size() == 5
                && Arrays.equals(mock.writes.get(3), LotusE1Frames.color(100, 50, 30))
                && Arrays.equals(mock.writes.get(4), LotusE1Frames.color(200, 100, 60)),
                "Lotus ramps from black to full target after setup");
        mock.connected = false;
        engine.tick(5750, 600);
        check(Arrays.equals(mock.writes.get(5), LotusE1Frames.color(0, 0, 0)),
                "Lotus reconnect starts at black again");
    }

    private static void zenggeStartupFade() {
        MockTransport mock = new MockTransport();
        mock.acceptObservedLotus = true;
        SyncEngine engine = SyncEngine.forScaledColor(mock, new ZenggeFrames.Codec(ColorOrder.RGB), 100);
        engine.setEnabled(true);
        engine.onState(new AmbientState(200, 100, 60, 50, true));
        engine.tick(0, 600);
        engine.tick(50, 600);
        engine.tick(350, 600);
        engine.tick(650, 600);
        check(mock.writes.size() == 3
                && hex(mock.writes.get(0)).substring(16, 26).equals("4101000000")
                && hex(mock.writes.get(1)).substring(16, 26).equals("410132190f")
                && hex(mock.writes.get(2)).substring(16, 26).equals("410164321e"),
                "Zengge fades with the calibrated brightness applied");
        engine.onState(new AmbientState(200, 100, 60, 0, false));
        engine.tick(700, 600);
        check(hex(mock.writes.get(3)).substring(16, 26).equals("4101000000"), "OFF remains immediate");
        engine.onState(new AmbientState(200, 100, 60, 50, true));
        engine.tick(750, 600);
        engine.tick(800, 600);
        check(mock.writes.size() == 5 && hex(mock.writes.get(4)).substring(16, 26).equals("4101090403"),
                "OFF to ON on the same BLE connection restarts the ramp");
        engine.onState(new AmbientState(255, 0, 0, 100, true));
        engine.tick(850, 0);
        engine.tick(900, 600);
        check(mock.writes.size() == 6 && hex(mock.writes.get(5)).substring(16, 26).equals("4101ff0000"),
                "immediate alert interrupts the ramp without restarting it later");
    }

    private static void cameraWarningRecoveryFade() {
        MockTransport mock = new MockTransport();
        mock.acceptObservedLotus = true;
        SyncEngine engine = SyncEngine.forScaledColor(mock, new ZenggeFrames.Codec(ColorOrder.RGB), 100);
        Fader fader = new Fader();
        AmbientState normal = new AmbientState(0, 154, 158, 100, true);
        AmbientState warning = new AmbientState(255, 0, 0, 100, true);
        engine.setEnabled(true);
        fader.target(normal, 0, 0);
        engine.onState(fader.current(0));
        engine.tick(0, 0);
        fader.target(warning, 50, 0);
        engine.onState(fader.current(50));
        engine.tick(50, 0);
        check(hex(mock.writes.get(1)).substring(16, 26).equals("4101ff0000"),
                "warning red goes out immediately");

        fader.target(normal, 100, 300);
        engine.onState(fader.current(250));
        engine.tick(250, 600);
        check(hex(mock.writes.get(2)).substring(16, 26).equals("4101804d4f"),
                "warning end blends red with the original color");
        engine.onState(fader.current(400));
        engine.tick(400, 600);
        check(hex(mock.writes.get(3)).substring(16, 26).equals("4101009a9e"),
                "warning recovery reaches the original color at the configured time");
    }

    private static void zenggeCameraPulses() {
        MockTransport mock = new MockTransport();
        mock.acceptObservedLotus = true;
        SyncEngine engine = SyncEngine.forScaledColor(mock, new ZenggeFrames.Codec(ColorOrder.GRB), 100);
        K8LotusMapping mapping = new K8LotusMapping();
        ColorTone tone = new ColorTone(18, 100, 100, 85, 100);
        AmbientState lit = tone.apply(mapping.map(SmlStatus.parse(RelayProtocol.decodeLine("SML 00ff0032001d0b0102"))));
        AmbientState dark = tone.apply(mapping.map(SmlStatus.parse(RelayProtocol.decodeLine("SML 00ff0032001d010102"))));
        engine.setEnabled(true);
        engine.onState(lit);
        engine.tick(0, 0);
        engine.onState(dark);
        engine.tick(1000, 0);
        engine.onState(lit);
        engine.tick(2000, 0);
        check(mock.writes.size() == 3, "each Zengge warning edge produces one write");
        byte[] first = mock.writes.get(0), second = mock.writes.get(1), third = mock.writes.get(2);
        check(first.length == 21 && second.length == 21 && third.length == 21,
                "Zengge warning commands require room for all 21 bytes");
        check((first[11] & 255) == 255 && (third[11] & 255) == 255
                && second[10] == 0 && second[11] == 0 && second[12] == 0,
                "Zengge GRB warning sends full red, black, then full red");
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

    private static void wifiKeeperPolicy() {
        final Boolean on = Boolean.TRUE, off = Boolean.FALSE;
        check(WifiKeeperPolicy.decide(off, false, 0, false) == WifiKeeperPolicy.Decision.ENABLE, "wifi off, no AP: enable");
        check(WifiKeeperPolicy.decide(on, false, 0, false) == WifiKeeperPolicy.Decision.ALREADY_ON, "wifi already on");
        check(WifiKeeperPolicy.decide(off, true, 0, false) == WifiKeeperPolicy.Decision.YIELD_TO_AP, "car hotspot running: yield");
        check(WifiKeeperPolicy.decide(on, true, 0, false) == WifiKeeperPolicy.Decision.YIELD_TO_AP, "hotspot wins even if wifi_on reads 1");
        check(WifiKeeperPolicy.decide(null, false, 0, false) == WifiKeeperPolicy.Decision.STATE_UNKNOWN, "unreadable state: no action");
        check(WifiKeeperPolicy.decide(off, false, WifiKeeperPolicy.MAX_ENABLES, false) == WifiKeeperPolicy.Decision.LIMIT_REACHED, "enable limit");
        check(WifiKeeperPolicy.decide(off, false, WifiKeeperPolicy.MAX_ENABLES - 1, false) == WifiKeeperPolicy.Decision.ENABLE, "last allowed enable");
        check(WifiKeeperPolicy.decide(off, false, 0, true) == WifiKeeperPolicy.Decision.DISABLED, "kill switch");
        check(WifiKeeperPolicy.decide(off, true, 0, true) == WifiKeeperPolicy.Decision.DISABLED, "kill switch beats hotspot");
        check(WifiKeeperPolicy.parseWifiOn("1\n") == Boolean.TRUE && WifiKeeperPolicy.parseWifiOn(" 0 ") == Boolean.FALSE, "wifi_on parse");
        check(WifiKeeperPolicy.parseWifiOn("null") == null && WifiKeeperPolicy.parseWifiOn("") == null
                && WifiKeeperPolicy.parseWifiOn(null) == null && WifiKeeperPolicy.parseWifiOn("2") == null, "wifi_on unknown values");
        String idle = "USER PID PPID VSIZE RSS WCHAN PC NAME\nwifi 4529 1 3764 2176 ffffffff 00000000 S /system/bin/wpa_supplicant\n"
                + "dhcp 4559 1 1080 388 ffffffff 00000000 S /system/bin/dhcpcd\n";
        check(!WifiKeeperPolicy.hasAccessPoint(idle), "no hostapd in the observed car listing");
        check(WifiKeeperPolicy.hasAccessPoint(idle + "wifi 5000 1 3000 1000 ffffffff 00000000 S /system/bin/hostapd\n"), "hostapd found");
        check(!WifiKeeperPolicy.hasAccessPoint("wifi 1 1 1 1 f 0 S com.example.hostapd_helper\n") && !WifiKeeperPolicy.hasAccessPoint(null),
                "similar names and null are not a hotspot");
        check(WifiKeeperPolicy.SETTLE_SECONDS > 0 && WifiKeeperPolicy.WINDOW_SECONDS >= WifiKeeperPolicy.POLL_SECONDS * 2, "window sanity");
        check(WifiKeeperPolicy.WATCH_SECONDS >= 60, "watch is light");
        check(WifiKeeperPolicy.decide(off, false, 0, false, true) == WifiKeeperPolicy.Decision.USER_OFF, "user switch-off respected");
        check(WifiKeeperPolicy.decide(on, false, 0, false, true) == WifiKeeperPolicy.Decision.ALREADY_ON, "user off but on again");
        check(WifiKeeperPolicy.decide(off, false, 0, true, true) == WifiKeeperPolicy.Decision.DISABLED, "kill switch first");
        int[] call = WifiKeeperPolicy.parseSetWifiEnabled("11-18 23:17:23.970 D/WifiService(  607): setWifiEnabled: true pid=4021, uid=1000");
        check(call != null && call[0] == 1 && call[1] == 4021 && call[2] == 1000, "observed setWifiEnabled line");
        call = WifiKeeperPolicy.parseSetWifiEnabled("D/WifiService(  607): setWifiEnabled: false pid=12, uid=0");
        check(call != null && call[0] == 0 && call[1] == 12 && call[2] == 0, "switch-off line");
        check(WifiKeeperPolicy.parseSetWifiEnabled("D/WifiService(  607): isWiFiSupport: true") == null
                && WifiKeeperPolicy.parseSetWifiEnabled("setWifiEnabled: maybe pid=1, uid=1") == null
                && WifiKeeperPolicy.parseSetWifiEnabled("setWifiEnabled: true pid=, uid=1") == null
                && WifiKeeperPolicy.parseSetWifiEnabled(null) == null, "other WifiService lines ignored");
        check(WifiKeeperPolicy.isUserWifiApp("com.hkmc.wifisettings") && WifiKeeperPolicy.isUserWifiApp("com.android.systemui"),
                "user Wi-Fi screens");
        check(!WifiKeeperPolicy.isUserWifiApp("pid 12") && !WifiKeeperPolicy.isUserWifiApp("/system/bin/sh")
                && !WifiKeeperPolicy.isUserWifiApp("com.nemesis.manager") && !WifiKeeperPolicy.isUserWifiApp(null), "other callers");
    }

    private static void customRgbTemplate() {
        CustomRgbTemplate lotus = CustomRgbTemplate.parse("7e 07 05 03 RR GG BB 10 ef");
        check(lotus.packetLength() == 9, "custom template length");
        check(Arrays.equals(lotus.format(255, 128, 16, 1), LotusE1Frames.color(255, 128, 16)),
                "custom template matches LotusE1Frames");

        CustomRgbTemplate zengge = CustomRgbTemplate.parse(
                "00 SQ 80 00 00 0d 0e 0b 41 01 RR GG BB 00 00 00 01 00 00 f0 CS:8");
        SyncEngine.ColorCodec zCodec = zengge.codec(ColorOrder.GRB);
        ZenggeFrames.Codec refCodec = new ZenggeFrames.Codec(ColorOrder.GRB);
        check(Arrays.equals(zCodec.color(200, 100, 50), refCodec.color(200, 100, 50)),
                "custom template matches ZenggeFrames step 1");
        check(Arrays.equals(zCodec.color(10, 20, 30), refCodec.color(10, 20, 30)),
                "custom template matches ZenggeFrames step 2 (rolling SQ + CS:8)");

        CustomRgbTemplate govee = CustomRgbTemplate.parse(
                "33 05 02 RR GG BB 00 00 00 00 00 00 00 00 00 00 00 00 00 XR");
        byte[] gFrame = govee.format(0xaa, 0x55, 0x0f, 0);
        int expectedXor = 0x33 ^ 0x05 ^ 0x02 ^ 0xaa ^ 0x55 ^ 0x0f;
        check(gFrame.length == 20 && (gFrame[19] & 0xff) == expectedXor, "XOR checksum");

        CustomRgbTemplate sum0 = CustomRgbTemplate.parse("aa RR GG BB CS");
        byte[] sFrame = sum0.format(10, 20, 30, 0);
        check((sFrame[4] & 0xff) == ((0xaa + 10 + 20 + 30) & 0xff), "SUM from 0 checksum");

        CustomRgbTemplate raw3 = CustomRgbTemplate.parse("RR,GG,BB");
        check(raw3.normalized().equals("RR GG BB") && Arrays.equals(raw3.format(1, 2, 3, 0), new byte[] {1, 2, 3}),
                "raw 3-byte RGB template");

        // 1-sample inference with distinct RGB values.
        CustomRgbTemplate one = CustomRgbTemplate.inferFromSample("56 12 34 56 00 f0 aa", 0x12, 0x34, 0x56);
        check(one.normalized().equals("56 RR GG BB 00 f0 aa"), "1-sample inference Triones");

        // 3-sample inference with varying channel magnitudes detects XOR checksum (Govee).
        int[][] rgb3 = {{255, 0, 0}, {0, 128, 0}, {0, 0, 64}};
        String[] gHex = {
                hex(govee.format(255, 0, 0, 0)),
                hex(govee.format(0, 128, 0, 0)),
                hex(govee.format(0, 0, 64, 0))
        };
        CustomRgbTemplate inferredGovee = CustomRgbTemplate.inferFromSamples(gHex, rgb3);
        check(inferredGovee.normalized().equals(govee.normalized()), "3-sample inference Govee XOR");

        // 3-sample inference with rolling sequence + payload sum checksum (Zengge).
        String[] zHex = {
                hex(ZenggeFrames.staticColor(1, 255, 0, 0)),
                hex(ZenggeFrames.staticColor(2, 0, 128, 0)),
                hex(ZenggeFrames.staticColor(3, 0, 0, 64))
        };
        CustomRgbTemplate inferredZengge = CustomRgbTemplate.inferFromSamples(zHex, rgb3);
        check(inferredZengge.normalized().equals(zengge.normalized()), "3-sample inference Zengge SQ + CS:8");

        // SyncEngine integration with custom template (scaled brightness + OFF=black).
        MockTransport mock = new MockTransport();
        mock.acceptObservedLotus = true;
        SyncEngine engine = SyncEngine.forScaledColor(mock, one.codec(ColorOrder.RGB), 100);
        engine.setEnabled(true);
        engine.onState(new AmbientState(200, 100, 50, 50, true));
        engine.tick(0);
        check(mock.writes.size() == 1 && hex(mock.writes.get(0)).equals("5664321900f0aa"),
                "SyncEngine scales RGB into custom template");
        engine.onState(new AmbientState(200, 100, 50, 50, false));
        engine.tick(100);
        check(mock.writes.size() == 2 && hex(mock.writes.get(1)).equals("5600000000f0aa"),
                "SyncEngine sends black for OFF on custom template");

        rejects(new Action() { public void run() { CustomRgbTemplate.parse(""); } });
        rejects(new Action() { public void run() { CustomRgbTemplate.parse("7e RR GG"); } });
        rejects(new Action() { public void run() { CustomRgbTemplate.parse("zz RR GG BB"); } });
        rejects(new Action() { public void run() { CustomRgbTemplate.parse("CS RR GG BB"); } });
        rejects(new Action() { public void run() { CustomRgbTemplate.parse("RR GG BB CS:3"); } });
        rejects(new Action() { public void run() { CustomRgbTemplate.inferFromSample("56 ff 00 00 00 f0 aa", 255, 0, 0); } });
        rejects(new Action() { public void run() { CustomRgbTemplate.inferFromSamples(new String[] {"56ff0000"}, new int[][] {{255, 0, 0}}); } });
        rejects(new Action() { public void run() {
            CustomRgbTemplate.inferFromSamples(new String[] {"56ff0000", "5600ff0001"}, new int[][] {{255, 0, 0}, {0, 255, 0}});
        } });
    }

    private static void customLampProfile() {
        check(CustomLampProfile.PRESETS.size() >= 7, "built-in presets present");
        for (CustomLampProfile.Preset p : CustomLampProfile.PRESETS) {
            check(CustomRgbTemplate.parse(p.template).packetLength() >= 3, "preset template valid: " + p.label);
        }
        check(CustomLampProfile.normalizeUuid("fff0").equals("0000fff0-0000-1000-8000-00805f9b34fb"), "16-bit UUID expand");
        check(CustomLampProfile.shortUuid("0000fff3-0000-1000-8000-00805f9b34fb").equals("FFF3"), "16-bit UUID short");
        check(CustomLampProfile.matchPresetIndex("ffff", "ff01", "LEDnetWF01") == 2, "match Zengge preset");
        check(CustomLampProfile.matchPresetIndex("ffd5", "ffd9", "Triones-123") == 3, "match Triones preset");
        check(CustomLampProfile.matchPresetIndex("fff0", "fff3", "Symphony-01") == 1, "match Symphony preset");

        CustomLampProfile p1 = new CustomLampProfile(
                "lamp1", "풋등 엠비언트", "aa:bb:cc:dd:ee:01",
                "ffd5", "ffd9", "", "56 RR GG BB 00 f0 aa",
                ColorOrder.GRB, 40, 100, 90, 80, false, false, true);
        CustomLampProfile p2 = new CustomLampProfile(
                "lamp2", "대시보드", "11:22:33:44:55:66",
                "ffff", "ff01", "ff02",
                "00 SQ 80 00 00 0d 0e 0b 41 01 RR GG BB 00 00 00 01 00 00 f0 CS:8",
                ColorOrder.RGB, 100, 100, 100, 100, true, true, false);
        check(p1.requiredMtu() == 0 && p2.requiredMtu() == 24, "required MTU calculation");
        check(p1.toColorTone(18).maxBrightness == 40 && p1.toColorTone(18).blueGain == 80, "per-lamp ColorTone");

        String encoded = CustomLampProfile.encodeList(Arrays.asList(p1, p2));
        List<CustomLampProfile> decoded = CustomLampProfile.decodeList(encoded + "\ncorrupted\tline\n");
        check(decoded.size() == 2, "profile list round-trip skips corrupted line");
        check(decoded.get(0).id.equals("lamp1") && decoded.get(0).name.equals("풋등 엠비언트")
                && decoded.get(0).address.equals("AA:BB:CC:DD:EE:01") && decoded.get(0).colorOrder == ColorOrder.GRB
                && decoded.get(0).enabled && !decoded.get(0).writeWithResponse, "profile 1 preserved");
        check(decoded.get(1).id.equals("lamp2") && decoded.get(1).notifyUuid.equals("0000ff02-0000-1000-8000-00805f9b34fb")
                && decoded.get(1).writeWithResponse && decoded.get(1).instant && !decoded.get(1).enabled, "profile 2 preserved");
        check(p2.withEnabled(true).enabled, "withEnabled toggle");

        rejects(new Action() { public void run() {
            new CustomLampProfile("id", "n", "bad-mac", "fff0", "fff3", "", "RR GG BB",
                    ColorOrder.RGB, 50, 100, 100, 100, false, false, true);
        } });
        rejects(new Action() { public void run() {
            new CustomLampProfile("id", "n", "AA:BB:CC:DD:EE:FF", "fff0", "fff3", "", "RR GG BB",
                    ColorOrder.RGB, 0, 100, 100, 100, false, false, true);
        } });
    }

    private static void vehicleProfilesAndCustomMapping() {
        // 프리셋 기본값 및 ID 조회
        check(VehicleProfile.PRESETS.size() == 3, "3 vehicle presets");
        check(VehicleProfile.byId(null).equals(VehicleProfile.K8_GL3), "null falls back to K8");
        check(VehicleProfile.byId("UNKNOWN").equals(VehicleProfile.K8_GL3), "unknown falls back to K8");
        check(VehicleProfile.byId(VehicleProfile.ID_EV6_IONIQ).equals(VehicleProfile.EV6_IONIQ), "EV6 preset lookup");
        check(VehicleProfile.byId(VehicleProfile.ID_GEN5W_AUTO).equals(VehicleProfile.GEN5W_AUTO), "Gen5W auto lookup");
        check(!VehicleProfile.K8_GL3.allowAdaptiveRange && VehicleProfile.EV6_IONIQ.allowAdaptiveRange
                && VehicleProfile.GEN5W_AUTO.allowAdaptiveRange, "adaptive defaults");

        // K8 기본 프로필은 기존 범위 밖 raw(0, 12)를 계속 null로 거부
        K8LotusMapping k8 = new K8LotusMapping(VehicleProfile.K8_GL3);
        check(k8.map(rawLevel(0)) == null && k8.map(rawLevel(12)) == null, "K8 rejects out-of-range raw");
        check(!k8.map(rawLevel(1)).on && k8.map(rawLevel(1)).brightness == 0, "K8 raw 1 is OFF");
        check(k8.map(rawLevel(2)).brightness == 10 && k8.map(rawLevel(11)).brightness == 100, "K8 raw 2..11");

        // 0=꺼짐, 1~10=밝기 차종 프로필 테스트
        VehicleProfile zeroToTen = VehicleProfile.GEN5W_AUTO.withBrightnessRange(0, 1, 10, false);
        K8LotusMapping map010 = new K8LotusMapping(zeroToTen);
        check(!map010.map(rawLevel(0)).on && map010.map(rawLevel(0)).brightness == 0, "0..10 raw 0 is OFF");
        check(map010.map(rawLevel(1)).on && map010.map(rawLevel(1)).brightness == 10, "0..10 raw 1 is 10%");
        check(map010.map(rawLevel(5)).brightness == 50 && map010.map(rawLevel(10)).brightness == 100, "0..10 linear");
        check(map010.map(rawLevel(11)) == null, "0..10 non-adaptive rejects 11");

        // 0=꺼짐, 1~100=밝기 차종 프로필 테스트
        VehicleProfile zeroToHundred = VehicleProfile.GEN5W_AUTO.withBrightnessRange(0, 1, 100, false);
        K8LotusMapping map0100 = new K8LotusMapping(zeroToHundred);
        check(!map0100.map(rawLevel(0)).on, "0..100 raw 0 is OFF");
        check(map0100.map(rawLevel(1)).brightness == 1 && map0100.map(rawLevel(50)).brightness == 50
                && map0100.map(rawLevel(100)).brightness == 100, "0..100 percentage");

        // 자동 적응 모드: 범위 밖 낮은 값은 꺼짐, 높은 값은 상한 자동 확장 학습 + 비례 정규화
        K8LotusMapping adaptive = new K8LotusMapping(VehicleProfile.GEN5W_AUTO);
        check(!adaptive.map(rawLevel(0)).on && adaptive.map(rawLevel(0)).brightness == 0, "adaptive raw 0 treated as OFF");
        check(!adaptive.map(rawLevel(1)).on && adaptive.map(rawLevel(1)).brightness == 0, "adaptive raw 1 is OFF");
        check(adaptive.map(rawLevel(6)).brightness == 50 && adaptive.effectiveMaxOnRaw() == 11, "adaptive within initial range");
        AmbientState expanded = adaptive.map(rawLevel(101));
        check(expanded.on && expanded.brightness == 100 && adaptive.effectiveMaxOnRaw() == 101, "adaptive expands upper bound");
        AmbientState mid = adaptive.map(rawLevel(51));
        check(mid.on && mid.brightness == 50, "adaptive rescales after learning max=101");

        // 꺼짐 원시값 없음(-1) + 1단계(min==max) 특수 케이스
        VehicleProfile singleStep = VehicleProfile.GEN5W_AUTO.withBrightnessRange(-1, 5, 5, false);
        K8LotusMapping singleMap = new K8LotusMapping(singleStep);
        check(singleMap.map(rawLevel(5)).on && singleMap.map(rawLevel(5)).brightness == 100, "single step maps to 100%");
        check(singleMap.map(rawLevel(1)) == null, "non-adaptive without offRaw rejects other values");

        // 드라이브 모드 코드 원터치 할당 및 충돌 해소
        VehicleProfile customModes = VehicleProfile.EV6_IONIQ
                .withAssignedModeCode(VehicleProfile.ROLE_ECO, 0x04)
                .withAssignedModeCode(VehicleProfile.ROLE_SPORT, 0x05)
                .withAssignedModeCode(VehicleProfile.ROLE_NORMAL, 0x01);
        check(customModes.ecoModeCode == 0x04 && customModes.sportModeCode == 0x05 && customModes.normalModeCode == 0x01,
                "custom mode codes assigned");
        // 이미 에코(0x04)로 지정된 코드를 스포츠로 재지정하면 에코에서 충돌 해제(0)
        VehicleProfile reassigned = customModes.withAssignedModeCode(VehicleProfile.ROLE_SPORT, 0x04);
        check(reassigned.sportModeCode == 0x04 && reassigned.ecoModeCode == 0, "mode role conflict cleared");
        check(customModes.modeLabel(0x04).contains("에코") && customModes.modeLabel(0x05).contains("스포츠")
                && customModes.modeLabel(0x01).contains("일반") && customModes.modeLabel(0x09).contains("0x09")
                && customModes.modeLabel(0).contains("아직 모름"), "mode labels");

        // DriveModeLight.fromProfile: 커스텀 드라이브 모드 코드(0x04 에코, 0x05 스포츠, 0x01 일반)
        SmlStatus linked = SmlStatus.parse(new byte[] {0, 0x10, 0, 0x20, 0, 0x30, 11, 1, 2});
        SmlStatus unlinked = SmlStatus.parse(new byte[] {0, 0x10, 0, 0x20, 0, 0x30, 11, 1, 1});
        AmbientState base = new K8LotusMapping(customModes).map(linked);
        DriveModeLight customLight = DriveModeLight.fromProfile(customModes, new int[] {10, 200, 180}, new int[] {255, 20, 10});
        AmbientState ecoState = customLight.apply(base, linked, 0x04, customModes);
        AmbientState sportState = customLight.apply(base, linked, 0x05, customModes);
        AmbientState normalState = customLight.apply(base, linked, 0x01, customModes);
        AmbientState k8EcoIgnored = customLight.apply(base, linked, 0x02, customModes);
        check(ecoState.red == 10 && ecoState.green == 200 && ecoState.blue == 180, "custom eco mode code applies eco color");
        check(sportState.red == 255 && sportState.green == 20 && sportState.blue == 10, "custom sport mode code applies sport color");
        check(normalState == base, "normal mode keeps user color");
        check(k8EcoIgnored == base, "unmapped code keeps user color");
        check(customLight.apply(base, unlinked, 0x05, customModes) == base, "unlinked status ignores mode override");
        customLight.clearModeColor(0x05);
        check(customLight.apply(base, linked, 0x05, customModes) == base, "clearModeColor restores user color");

        // 잘못된 프로필 입력 거부
        rejects(new Action() { public void run() { VehicleProfile.K8_GL3.withBrightnessRange(5, 2, 11, false); } });
        rejects(new Action() { public void run() { VehicleProfile.K8_GL3.withBrightnessRange(1, 12, 11, false); } });
        rejects(new Action() { public void run() { VehicleProfile.K8_GL3.withBrightnessRange(-2, 1, 10, false); } });
        rejects(new Action() { public void run() { VehicleProfile.K8_GL3.withAssignedModeCode(VehicleProfile.ROLE_ECO, 0); } });
        rejects(new Action() { public void run() { VehicleProfile.K8_GL3.withAssignedModeCode("bad", 2); } });
        rejects(new Action() { public void run() { new K8LotusMapping(null); } });
        rejects(new Action() { public void run() { new DriveModeLight().setModeColor(0, 1, 2, 3); } });
    }

    private static SmlStatus sml(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return SmlStatus.parse(b);
    }

    private static void sportBaseColor() {
        SmlStatus purple = sml("00a9002300ef0b0101");
        SmlStatus red = sml("00fa000400510b0101");
        SmlStatus oldRed = sml("00ff000000000b0101");
        SportBaseColor s = new SportBaseColor();
        s.onDriveMode(DriveModeLight.SMART);
        s.onMyDrive(new int[] {4, 2, 2});
        check(!s.sportActive(), "my drive eco is not sport");
        s.onNormalStatus(purple);
        check(s.base() == purple, "normal color becomes base");
        s.onMyDrive(new int[] {5, 2, 2});
        check(s.sportActive(), "engine sport in my drive");
        s.onNormalStatus(red);
        check(s.base() == purple, "module sport red is not the base");
        check(SportBaseColor.isSportRed(oldRed) && !SportBaseColor.isSportRed(purple), "known sport reds");
        // Alert while sport, sport ends during the alert: restore the base, not the stale red.
        s.onMyDrive(new int[] {4, 2, 2});
        check(s.afterAlert(red) == purple, "alert end after sport ended -> base color");
        // Still sport when the alert ends: keep the red.
        s.onMyDrive(new int[] {4, 5, 2});
        check(s.afterAlert(red) == red, "alert end while still sport -> red");
        // A real new color (not sport red) is shown as is.
        s.onMyDrive(new int[] {4, 2, 2});
        SmlStatus green = sml("000a00f000100b0101");
        check(s.afterAlert(green) == green, "non-red last status is kept");
        // Red chosen by the user outside sport is a normal base color.
        s.onNormalStatus(red);
        check(s.base() == red, "user red outside sport is the base");
        // Stock sport mode counts as sport; unknown base falls back to the last status.
        SportBaseColor fresh = new SportBaseColor();
        fresh.onDriveMode(DriveModeLight.SPORT);
        check(fresh.sportActive() && fresh.afterAlert(red) == red, "stock sport keeps red");
        fresh.onDriveMode(DriveModeLight.ECO);
        check(fresh.afterAlert(red) == red, "no base yet -> last status");
        fresh.onNormalStatus(null);
        fresh.onMyDrive(null);
        check(!fresh.sportActive(), "eco without feedback is not sport");
    }

    public static void main(String[] args) {
        sportBaseColor();
        wifiKeeperPolicy();
        customRgbTemplate();
        customLampProfile();
        vehicleProfilesAndCustomMapping();
        stateAndRawStatus();
        packets();
        k8ProvisionalBrightness();
        k8CapturedStatusesUnderOffLadderCandidate();
        relayProtocol();
        telemetryProtocol();
        k8LotusMapping();
        colorTone();
        hueBrightness();
        fader();
        zengge();
        driveModeLight();
        myDriveProtocol();
        directColorDriveModeReplay();
        predictedStatus();
        observedLotusReplay();
        lotusStaticPower();
        lotusStartupFade();
        zenggeStartupFade();
        cameraWarningRecoveryFade();
        zenggeCameraPulses();
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
