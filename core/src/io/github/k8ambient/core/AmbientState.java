package io.github.k8ambient.core;

/** Normalized input; establishing the vehicle-specific mapping is a separate step. */
public final class AmbientState {
    public final int red, green, blue, brightness;
    public final boolean on;

    public AmbientState(int red, int green, int blue, int brightness, boolean on) {
        checkRange(red, 255);
        checkRange(green, 255);
        checkRange(blue, 255);
        checkRange(brightness, 100);
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.brightness = brightness;
        this.on = on;
    }

    static void checkRange(int value, int maximum) {
        if (value < 0 || value > maximum) throw new IllegalArgumentException("Value out of range");
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof AmbientState)) return false;
        AmbientState state = (AmbientState) other;
        return red == state.red && green == state.green && blue == state.blue
                && brightness == state.brightness && on == state.on;
    }

    @Override public int hashCode() {
        return ((((red * 31 + green) * 31 + blue) * 31 + brightness) * 31) + (on ? 1 : 0);
    }

    @Override public String toString() {
        return "AmbientState(" + red + "," + green + "," + blue + "," + brightness + "," + on + ")";
    }
}
