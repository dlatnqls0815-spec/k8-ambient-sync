package io.github.k8ambient.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Configuration for a user-registered aftermarket BLE ambient lamp (pure Java 7).
 *
 * <p>Any BLE ambient lamp can be added by choosing its BLE device, GATT write characteristic,
 * RGB packet template, and RGB calibration (order, RGB ratios, maximum brightness).
 * Brightness and OFF are handled via RGB scaling ({@link SyncEngine#forScaledColor}), so no
 * separate brightness or power packet is required.
 */
public final class CustomLampProfile {
    /** Built-in protocol preset for quick one-tap setup. */
    public static final class Preset {
        public final String label;
        public final String serviceUuid;
        public final String writeUuid;
        public final String notifyUuid;
        public final String template;
        public final int defaultMaxBrightness;
        public final boolean instant;

        public Preset(String label, String serviceUuid, String writeUuid, String notifyUuid,
                      String template, int defaultMaxBrightness, boolean instant) {
            this.label = label;
            this.serviceUuid = serviceUuid.length() == 0 ? "" : normalizeUuid(serviceUuid);
            this.writeUuid = writeUuid.length() == 0 ? "" : normalizeUuid(writeUuid);
            this.notifyUuid = notifyUuid.length() == 0 ? "" : normalizeUuid(notifyUuid);
            this.template = CustomRgbTemplate.parse(template).normalized();
            this.defaultMaxBrightness = defaultMaxBrightness;
            this.instant = instant;
        }
    }

    public static final List<Preset> PRESETS;
    static {
        List<Preset> list = new ArrayList<Preset>();
        list.add(new Preset("Lotus / ELK-BLEDOM (7e 07)", "fff0", "fff3", "",
                "7e 07 05 03 RR GG BB 10 ef", 20, false));
        list.add(new Preset("Symphony / LED LAMP (7e 00)", "fff0", "fff3", "",
                "7e 00 05 03 RR GG BB 00 ef", 30, false));
        list.add(new Preset("Zengge / LEDnetWF (41 01)", "ffff", "ff01", "ff02",
                "00 SQ 80 00 00 0d 0e 0b 41 01 RR GG BB 00 00 00 01 00 00 f0 CS:8", 100, true));
        list.add(new Preset("Triones / HappyLighting (FFD5)", "ffd5", "ffd9", "",
                "56 RR GG BB 00 f0 aa", 50, false));
        list.add(new Preset("MagicBlue / LEDBLE (FFE5)", "ffe5", "ffe9", "",
                "56 RR GG BB 00 f0 aa", 50, false));
        list.add(new Preset("SP110E / BanlanX (FFE0)", "ffe0", "ffe1", "",
                "RR GG BB 1e", 50, false));
        list.add(new Preset("Govee BLE (XOR 20B)",
                "00010203-0405-0607-0809-0a0b0c0d1910",
                "00010203-0405-0607-0809-0a0b0c0d2b11", "",
                "33 05 02 RR GG BB 00 00 00 00 00 00 00 00 00 00 00 00 00 XR", 50, false));
        list.add(new Preset("순수 RGB 3바이트 (RAW)", "", "", "",
                "RR GG BB", 50, false));
        PRESETS = Collections.unmodifiableList(list);
    }

    public final String id;
    public final String name;
    public final String address;
    public final String serviceUuid;
    public final String writeUuid;
    public final String notifyUuid;
    public final String template;
    public final ColorOrder colorOrder;
    public final int maxBrightness;
    public final int redGain;
    public final int greenGain;
    public final int blueGain;
    public final boolean writeWithResponse;
    public final boolean instant;
    public final boolean enabled;

    public CustomLampProfile(String id, String name, String address,
                             String serviceUuid, String writeUuid, String notifyUuid,
                             String template, ColorOrder colorOrder,
                             int maxBrightness, int redGain, int greenGain, int blueGain,
                             boolean writeWithResponse, boolean instant, boolean enabled) {
        this.id = requireNonEmpty(id, "id");
        this.name = cleanName(name);
        this.address = normalizeMac(address);
        this.serviceUuid = normalizeUuid(serviceUuid);
        this.writeUuid = normalizeUuid(writeUuid);
        this.notifyUuid = notifyUuid == null || notifyUuid.trim().length() == 0 ? "" : normalizeUuid(notifyUuid);
        this.template = CustomRgbTemplate.parse(template).normalized();
        this.colorOrder = colorOrder == null ? ColorOrder.RGB : colorOrder;
        if (maxBrightness < 1 || maxBrightness > 100) throw new IllegalArgumentException("maxBrightness must be 1..100");
        AmbientState.checkRange(redGain, 100);
        AmbientState.checkRange(greenGain, 100);
        AmbientState.checkRange(blueGain, 100);
        this.maxBrightness = maxBrightness;
        this.redGain = redGain;
        this.greenGain = greenGain;
        this.blueGain = blueGain;
        this.writeWithResponse = writeWithResponse;
        this.instant = instant;
        this.enabled = enabled;
    }

    public CustomLampProfile withEnabled(boolean nextEnabled) {
        return new CustomLampProfile(id, name, address, serviceUuid, writeUuid, notifyUuid,
                template, colorOrder, maxBrightness, redGain, greenGain, blueGain,
                writeWithResponse, instant, nextEnabled);
    }

    public CustomRgbTemplate parsedTemplate() {
        return CustomRgbTemplate.parse(template);
    }

    public SyncEngine.ColorCodec newCodec() {
        return parsedTemplate().codec(colorOrder);
    }

    /** Returns minimum ATT MTU needed for this lamp's packet, or 0 if default 23-byte MTU suffices. */
    public int requiredMtu() {
        int len = parsedTemplate().packetLength();
        return len > 20 ? len + 3 : 0;
    }

    /** Builds the per-lamp color tone using the shared gamma setting and this lamp's RGB ratios. */
    public ColorTone toColorTone(int gammaTenths) {
        int g = Math.max(10, Math.min(30, gammaTenths));
        return new ColorTone(g, redGain, greenGain, blueGain, maxBrightness);
    }

    /** Finds the best matching preset index for discovered GATT UUIDs and/or BLE device name. */
    public static int matchPresetIndex(String serviceUuid, String writeUuid, String deviceName) {
        String svc = safeNormUuid(serviceUuid);
        String wrt = safeNormUuid(writeUuid);
        String upperName = deviceName == null ? "" : deviceName.toUpperCase(Locale.US);
        if (upperName.startsWith("LEDNETWF")) return 2;
        if (upperName.contains("GOVEE")) return 6;
        if (upperName.contains("TRIONES") || upperName.contains("HAPPY")) return 3;
        if (upperName.contains("SP110") || upperName.contains("BANLAN")) return 5;
        if (svc.length() > 0 && wrt.length() > 0) {
            for (int i = 0; i < PRESETS.size(); i++) {
                Preset p = PRESETS.get(i);
                if (p.serviceUuid.equalsIgnoreCase(svc) && p.writeUuid.equalsIgnoreCase(wrt)) {
                    if (i == 0 && (upperName.contains("SYMPHONY") || upperName.contains("LED LAMP"))) return 1;
                    return i;
                }
            }
        }
        return 0;
    }

    /** Normalizes a 16-bit hex UUID (e.g. {@code fff0}) or full 128-bit UUID to lowercase canonical form. */
    public static String normalizeUuid(String raw) {
        if (raw == null) throw new IllegalArgumentException("UUID is required");
        String s = raw.trim().toLowerCase(Locale.US);
        if (s.startsWith("0x")) s = s.substring(2);
        if (s.matches("[0-9a-f]{4}")) {
            return "0000" + s + "-0000-1000-8000-00805f9b34fb";
        }
        if (s.matches("[0-9a-f]{8}")) {
            return s + "-0000-1000-8000-00805f9b34fb";
        }
        return UUID.fromString(s).toString().toLowerCase(Locale.US);
    }

    /** Short display form: 4 hex digits for Bluetooth Base UUIDs, otherwise the full UUID. */
    public static String shortUuid(String uuid) {
        if (uuid == null || uuid.length() == 0) return "-";
        String lower = uuid.toLowerCase(Locale.US);
        if (lower.startsWith("0000") && lower.endsWith("-0000-1000-8000-00805f9b34fb") && lower.length() == 36) {
            return lower.substring(4, 8).toUpperCase(Locale.US);
        }
        return lower;
    }

    public static String normalizeMac(String raw) {
        if (raw == null) throw new IllegalArgumentException("Bluetooth MAC address is required");
        String s = raw.trim().toUpperCase(Locale.US);
        if (!s.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}")) {
            throw new IllegalArgumentException("Invalid MAC address: " + raw);
        }
        return s;
    }

    private static String safeNormUuid(String raw) {
        if (raw == null || raw.trim().length() == 0) return "";
        try { return normalizeUuid(raw); }
        catch (IllegalArgumentException ignored) { return ""; }
    }

    private static String requireNonEmpty(String text, String label) {
        if (text == null || text.trim().length() == 0) throw new IllegalArgumentException(label + " is required");
        return text.trim().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String cleanName(String text) {
        if (text == null || text.trim().length() == 0) return "사제 엠비언트";
        return text.trim().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** Encodes a list of profiles into a newline/tab-delimited string for SharedPreferences storage. */
    public static String encodeList(List<CustomLampProfile> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append('\n');
            CustomLampProfile p = list.get(i);
            sb.append("v1\t")
                    .append(p.id).append('\t')
                    .append(p.name).append('\t')
                    .append(p.address).append('\t')
                    .append(p.serviceUuid).append('\t')
                    .append(p.writeUuid).append('\t')
                    .append(p.notifyUuid).append('\t')
                    .append(p.template).append('\t')
                    .append(p.colorOrder.name()).append('\t')
                    .append(p.maxBrightness).append('\t')
                    .append(p.redGain).append('\t')
                    .append(p.greenGain).append('\t')
                    .append(p.blueGain).append('\t')
                    .append(p.writeWithResponse ? "1" : "0").append('\t')
                    .append(p.instant ? "1" : "0").append('\t')
                    .append(p.enabled ? "1" : "0");
        }
        return sb.toString();
    }

    /** Decodes stored profiles; malformed lines are skipped safely. */
    public static List<CustomLampProfile> decodeList(String stored) {
        List<CustomLampProfile> out = new ArrayList<CustomLampProfile>();
        if (stored == null || stored.trim().length() == 0) return out;
        String[] lines = stored.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 16 || !"v1".equals(f[0])) continue;
            try {
                out.add(new CustomLampProfile(
                        f[1], f[2], f[3], f[4], f[5], f[6], f[7],
                        ColorOrder.parse(f[8]),
                        Integer.parseInt(f[9]),
                        Integer.parseInt(f[10]),
                        Integer.parseInt(f[11]),
                        Integer.parseInt(f[12]),
                        "1".equals(f[13]),
                        "1".equals(f[14]),
                        "1".equals(f[15])
                ));
            } catch (IllegalArgumentException ignored) {
                // Skip corrupted entries without losing valid lamps.
            }
        }
        return out;
    }
}
