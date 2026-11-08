package io.github.k8ambient.core;

/**
 * Smooth color and brightness transitions like the factory ambient light. Not thread-safe.
 *
 * <p>While the lamp stays on, a new target starts a linear fade from the color currently shown.
 * Turning on or off, or a zero duration, jumps straight to the target (power is never faded).
 */
public final class Fader {
    private AmbientState from, to;
    private long start, duration;

    public void target(AmbientState next, long nowMs, long durationMs) {
        if (next == null) throw new IllegalArgumentException("State is required");
        if (durationMs < 0) throw new IllegalArgumentException("Duration must not be negative");
        AmbientState shown = to == null ? null : current(nowMs);
        if (shown == null || !shown.on || !next.on || durationMs == 0) {
            from = next;
        } else {
            from = shown;
        }
        to = next;
        start = nowMs;
        duration = durationMs;
    }

    /** Returns the state to show now, or null before the first target. */
    public AmbientState current(long nowMs) {
        if (to == null) return null;
        if (duration == 0 || nowMs - start >= duration) return to;
        if (nowMs <= start) return from;
        double t = (double) (nowMs - start) / duration;
        return new AmbientState(mix(from.red, to.red, t), mix(from.green, to.green, t), mix(from.blue, to.blue, t),
                mix(from.brightness, to.brightness, t), to.on);
    }

    public boolean active(long nowMs) {
        return to != null && duration > 0 && nowMs - start < duration;
    }

    private static int mix(int a, int b, double t) {
        return (int) Math.round(a + (b - a) * t);
    }
}
