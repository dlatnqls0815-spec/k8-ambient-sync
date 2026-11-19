package io.github.k8ambient.core;

/**
 * Optional "My Drive sport color" override for the aftermarket lamps.
 *
 * <p>On the K8, My Drive reports drive mode {@link DriveModeLight#SMART} (0x06) and logs item
 * feedback {@code mIndividualModeFeedback: [0] powertrain [1] suspension [2] steering}, where value
 * {@link #SPORT_VALUE} (5) means sport (firm for suspension). When enabled,
 * shows the configured sport color whenever My Drive has any sport item (feedback known) or the car
 * is in {@link DriveModeLight#SPORT} (0x03), regardless of the factory drive-mode-link option.
 */
public final class MyDriveSportLight {
    /** Feedback value meaning sport (powertrain/steering) or firm/sport (suspension) on the K8. */
    public static final int SPORT_VALUE = 5;

    private final boolean enabled;
    private final int red, green, blue;

    public MyDriveSportLight(boolean enabled, int red, int green, int blue) {
        AmbientState.checkRange(red, 255);
        AmbientState.checkRange(green, 255);
        AmbientState.checkRange(blue, 255);
        this.enabled = enabled;
        this.red = red;
        this.green = green;
        this.blue = blue;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** True when feedback has at least 3 items and any of powertrain, suspension or steering is sport (5). */
    public static boolean anySport(int[] feedback) {
        return feedback != null && feedback.length >= 3
                && (feedback[0] == SPORT_VALUE || feedback[1] == SPORT_VALUE || feedback[2] == SPORT_VALUE);
    }

    /** True when this option should override the color for the given drive mode and feedback. */
    public boolean active(int mode, int[] feedback) {
        if (!enabled) return false;
        // Sport mode is sport whatever the My Drive items say; My Drive needs a known sport item.
        return mode == DriveModeLight.SPORT || (mode == DriveModeLight.SMART && anySport(feedback));
    }

    /**
     * Returns the sport color with {@code mapped}'s brightness and power when {@link #active} holds,
     * otherwise {@code mapped} unchanged.
     */
    public AmbientState apply(AmbientState mapped, int mode, int[] feedback) {
        if (mapped == null || !active(mode, feedback)) return mapped;
        return new AmbientState(red, green, blue, mapped.brightness, mapped.on);
    }
}
