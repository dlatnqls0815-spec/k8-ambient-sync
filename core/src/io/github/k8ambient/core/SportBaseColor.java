package io.github.k8ambient.core;

import java.util.Arrays;

/**
 * Keeps the user's normal factory ambient status apart from the sport red that the My Drive Auto module
 * (research/k8-ecs-damping, option "ambient sport red") puts on the factory light.
 *
 * <p>Problem it solves (user report 2026-11-20): engine goes sport, the module sets the factory color red,
 * a speed camera alert starts (its SML statuses are blink frames, so the phone does not keep them), the engine
 * goes back to eco during the alert and the module restores the original color, but that restore arrives while
 * the alert is on and is ignored. When the alert ends the phone went back to the last normal status, which was
 * the sport red, and stayed red because the car relay does not repeat an unchanged status.
 *
 * <p>Rule: every normal (non-alert) status is the base color unless the car is in a sport state and the status
 * is one of the module's sport reds. After an alert, show the last normal status while still in sport, otherwise
 * the base color. Java 7, no Android classes.
 */
public final class SportBaseColor {
    /** RGB bytes 0..5 the module sends: K8 stock red (0.4.8+) and plain red (0.4.7). */
    private static final byte[][] SPORT_REDS = {
            {0x00, (byte) 0xfa, 0x00, 0x04, 0x00, 0x51},
            {0x00, (byte) 0xff, 0x00, 0x00, 0x00, 0x00},
    };
    /** My Drive item feedback value for sport (K8: powertrain, suspension, steering). */
    static final int SPORT_FEEDBACK = 5;

    private int mode;
    private int[] feedback;
    private SmlStatus base;

    public void onDriveMode(int driveMode) { mode = driveMode; }

    public void onMyDrive(int[] itemFeedback) {
        feedback = itemFeedback == null ? null : itemFeedback.clone();
    }

    /** Stock Sport mode, or My Drive with any item reported at sport. */
    public boolean sportActive() {
        if (mode == DriveModeLight.SPORT) return true;
        if (mode != DriveModeLight.SMART || feedback == null) return false;
        for (int i = 0; i < feedback.length && i < 3; i++) if (feedback[i] == SPORT_FEEDBACK) return true;
        return false;
    }

    public static boolean isSportRed(SmlStatus status) {
        if (status == null) return false;
        byte[] rgb = Arrays.copyOf(status.rawBytes(), 6);
        for (byte[] red : SPORT_REDS) if (Arrays.equals(red, rgb)) return true;
        return false;
    }

    /** Call for every status received while no camera alert is on. */
    public void onNormalStatus(SmlStatus status) {
        if (status == null) return;
        if (sportActive() && isSportRed(status)) return;
        base = status;
    }

    public SmlStatus base() { return base; }

    /**
     * The status to show when a camera alert ends: the last normal status while still in sport, otherwise the
     * base color (falling back to the last normal status when no base is known yet).
     */
    public SmlStatus afterAlert(SmlStatus lastNormal) {
        if (sportActive() || base == null) return lastNormal;
        if (lastNormal != null && !isSportRed(lastNormal)) return lastNormal;
        return base;
    }
}
