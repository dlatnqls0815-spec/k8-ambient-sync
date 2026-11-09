package io.github.k8ambient.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Factory "ambient light depends on drive mode" option, reproduced for the aftermarket lamps.
 *
 * <p>Evidence (2026-11-09 recordings and user observation): SML status byte 8 is 0x02 while the
 * option is on and 0x01 while off. The head unit logs the drive mode in
 * {@code SYS_DRIVE_MODE_DATA_M_RESP} byte 2 (0x02, 0x03, 0x06, each followed by 0x00 a moment
 * later). The car shows a fixed color in ECO and SPORT, and the user's last applied color in SMART;
 * the SML status keeps carrying that user color throughout, so the override must be applied here.
 * The code-to-mode assignment (0x02 ECO, 0x03 SPORT, 0x06 SMART) follows the usual HMG values and
 * the user's three modes; it should be confirmed on the car.
 */
public final class DriveModeLight {
    public static final int ECO = 0x02, SPORT = 0x03, SMART = 0x06;
    static final int LINK_ON = 0x02;
    private final Map<Integer, int[]> colors = new HashMap<Integer, int[]>();

    /** True when the factory option "ambient light depends on drive mode" is on. */
    public static boolean linked(SmlStatus status) {
        return status != null && status.driveModeRaw == LINK_ON;
    }

    public static boolean known(int mode) {
        return mode == ECO || mode == SPORT || mode == SMART;
    }

    /** Sets the color shown in a fixed-color mode (ECO or SPORT). SMART always uses the user color. */
    public void setColor(int mode, int red, int green, int blue) {
        if (mode != ECO && mode != SPORT) throw new IllegalArgumentException("Only ECO and SPORT have fixed colors");
        AmbientState.checkRange(red, 255);
        AmbientState.checkRange(green, 255);
        AmbientState.checkRange(blue, 255);
        colors.put(Integer.valueOf(mode), new int[] {red, green, blue});
    }

    /**
     * Returns the state to show: the mode color with the mapped brightness and power when the option
     * is on and the mode has a fixed color, otherwise {@code mapped} unchanged.
     */
    public AmbientState apply(AmbientState mapped, SmlStatus status, int mode) {
        if (mapped == null || !linked(status)) return mapped;
        int[] color = colors.get(Integer.valueOf(mode));
        if (color == null) return mapped;
        return new AmbientState(color[0], color[1], color[2], mapped.brightness, mapped.on);
    }
}
