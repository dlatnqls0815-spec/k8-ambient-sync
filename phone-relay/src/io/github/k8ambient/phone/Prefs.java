package io.github.k8ambient.phone;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.k8ambient.core.ColorOrder;
import io.github.k8ambient.core.ColorTone;
import io.github.k8ambient.core.K8LotusMapping;
import java.util.Map;
import java.util.TreeMap;

/** Saved car/lamp addresses, the experimental power option and user theme colors. */
final class Prefs {
    private static final String NAME = "relay", CAR = "car", LAMP = "lamp", POWER = "power", THEMES = "themes";
    private final SharedPreferences prefs;

    Prefs(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    String car() { return prefs.getString(CAR, null); }
    /** LotusLamp X address, or null. */
    String lamp() { return prefs.getString(LAMP, null); }
    /** Zengge/LEDnetWF address, or null. */
    String zengge() { return prefs.getString("zengge", null); }
    void setZengge(String address) { prefs.edit().putString("zengge", address).apply(); }
    /** The user's strip shows green for red and red for green, so GRB is the default. */
    ColorOrder zenggeOrder() { return ColorOrder.parse(prefs.getString("zenggeOrder", "GRB")); }
    void setZenggeOrder(ColorOrder order) { prefs.edit().putString("zenggeOrder", order.name()).apply(); }
    boolean anyLamp() { return lamp() != null || zengge() != null; }
    boolean power() { return prefs.getBoolean(POWER, false); }

    /** Tone/fade settings. Defaults: deeper colors (gamma 1.8), slightly less blue, 20 % max, 0.8 s fade. */
    static final String FADE_MS = "fadeMs", GAMMA = "gamma10", RED = "redGain", GREEN = "greenGain", BLUE = "blueGain", MAX = "maxBrightness";
    /** Zengge maximum (lamp %), separate because the user's Zengge strip is calibrated at 100 %. */
    static final String ZENGGE_MAX = "zenggeMaxBrightness";

    int get(String key) {
        if (FADE_MS.equals(key)) return prefs.getInt(key, 800);
        if (ZENGGE_MAX.equals(key)) return prefs.getInt(key, 100);
        if (GAMMA.equals(key)) return prefs.getInt(key, 18);
        if (BLUE.equals(key)) return prefs.getInt(key, 85);
        if (MAX.equals(key)) return prefs.getInt(key, 20);
        return prefs.getInt(key, 100);
    }

    void set(String key, int value) { prefs.edit().putInt(key, value).apply(); }

    /** Shared color tone; the maximum brightness is per lamp type. */
    ColorTone tone(boolean zengge) {
        return new ColorTone(clamp(get(GAMMA), 10, 30), clamp(get(RED), 0, 100), clamp(get(GREEN), 0, 100),
                clamp(get(BLUE), 0, 100), clamp(get(zengge ? ZENGGE_MAX : MAX), 1, 100));
    }

    long fadeMs() { return clamp(get(FADE_MS), 0, 5000); }

    private static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }

    /** True while the user wants the relay running; auto-start on car connect only then. */
    boolean auto() { return prefs.getBoolean("auto", false); }
    void setAuto(boolean value) { prefs.edit().putBoolean("auto", value).apply(); }

    void setCar(String address) { prefs.edit().putString(CAR, address).apply(); }
    void setLamp(String address) { prefs.edit().putString(LAMP, address).apply(); }
    void setPower(boolean value) { prefs.edit().putBoolean(POWER, value).apply(); }

    /** Theme colors as {@code key=r,g,b;key=r,g,b}. Invalid entries are skipped. */
    Map<String, int[]> themes() {
        Map<String, int[]> out = new TreeMap<String, int[]>();
        String text = prefs.getString(THEMES, "");
        for (String entry : text.split(";")) {
            String[] parts = entry.split("=");
            if (parts.length != 2) continue;
            int[] rgb = parseRgb(parts[1]);
            if (rgb != null && parts[0].matches("[0-9a-f]{12}")) out.put(parts[0], rgb);
        }
        return out;
    }

    void setTheme(String key, int[] rgb) {
        Map<String, int[]> map = themes();
        if (rgb == null) map.remove(key);
        else map.put(key, rgb);
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, int[]> entry : map.entrySet()) {
            if (text.length() > 0) text.append(';');
            int[] c = entry.getValue();
            text.append(entry.getKey()).append('=').append(c[0]).append(',').append(c[1]).append(',').append(c[2]);
        }
        prefs.edit().putString(THEMES, text.toString()).apply();
    }

    void applyThemes(K8LotusMapping mapping) {
        for (Map.Entry<String, int[]> entry : themes().entrySet()) {
            int[] c = entry.getValue();
            mapping.setThemeColor(entry.getKey(), c[0], c[1], c[2]);
        }
    }

    /** Parses {@code r,g,b} with each value 0..255; returns null otherwise. */
    static int[] parseRgb(String text) {
        if (text == null) return null;
        String[] parts = text.trim().split("\\s*,\\s*");
        if (parts.length != 3) return null;
        int[] rgb = new int[3];
        try {
            for (int i = 0; i < 3; i++) {
                rgb[i] = Integer.parseInt(parts[i]);
                if (rgb[i] < 0 || rgb[i] > 255) return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return rgb;
    }
}
