package io.github.k8ambient.phone;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Process-wide status strings shown by the activity. Written by the service threads. */
final class RelayStatus {
    static volatile boolean running;
    static final int LOTUS = 0, ZENGGE = 1;
    static volatile String car = "-", raw = "-", target = "-";
    static volatile String zenggeWarningWrites = "0 / 0";
    static final String[] lamp = {"-", "-"}, lastSent = {"-", "-"};
    static final long[] packets = new long[2];
    static volatile String themeKey;
    static volatile int driveMode;
    static volatile boolean driveLinked;

    private RelayStatus() {}

    static void car(String text) {
        car = stamp(text);
        // "연결 중" alternates with the failure line while the car is off; skipping it lets the log
        // collapse hours of retries into one line with a count.
        if (!"연결 중".equals(text)) DiagLog.event("car", text);
    }

    static void lamp(int slot, String text) {
        lamp[slot] = stamp(text);
        DiagLog.event(lampTag(slot), text);
    }

    static String lampTag(int slot) { return slot == LOTUS ? "lotus" : "zengge"; }

    static void sent(int slot, byte[] packet) {
        packets[slot]++;
        StringBuilder hex = new StringBuilder();
        for (byte b : packet) hex.append(String.format(Locale.US, "%02x", b & 255));
        lastSent[slot] = stamp(hex.toString());
    }

    static String stamp(String text) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + text;
    }

    static void reset() {
        car = raw = target = "-";
        zenggeWarningWrites = "0 / 0";
        for (int i = 0; i < 2; i++) { lamp[i] = "-"; lastSent[i] = "-"; packets[i] = 0; }
        themeKey = null;
    }
}
