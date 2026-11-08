package io.github.k8ambient.phone;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Process-wide status strings shown by the activity. Written by the service threads. */
final class RelayStatus {
    static volatile boolean running;
    static volatile String car = "-", lamp = "-", raw = "-", target = "-", lastSent = "-";
    static volatile String themeKey;
    static volatile long packets;

    private RelayStatus() {}

    static void car(String text) { car = stamp(text); }
    static void lamp(String text) { lamp = stamp(text); }

    static void sent(byte[] packet) {
        packets++;
        StringBuilder hex = new StringBuilder();
        for (byte b : packet) hex.append(String.format(Locale.US, "%02x", b & 255));
        lastSent = stamp(hex.toString());
    }

    static String stamp(String text) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + text;
    }

    static void reset() {
        car = lamp = raw = target = lastSent = "-";
        themeKey = null;
        packets = 0;
    }
}
