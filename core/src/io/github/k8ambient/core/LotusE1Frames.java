package io.github.k8ambient.core;

/**
 * Plain E1 frames for the user's LotusLamp X controller branch. Offline/mock use only.
 *
 * <p>Color and brightness match frames captured over HCI. Power is read from the LotusLamp X
 * 5.21.03 bytecode ({@code BlePackagingDevice.rgbOpen}, {@code E1Achieve.achieve}) with the
 * appType/appVersion bytes observed in every captured brightness frame; it has not been
 * compared against a captured power write yet. See docs/research/PC_ANALYSIS_2026-11-07.md.
 */
public final class LotusE1Frames {
    /** Lamp type, appType and appVersion bytes seen in all 120 captured brightness frames. */
    static final byte LAMP_TYPE = 1, APP_TYPE = 2, APP_VERSION = 1;

    private LotusE1Frames() {}

    public static byte[] color(int red, int green, int blue) {
        AmbientState.checkRange(red, 255);
        AmbientState.checkRange(green, 255);
        AmbientState.checkRange(blue, 255);
        return new byte[] {0x7e, 7, 5, 3, (byte) red, (byte) green, (byte) blue, 0x10, (byte) 0xef};
    }

    /** App percentage. Captured values were 5-23; other values are format-consistent but unobserved. */
    public static byte[] brightness(int percent) {
        AmbientState.checkRange(percent, 100);
        return new byte[] {0x7e, 4, 1, (byte) percent, LAMP_TYPE, -1, APP_TYPE, APP_VERSION, (byte) 0xef};
    }

    /** Static-analysis frame; not yet matched to a captured power write. */
    public static byte[] power(boolean on) {
        return new byte[] {0x7e, 7, 4, (byte) (on ? 0xff : 0), 1, (byte) (on ? 1 : 0),
                APP_TYPE, APP_VERSION, (byte) 0xef};
    }
}
