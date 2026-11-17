package io.github.k8ambient.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Settings hand-over from the phone app to the ESP32 (plan C, since phone 0.5.0).
 *
 * <p>The ESP32 drives the lamps itself; the phone only sends what its own relay used to apply:
 * car and lamp addresses, Zengge order, Lotus power option, fade, tone, maximum brightness per
 * lamp, per-color brightness, drive mode colors and theme colors. One text line per BLE write to
 * {@link #RX_UUID}; the ESP32 answers on {@link #TX_UUID} notifications ({@code OK ...},
 * {@code ERR ...}, {@code CFG ...}, {@code ST ...}). The same lines work on the ESP32 USB serial.
 * Values are clamped to the ranges the phone app uses; the ESP32 validates them again.
 */
public final class EspConfig {
    public static final UUID SERVICE_UUID = UUID.fromString("6b38a0d2-4f7e-4c1b-9d5a-2e8f71c3b9e0");
    public static final UUID RX_UUID = UUID.fromString("6b38a0d2-4f7e-4c1b-9d5a-2e8f71c3b9e1");
    public static final UUID TX_UUID = UUID.fromString("6b38a0d2-4f7e-4c1b-9d5a-2e8f71c3b9e2");
    /** BLE name the ESP32 advertises. */
    public static final String DEVICE_NAME = "K8AMB-ESP32";
    /** Longest line either side sends; the phone requests an ATT MTU above this. */
    public static final int MAX_LINE = 120;
    private static final Pattern ADDRESS = Pattern.compile("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}");
    private static final Pattern THEME_KEY = Pattern.compile("[0-9a-f]{12}");

    /** The values the phone app keeps (see phone Prefs). */
    public static final class Values {
        public String car, lotus, zengge;
        public ColorOrder order = ColorOrder.GRB;
        public boolean lotusPower;
        public int fadeMs = 300, gammaTenths = 18, redGain = 100, greenGain = 100, blueGain = 85;
        public int lotusMax = 20, zenggeMax = 100;
        public int[] hue = {65, 150, 100, 100, 65, 65};
        public int[] eco = {50, 154, 158}, sport = {255, 0, 0};
        public Map<String, int[]> themes;
    }

    private EspConfig() {}

    /** Full snapshot ending in SAVE. Does not include the lamp output switch (sent on its own). */
    public static List<String> snapshot(Values v) {
        if (v == null) throw new IllegalArgumentException("Values are required");
        List<String> lines = new ArrayList<String>();
        lines.add("SET car " + address(v.car));
        lines.add("SET lotus " + address(v.lotus));
        lines.add("SET zengge " + address(v.zengge));
        lines.add("SET order " + (v.order == null ? ColorOrder.GRB : v.order).name());
        lines.add("SET power " + (v.lotusPower ? 1 : 0));
        lines.add("SET fade " + clamp(v.fadeMs, 0, 5000));
        lines.add("SET gamma " + clamp(v.gammaTenths, 10, 30));
        lines.add("SET red " + clamp(v.redGain, 0, 100));
        lines.add("SET green " + clamp(v.greenGain, 0, 100));
        lines.add("SET blue " + clamp(v.blueGain, 0, 100));
        lines.add("SET lotusMax " + clamp(v.lotusMax, 1, 100));
        lines.add("SET zenggeMax " + clamp(v.zenggeMax, 1, 100));
        lines.add("SET hue " + list(v.hue, 6, HueBrightness.MIN_RATIO, HueBrightness.MAX_RATIO));
        lines.add("SET eco " + list(v.eco, 3, 0, 255));
        lines.add("SET sport " + list(v.sport, 3, 0, 255));
        lines.add("THEMES CLEAR");
        if (v.themes != null) {
            for (Map.Entry<String, int[]> entry : v.themes.entrySet()) {
                if (entry.getKey() == null || !THEME_KEY.matcher(entry.getKey()).matches()) continue;
                lines.add("THEME " + entry.getKey() + " " + list(entry.getValue(), 3, 0, 255));
            }
        }
        lines.add("SAVE");
        return lines;
    }

    /** Lamp output switch: ESP32 BLE writes to the lamps (off = the ESP32 only logs frames). */
    public static String output(boolean on) {
        return "SET output " + (on ? 1 : 0);
    }

    /** "-" for none; rejects anything that is not a Bluetooth address. */
    static String address(String text) {
        if (text == null || text.length() == 0) return "-";
        if (!ADDRESS.matcher(text).matches()) throw new IllegalArgumentException("Bad Bluetooth address");
        return text.toUpperCase(Locale.US);
    }

    private static String list(int[] values, int size, int low, int high) {
        if (values == null || values.length != size) throw new IllegalArgumentException("Need " + size + " values");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) out.append(',');
            out.append(clamp(values[i], low, high));
        }
        return out.toString();
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    /** Parses one {@code ST key value} status reply into {key, value}, or null. */
    public static String[] parseStatus(String line) {
        if (line == null) return null;
        String text = line.trim();
        if (!text.startsWith("ST ")) return null;
        int space = text.indexOf(' ', 3);
        if (space < 0) return new String[] {text.substring(3), ""};
        return new String[] {text.substring(3, space), text.substring(space + 1)};
    }
}
