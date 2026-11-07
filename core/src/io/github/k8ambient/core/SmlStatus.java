package io.github.k8ambient.core;

import java.util.Arrays;

/** Raw SML response: three big-endian 16-bit channels followed by three raw fields. */
public final class SmlStatus {
    public final int redRaw, greenRaw, blueRaw, brightnessRaw, dimRaw, driveModeRaw;
    private final byte[] bytes;

    private SmlStatus(byte[] bytes) {
        this.bytes = bytes.clone();
        redRaw = channel(this.bytes, 0);
        greenRaw = channel(this.bytes, 2);
        blueRaw = channel(this.bytes, 4);
        brightnessRaw = this.bytes[6] & 255;
        dimRaw = this.bytes[7] & 255;
        driveModeRaw = this.bytes[8] & 255;
    }

    public static SmlStatus parse(byte[] bytes) {
        if (bytes == null || bytes.length < 9) throw new IllegalArgumentException("SML response requires at least 9 bytes");
        return new SmlStatus(bytes);
    }

    private static int channel(byte[] bytes, int index) {
        return ((bytes[index] & 255) << 8) | (bytes[index + 1] & 255);
    }

    public byte[] rawBytes() { return bytes.clone(); }

    /**
     * K8 observation: level 1 is the lowest lit setting and level 11 was the maximum.
     * Linear normalization between those endpoints is provisional; this does not infer power state.
     */
    public int toK8ProvisionalBrightnessPercent() {
        if (brightnessRaw < 1 || brightnessRaw > 11)
            throw new IllegalArgumentException("Unknown K8 brightness level");
        return (brightnessRaw * 100 + 5) / 11;
    }

    /** EV6 upstream reference only; this mapping is unverified for K8. */
    public AmbientState toEv6ReferenceState() {
        if (redRaw > 255 || greenRaw > 255 || blueRaw > 255)
            throw new IllegalArgumentException("Raw RGB may be a theme; conversion is unverified");
        if (brightnessRaw < 1 || brightnessRaw > 11)
            throw new IllegalArgumentException("Unknown reference brightness");
        return new AmbientState(redRaw, greenRaw, blueRaw, (brightnessRaw - 1) * 10, brightnessRaw != 1);
    }

    @Override public boolean equals(Object other) {
        return other instanceof SmlStatus && Arrays.equals(bytes, ((SmlStatus) other).bytes);
    }

    @Override public int hashCode() { return Arrays.hashCode(bytes); }

    @Override public String toString() {
        return "SmlStatus(rgbRaw=" + redRaw + "," + greenRaw + "," + blueRaw
                + ";brightnessRaw=" + brightnessRaw + ";dimRaw=" + dimRaw
                + ";driveModeRaw=" + driveModeRaw + ";bytes=" + bytes.length + ")";
    }
}
