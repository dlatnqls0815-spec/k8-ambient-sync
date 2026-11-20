package io.github.k8ambient.phone;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Process-wide status strings shown by the activity. Written by the service threads. */
final class RelayStatus {
    static volatile boolean running;
    static final int LOTUS = 0, ZENGGE = 1, CUSTOM = -1;
    static volatile String car = "-", raw = "-", target = "-", myDrive = "-";
    static volatile String zenggeWarningWrites = "0 / 0";
    static final String[] lamp = {"-", "-"}, lastSent = {"-", "-"};
    static final long[] packets = new long[2];
    static final Map<String, String> customLamp = new ConcurrentHashMap<String, String>();
    static final Map<String, String> customLastSent = new ConcurrentHashMap<String, String>();
    static final Map<String, Long> customPackets = new ConcurrentHashMap<String, Long>();
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

    static void customLamp(String id, String text) {
        customLamp.put(id, stamp(text));
        DiagLog.event("custom:" + id, text);
    }

    static String lampTag(int slot) { return slot == LOTUS ? "lotus" : "zengge"; }

    static void sent(int slot, byte[] packet) {
        packets[slot]++;
        lastSent[slot] = stamp(toHex(packet));
    }

    static void customSent(String id, byte[] packet) {
        Long prev = customPackets.get(id);
        customPackets.put(id, Long.valueOf((prev == null ? 0L : prev.longValue()) + 1L));
        customLastSent.put(id, stamp(toHex(packet)));
    }

    static void clearCustom(String id) {
        customLamp.remove(id);
        customLastSent.remove(id);
        customPackets.remove(id);
    }

    private static String toHex(byte[] packet) {
        StringBuilder hex = new StringBuilder();
        for (byte b : packet) hex.append(String.format(Locale.US, "%02x", b & 255));
        return hex.toString();
    }

    static String stamp(String text) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + text;
    }

    static void reset() {
        car = raw = target = myDrive = "-";
        zenggeWarningWrites = "0 / 0";
        for (int i = 0; i < 2; i++) { lamp[i] = "-"; lastSent[i] = "-"; packets[i] = 0; }
        customLamp.clear();
        customLastSent.clear();
        customPackets.clear();
        themeKey = null;
    }
}
