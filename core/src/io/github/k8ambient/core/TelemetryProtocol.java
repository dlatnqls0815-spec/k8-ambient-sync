package io.github.k8ambient.core;

import java.util.Locale;
import java.util.UUID;

/**
 * Read-only driving telemetry the K8 car relay serves to the phone engine-sound app over a second
 * RFCOMM channel, plus the parsers for the OEM SystemService log lines it comes from. Java 7.
 *
 * <p>Car to phone, one ASCII line each: {@code K8TEL 1} on connect, then
 * {@code TEL <kmh> <park> <reverse> <mode>} whenever a value changes and at least every
 * {@link #HEARTBEAT_MS} ms. Each field is a decimal integer, {@code -1} when not seen yet:
 * speed in whole km/h as the head unit reports it; park 1 = P, 0 = not P; reverse 1/0; drive mode
 * code (2 eco, 3 sport, 6 smart). The head unit sends speed only when it changes, so a steady
 * speed is repeated by the heartbeat; a phone that hears nothing for a few seconds must treat the
 * data as stale. The car carries no engine RPM or pedal value (checked 2026-11-09), so the phone
 * keeps computing a virtual RPM. The phone never sends anything.
 */
public final class TelemetryProtocol {
    /** Separate SDP service so the ambient light client keeps its own channel. */
    public static final UUID SERVICE_UUID = UUID.fromString("9f2d4c61-3b8e-4a07-b5d1-6c0e8a47f213");
    public static final String HELLO = "K8TEL 1";
    public static final long HEARTBEAT_MS = 500;
    public static final int UNKNOWN = -1;
    static final int MAX_SPEED_KMH = 300;
    private static final String SPEED_MARKER = "SYS_SPEED_M:";
    private static final String GEAR_MARKER = "SYS_GEAR_STATUS_M:";
    private static final String REVERSE_MARKER = "Reverse status changed to :";

    private TelemetryProtocol() {}

    /** One snapshot. Immutable; {@link #UNKNOWN} marks a value not seen yet. */
    public static final class Snapshot {
        public final int speedKmh, park, reverse, mode;

        public Snapshot(int speedKmh, int park, int reverse, int mode) {
            if (speedKmh < UNKNOWN || speedKmh > MAX_SPEED_KMH) throw new IllegalArgumentException("speed");
            if (park < UNKNOWN || park > 1 || reverse < UNKNOWN || reverse > 1) throw new IllegalArgumentException("flag");
            if (mode < UNKNOWN || mode > 255 || mode == 0) throw new IllegalArgumentException("mode");
            this.speedKmh = speedKmh;
            this.park = park;
            this.reverse = reverse;
            this.mode = mode;
        }

        public static Snapshot empty() {
            return new Snapshot(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);
        }

        public Snapshot withSpeed(int value) { return new Snapshot(value, park, reverse, mode); }
        public Snapshot withPark(boolean value) { return new Snapshot(speedKmh, value ? 1 : 0, reverse, mode); }
        public Snapshot withReverse(boolean value) { return new Snapshot(speedKmh, park, value ? 1 : 0, mode); }
        public Snapshot withMode(int value) { return new Snapshot(speedKmh, park, reverse, value); }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Snapshot)) return false;
            Snapshot s = (Snapshot) other;
            return s.speedKmh == speedKmh && s.park == park && s.reverse == reverse && s.mode == mode;
        }

        @Override public int hashCode() {
            return ((speedKmh * 31 + park) * 31 + reverse) * 31 + mode;
        }

        @Override public String toString() { return encode(this); }
    }

    public static String encode(Snapshot s) {
        return String.format(Locale.US, "TEL %d %d %d %d", s.speedKmh, s.park, s.reverse, s.mode);
    }

    /** The snapshot of a {@code TEL} line, or null for HELLO/PING-like or malformed lines. */
    public static Snapshot decode(String line) {
        if (line == null) return null;
        String[] parts = line.trim().split(" ");
        if (parts.length != 5 || !"TEL".equals(parts[0])) return null;
        try {
            return new Snapshot(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]), Integer.parseInt(parts[4]));
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }

    /** Speed from {@code D/SystemService( 693): SYS_SPEED_M: 42}; null for other lines. */
    public static Integer parseSpeedLogLine(String line) {
        Integer value = intAfter(line, SPEED_MARKER);
        return value == null || value.intValue() > MAX_SPEED_KMH ? null : value;
    }

    /**
     * P state from {@code D/SystemService( 693): SYS_GEAR_STATUS_M: 0}. In the 2026-11-09 log 0 was
     * always followed by "Gear status changed to : P(0x0)" and 1 by "NONE(0xff)" (shift-by-wire
     * reports only P versus not-P here), so 0 = P and any other value = not P.
     */
    public static Boolean parseParkLogLine(String line) {
        Integer value = intAfter(line, GEAR_MARKER);
        return value == null ? null : Boolean.valueOf(value.intValue() == 0);
    }

    /** Reverse from {@code V/SystemService( 693): Reverse status changed to : true}. */
    public static Boolean parseReverseLogLine(String line) {
        if (line == null) return null;
        int at = line.indexOf(REVERSE_MARKER);
        if (at < 0) return null;
        String rest = line.substring(at + REVERSE_MARKER.length()).trim();
        if (rest.equals("true")) return Boolean.TRUE;
        if (rest.equals("false")) return Boolean.FALSE;
        return null;
    }

    private static Integer intAfter(String line, String marker) {
        if (line == null) return null;
        int at = line.indexOf(marker);
        if (at < 0) return null;
        String rest = line.substring(at + marker.length()).trim();
        if (rest.isEmpty() || rest.length() > 4) return null;
        for (int i = 0; i < rest.length(); i++) if (!Character.isDigit(rest.charAt(i))) return null;
        return Integer.valueOf(rest);
    }
}
