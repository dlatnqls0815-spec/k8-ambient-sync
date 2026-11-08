package io.github.k8ambient.core;

/**
 * User tone correction applied before sending to the aftermarket lamp (immutable).
 *
 * <p>The LED strip renders mid values lighter and more pastel than the factory ambient light, so a
 * gamma above 1 deepens colors; per-channel gains trim the hue; {@code maxBrightness} maps the
 * factory 0..100 % range onto the lamp's app percentage (20 % matched the factory maximum).
 */
public final class ColorTone {
    public static final ColorTone NEUTRAL = new ColorTone(10, 100, 100, 100, 100);
    public final int gammaTenths, redGain, greenGain, blueGain, maxBrightness;

    /** gamma 1.0..3.0 as 10..30; gains 0..100 %; maxBrightness 1..100 %. */
    public ColorTone(int gammaTenths, int redGain, int greenGain, int blueGain, int maxBrightness) {
        if (gammaTenths < 10 || gammaTenths > 30) throw new IllegalArgumentException("Gamma out of range");
        AmbientState.checkRange(redGain, 100);
        AmbientState.checkRange(greenGain, 100);
        AmbientState.checkRange(blueGain, 100);
        if (maxBrightness < 1 || maxBrightness > 100) throw new IllegalArgumentException("Maximum out of range");
        this.gammaTenths = gammaTenths;
        this.redGain = redGain;
        this.greenGain = greenGain;
        this.blueGain = blueGain;
        this.maxBrightness = maxBrightness;
    }

    public AmbientState apply(AmbientState state) {
        if (state == null) throw new IllegalArgumentException("State is required");
        return new AmbientState(channel(state.red, redGain), channel(state.green, greenGain),
                channel(state.blue, blueGain), (state.brightness * maxBrightness + 50) / 100, state.on);
    }

    int channel(int value, int gain) {
        double corrected = 255.0 * Math.pow(value / 255.0, gammaTenths / 10.0) * gain / 100.0;
        return (int) Math.max(0, Math.min(255, Math.round(corrected)));
    }
}
