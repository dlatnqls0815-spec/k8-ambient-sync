package io.github.k8ambient.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Expected ESP32 "CALC" answers computed by the Java core (the phone relay 0.4.8 pipeline), so the
 * firmware's C++ port can be compared on the real board: tools/esp32_serial_check.py sends each
 * CALC line over USB serial and compares the reply. Default settings only (the firmware's DEFAULTS).
 *
 * <p>Output lines: {@code CALC <sml> <mode> <alert>\t<expected reply>}.
 */
public final class EspGolden {
    private EspGolden() {}

    public static void main(String[] args) {
        for (String line : lines()) System.out.println(line);
    }

    static List<String> lines() {
        List<String> out = new ArrayList<String>();
        Random random = new Random(20261117L);
        // Fixed cases first: theme 0132019a019e (eco ocean), direct orange, purple, red, white, off, unknown brightness.
        String[] fixed = {"0132019a019e0b0102", "00ff00a600460b0101", "009b002100ef0b0101", "00ff000000000b0102",
                "00ff00ff00ff060101", "00ff000000000101 02".replace(" ", ""), "00ff000000000c0101", "016b015a01950b0102",
                "000000000000060101"};
        int[] modes = {0, 2, 3, 6};
        for (String sml : fixed) {
            for (int mode : modes) {
                out.add(entry(sml, mode, false));
                out.add(entry(sml, mode, true));
            }
        }
        for (int i = 0; i < 160; i++) {
            boolean theme = random.nextInt(4) == 0;
            StringBuilder sml = new StringBuilder();
            for (int c = 0; c < 3; c++) sml.append(String.format("%02x%02x", theme ? 1 : 0, random.nextInt(256)));
            sml.append(String.format("%02x", 1 + random.nextInt(11)));
            sml.append("01");
            sml.append(random.nextBoolean() ? "02" : "01");
            out.add(entry(sml.toString(), modes[random.nextInt(modes.length)], random.nextInt(8) == 0));
        }
        return out;
    }

    static String entry(String smlHex, int mode, boolean alert) {
        return "CALC " + smlHex + " " + mode + " " + (alert ? 1 : 0) + "\t" + expected(smlHex, mode, alert);
    }

    /** Same pipeline and defaults as RelayService + Prefs: mapping, drive mode, tone, per-color ratio. */
    static String expected(String smlHex, int mode, boolean alert) {
        byte[] raw = RelayProtocol.decodeLine("SML " + smlHex);
        SmlStatus status = SmlStatus.parse(raw);
        K8LotusMapping mapping = new K8LotusMapping();
        DriveModeLight drive = new DriveModeLight();
        drive.setColor(DriveModeLight.ECO, 50, 154, 158);
        drive.setColor(DriveModeLight.SPORT, 255, 0, 0);
        AmbientState mapped = mapping.map(status);
        AmbientState state = alert ? mapped : drive.apply(mapped, status, mode);
        if (state == null) return "CALC none";
        HueBrightness hue = HueBrightness.USER_DEFAULT;
        AmbientState lotus = hue.apply(state, new ColorTone(18, 100, 100, 85, 20).apply(state));
        AmbientState zengge = hue.apply(state, new ColorTone(18, 100, 100, 85, 100).apply(state));
        int[] wire = zengge.on
                ? ColorOrder.GRB.toWire(scale(zengge.red, zengge.brightness), scale(zengge.green, zengge.brightness),
                        scale(zengge.blue, zengge.brightness))
                : new int[] {0, 0, 0};
        return "CALC " + text(state) + " L " + text(lotus) + " Z " + wire[0] + "," + wire[1] + "," + wire[2];
    }

    private static int scale(int channel, int percent) {
        return (channel * percent + 50) / 100;
    }

    private static String text(AmbientState s) {
        return s.red + "," + s.green + "," + s.blue + "," + s.brightness + "," + (s.on ? 1 : 0);
    }
}
