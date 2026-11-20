package io.github.k8ambient.phone;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.k8ambient.core.ColorOrder;
import io.github.k8ambient.core.ColorTone;
import io.github.k8ambient.core.CustomLampProfile;
import io.github.k8ambient.core.DriveModeLight;
import io.github.k8ambient.core.HueBrightness;
import io.github.k8ambient.core.K8LotusMapping;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Saved car/lamp addresses, custom aftermarket lamps, experimental power option and user theme colors. */
final class Prefs {
    private static final String NAME = "relay", CAR = "car", LAMP = "lamp", POWER = "power", THEMES = "themes";
    private static final String CUSTOM_LAMPS = "customLamps", CUSTOM_SEQ = "customSeq";
    private final SharedPreferences prefs;

    Prefs(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
        // One-time: the user asked for a faster response, so a saved slower fade drops to the new default.
        if (!prefs.getBoolean("fastFade", false)) {
            SharedPreferences.Editor editor = prefs.edit().putBoolean("fastFade", true);
            if (prefs.getInt(FADE_MS, DEFAULT_FADE_MS) > DEFAULT_FADE_MS) editor.putInt(FADE_MS, DEFAULT_FADE_MS);
            editor.apply();
        }
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

    /** All user-registered custom aftermarket ambient lamps. */
    List<CustomLampProfile> customLamps() {
        return CustomLampProfile.decodeList(prefs.getString(CUSTOM_LAMPS, ""));
    }

    List<CustomLampProfile> enabledCustomLamps() {
        List<CustomLampProfile> out = new ArrayList<CustomLampProfile>();
        for (CustomLampProfile p : customLamps()) if (p.enabled) out.add(p);
        return out;
    }

    boolean hasEnabledCustomLamp() {
        return !enabledCustomLamps().isEmpty();
    }

    String nextCustomId() {
        int next = prefs.getInt(CUSTOM_SEQ, 0) + 1;
        prefs.edit().putInt(CUSTOM_SEQ, next).apply();
        return "lamp" + next;
    }

    void saveCustomLamp(CustomLampProfile profile) {
        List<CustomLampProfile> list = customLamps();
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(profile.id)) {
                list.set(i, profile);
                replaced = true;
                break;
            }
        }
        if (!replaced) list.add(profile);
        prefs.edit().putString(CUSTOM_LAMPS, CustomLampProfile.encodeList(list)).apply();
    }

    void removeCustomLamp(String id) {
        List<CustomLampProfile> list = customLamps();
        List<CustomLampProfile> kept = new ArrayList<CustomLampProfile>();
        for (CustomLampProfile p : list) if (!p.id.equals(id)) kept.add(p);
        prefs.edit().putString(CUSTOM_LAMPS, CustomLampProfile.encodeList(kept)).apply();
    }

    void setCustomLampEnabled(String id, boolean enabled) {
        List<CustomLampProfile> list = customLamps();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.set(i, list.get(i).withEnabled(enabled));
                break;
            }
        }
        prefs.edit().putString(CUSTOM_LAMPS, CustomLampProfile.encodeList(list)).apply();
    }

    boolean anyLamp() { return lamp() != null || zengge() != null || hasEnabledCustomLamp(); }
    boolean power() { return prefs.getBoolean(POWER, false); }

    /** Tone/fade settings. Defaults: deeper colors (gamma 1.8), slightly less blue, 20 % max, 0.3 s fade (0.8 s until 0.4.1). */
    static final String FADE_MS = "fadeMs", GAMMA = "gamma10", RED = "redGain", GREEN = "greenGain", BLUE = "blueGain", MAX = "maxBrightness";
    /** Zengge maximum (lamp %), separate because the user's Zengge strip is calibrated at 100 %. */
    static final String ZENGGE_MAX = "zenggeMaxBrightness";

    static final int DEFAULT_FADE_MS = 300;

    int get(String key) {
        for (int i = 0; i < HUE_KEYS.length; i++) if (HUE_KEYS[i].equals(key)) return hueRatio(i);
        if (FADE_MS.equals(key)) return prefs.getInt(key, DEFAULT_FADE_MS);
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

    /** Per-lamp tone for a custom aftermarket ambient lamp (uses shared gamma + lamp's own RGB gains & max). */
    ColorTone toneForCustom(CustomLampProfile profile) {
        return profile.toColorTone(clamp(get(GAMMA), 10, 30));
    }

    long fadeMs() { return clamp(get(FADE_MS), 0, 5000); }

    /** Per-color brightness ratio keys (red, warm, green, sky, blue, purple), percent of the lamp maximum. */
    static final String[] HUE_KEYS = {"hueRed", "hueWarm", "hueGreen", "hueSky", "hueBlue", "huePurple"};

    int hueRatio(int anchor) {
        return clamp(prefs.getInt(HUE_KEYS[anchor], HueBrightness.USER_DEFAULT.ratio(anchor)),
                HueBrightness.MIN_RATIO, HueBrightness.MAX_RATIO);
    }

    HueBrightness hueBrightness() {
        int[] ratios = new int[HUE_KEYS.length];
        for (int i = 0; i < ratios.length; i++) ratios[i] = hueRatio(i);
        return new HueBrightness(ratios);
    }

    private static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }

    /** True while the user wants the relay running; auto-start on car connect only then. */
    boolean auto() { return prefs.getBoolean("auto", false); }
    void setAuto(boolean value) { prefs.edit().putBoolean("auto", value).apply(); }

    void setCar(String address) { prefs.edit().putString(CAR, address).apply(); }
    void setLamp(String address) { prefs.edit().putString(LAMP, address).apply(); }
    void setPower(boolean value) { prefs.edit().putBoolean(POWER, value).apply(); }

    /** Factory drive mode colors (ECO "Refreshing Ocean", SPORT red); SMART uses the user color. */
    static final String DRIVE_ECO = "driveEco", DRIVE_SPORT = "driveSport";
    static final String DEFAULT_ECO = "50,154,158", DEFAULT_SPORT = "255,0,0";
    int[] driveColor(String key) {
        int[] rgb = parseRgb(prefs.getString(key, null));
        return rgb != null ? rgb : parseRgb(DRIVE_ECO.equals(key) ? DEFAULT_ECO : DEFAULT_SPORT);
    }

    void setDriveColor(String key, int[] rgb) {
        prefs.edit().putString(key, rgb[0] + "," + rgb[1] + "," + rgb[2]).apply();
    }

    DriveModeLight driveModeLight() {
        DriveModeLight light = new DriveModeLight();
        int[] eco = driveColor(DRIVE_ECO), sport = driveColor(DRIVE_SPORT);
        light.setColor(DriveModeLight.ECO, eco[0], eco[1], eco[2]);
        light.setColor(DriveModeLight.SPORT, sport[0], sport[1], sport[2]);
        return light;
    }

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
        return parseTriple(text, 0, 255);
    }

    /** Parses {@code a,b,c} with each value in {@code min..max}; returns null otherwise. */
    static int[] parseTriple(String text, int min, int max) {
        if (text == null) return null;
        String[] parts = text.trim().split("\\s*,\\s*");
        if (parts.length != 3) return null;
        int[] out = new int[3];
        try {
            for (int i = 0; i < 3; i++) {
                out[i] = Integer.parseInt(parts[i]);
                if (out[i] < min || out[i] > max) return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }
}
