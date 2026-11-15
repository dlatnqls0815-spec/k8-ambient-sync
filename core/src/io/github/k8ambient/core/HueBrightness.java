package io.github.k8ambient.core;

/**
 * Per-color brightness ratio so different factory colors look equally bright on the aftermarket
 * lamps (immutable). The user found warm colors too dim and saturated red, blue and purple too
 * bright: with the Lotus maximum at 20 %, warm should be about 30 % (ratio 150) and red/blue/purple
 * about 13 % (ratio 65).
 *
 * <p>Six anchors on the hue circle; colors between two anchors blend linearly. Whitish colors
 * blend toward 100 % (full hue ratio from saturation 2/3 up), so pure white is unchanged. The ratio multiplies the lamp
 * brightness that {@link ColorTone} produced and is capped at 100 %.
 */
public final class HueBrightness {
    /** Anchor hues in degrees; warm (orange to yellow, 30..60) is a plateau. */
    static final double[] ANCHOR_HUES = {0, 30, 60, 120, 185, 235, 280};
    /** Which ratio each anchor uses. */
    private static final int[] ANCHOR_RATIO = {0, 1, 1, 2, 3, 4, 5};
    public static final int RED = 0, WARM = 1, GREEN = 2, SKY = 3, BLUE = 4, PURPLE = 5;
    public static final int MIN_RATIO = 20, MAX_RATIO = 300;
    public static final HueBrightness NEUTRAL = new HueBrightness(new int[] {100, 100, 100, 100, 100, 100});
    /** The user's 2026-11-14 request: warm 30 %, red/blue/purple 13 % against the 20 % Lotus maximum. */
    public static final HueBrightness USER_DEFAULT = new HueBrightness(new int[] {65, 150, 100, 100, 65, 65});

    private final int[] ratios;

    /** Ratios in percent for {@link #RED}..{@link #PURPLE}, each {@value #MIN_RATIO}..{@value #MAX_RATIO}. */
    public HueBrightness(int[] ratios) {
        if (ratios == null || ratios.length != 6) throw new IllegalArgumentException("Six ratios are required");
        for (int ratio : ratios) {
            if (ratio < MIN_RATIO || ratio > MAX_RATIO) throw new IllegalArgumentException("Ratio out of range");
        }
        this.ratios = ratios.clone();
    }

    public int ratio(int anchor) { return ratios[anchor]; }

    /** Brightness ratio in percent for a color (0..255 channels). */
    public double ratioFor(int red, int green, int blue) {
        AmbientState.checkRange(red, 255);
        AmbientState.checkRange(green, 255);
        AmbientState.checkRange(blue, 255);
        int max = Math.max(red, Math.max(green, blue)), min = Math.min(red, Math.min(green, blue));
        if (max == 0) return 100;
        double saturation = (max - min) / (double) max;
        if (saturation == 0) return 100;
        double hue;
        double delta = max - min;
        if (max == red) hue = 60 * (((green - blue) / delta) % 6);
        else if (max == green) hue = 60 * ((blue - red) / delta + 2);
        else hue = 60 * ((red - green) / delta + 4);
        if (hue < 0) hue += 360;
        double weight = Math.min(1, saturation * 1.5);
        return weight * hueRatio(hue) + (1 - weight) * 100;
    }

    private double hueRatio(double hue) {
        int n = ANCHOR_HUES.length;
        for (int i = 0; i < n; i++) {
            double from = ANCHOR_HUES[i], to = i + 1 < n ? ANCHOR_HUES[i + 1] : ANCHOR_HUES[0] + 360;
            double h = hue < from ? hue + 360 : hue;
            if (h >= from && h <= to) {
                double t = (h - from) / (to - from);
                int a = ratios[ANCHOR_RATIO[i]], b = ratios[ANCHOR_RATIO[(i + 1) % n]];
                return a + (b - a) * t;
            }
        }
        return ratios[ANCHOR_RATIO[0]];
    }

    /**
     * Scales the toned state's brightness by the ratio of the factory color {@code source}
     * (the hue is taken before tone correction). Power and color are unchanged.
     */
    public AmbientState apply(AmbientState source, AmbientState toned) {
        if (source == null || toned == null) throw new IllegalArgumentException("States are required");
        double ratio = ratioFor(source.red, source.green, source.blue);
        int brightness = (int) Math.min(100, Math.round(toned.brightness * ratio / 100.0));
        return new AmbientState(toned.red, toned.green, toned.blue, brightness, toned.on);
    }
}
