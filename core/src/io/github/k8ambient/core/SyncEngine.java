package io.github.k8ambient.core;

import java.io.IOException;

/**
 * Call from one serialized event loop; transport writes complete synchronously.
 * The 50ms interval is a mock assumption, not measured controller timing.
 * Contains no Bluetooth or vehicle access.
 */
public final class SyncEngine {
    public interface Transport {
        boolean connected();
        void connect() throws IOException;
        void write(byte[] packet) throws IOException;
        void disconnect() throws IOException;
    }

    /** Lamps with one color frame and no separate brightness/power frame (e.g. Zengge). */
    public interface ColorCodec {
        byte[] color(int red, int green, int blue);
    }

    private final Transport transport;
    private final int maximumBrightness;
    private final boolean observedLotus, lotusPower;
    private final ColorCodec codec;
    private AmbientState desired;
    private boolean enabled, hadConnection, lastDataWasColor;
    private Boolean sentOn;
    private int sentRed = -1, sentGreen = -1, sentBlue = -1, sentBrightness = -1;
    private long lastTick = -1, lastAttempt = -1, retryAt, retryDelay = 100;
    private long successfulWrites, connectionAttempts, failures;

    public SyncEngine(Transport transport) {
        this(transport, 20);
    }

    /** The maximum is an app percentage; the default 20 is an unverified user calibration. */
    public SyncEngine(Transport transport, int maximumBrightness) {
        this(transport, maximumBrightness, false, false);
    }

    /** Offline replay of the captured LotusLamp X color and brightness formats. OFF input stops output. */
    public static SyncEngine forObservedLotus(Transport transport) {
        return new SyncEngine(transport, 20, true, false);
    }

    /**
     * Captured color/brightness plus the power frames read from the app bytecode.
     * Power is static-analysis evidence only; keep this mock-only until an HCI capture matches it.
     */
    public static SyncEngine forLotusWithStaticPower(Transport transport) {
        return new SyncEngine(transport, 20, true, true);
    }

    /** As {@link #forObservedLotus}, with the input already scaled (e.g. by {@link ColorTone}); 100 = pass-through. */
    public static SyncEngine forObservedLotus(Transport transport, int maximumBrightness) {
        return new SyncEngine(transport, maximumBrightness, true, false);
    }

    /** As {@link #forLotusWithStaticPower}, with a chosen maximum; 100 = pass-through. */
    public static SyncEngine forLotusWithStaticPower(Transport transport, int maximumBrightness) {
        return new SyncEngine(transport, maximumBrightness, true, true);
    }

    /**
     * One color frame per change: brightness scales the RGB values and OFF is sent as black.
     * The input should already be toned; 100 = pass-through.
     */
    public static SyncEngine forScaledColor(Transport transport, ColorCodec codec, int maximumBrightness) {
        if (codec == null) throw new IllegalArgumentException("Codec is required");
        return new SyncEngine(transport, maximumBrightness, false, false, codec);
    }

    private SyncEngine(Transport transport, int maximumBrightness, boolean observedLotus, boolean lotusPower) {
        this(transport, maximumBrightness, observedLotus, lotusPower, null);
    }

    private SyncEngine(Transport transport, int maximumBrightness, boolean observedLotus, boolean lotusPower,
                       ColorCodec codec) {
        if (transport == null) throw new IllegalArgumentException("Transport is required");
        this.codec = codec;
        AmbientState.checkRange(maximumBrightness, 100);
        this.transport = transport;
        this.maximumBrightness = maximumBrightness;
        this.observedLotus = observedLotus;
        this.lotusPower = lotusPower;
    }

    public void onState(AmbientState state) {
        if (state == null) throw new IllegalArgumentException("State is required");
        desired = state;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            forgetSent();
            hadConnection = false;
            retryAt = 0;
            retryDelay = 100;
            if (transport.connected()) {
                try { transport.disconnect(); }
                catch (IOException ignored) { failures++; }
            }
        }
    }

    /** Writes accepted by the transport, not acknowledgments from a physical LED. */
    public long getSuccessfulWrites() { return successfulWrites; }
    public long getConnectionAttempts() { return connectionAttempts; }
    public long getFailures() { return failures; }

    public void tick(long monotonicMillis) {
        if (monotonicMillis < 0 || monotonicMillis < lastTick)
            throw new IllegalArgumentException("Clock must be monotonic");
        lastTick = monotonicMillis;
        if (!enabled || desired == null || monotonicMillis < retryAt) return;
        if (observedLotus && !lotusPower && !desired.on) {
            forgetSent();
            return;
        }

        if (!transport.connected()) {
            hadConnection = false;
            forgetSent();
            connectionAttempts++;
            try {
                transport.connect();
                if (!transport.connected()) throw new IOException();
            } catch (IOException ignored) {
                failed(monotonicMillis);
                return;
            }
        }
        if (!hadConnection) {
            forgetSent();
            hadConnection = true;
        }
        if (lastAttempt >= 0 && monotonicMillis - lastAttempt < 50) return;

        AmbientState target = desired;
        // The input must already be normalized; this is not a K8 raw-value conversion.
        int targetBrightness = (target.brightness * maximumBrightness + 50) / 100;
        if (codec != null) {
            int red = target.on ? scale(target.red, targetBrightness) : 0;
            int green = target.on ? scale(target.green, targetBrightness) : 0;
            int blue = target.on ? scale(target.blue, targetBrightness) : 0;
            if (red == sentRed && green == sentGreen && blue == sentBlue) return;
            lastAttempt = monotonicMillis;
            try {
                transport.write(codec.color(red, green, blue));
                successfulWrites++;
                retryAt = 0;
                retryDelay = 100;
                sentRed = red;
                sentGreen = green;
                sentBlue = blue;
            } catch (IOException ignored) {
                failed(monotonicMillis);
            }
            return;
        }
        boolean colorPending = sentRed != target.red || sentGreen != target.green || sentBlue != target.blue;
        byte[] packet;
        int field;
        if ((!observedLotus || lotusPower) && (sentOn == null || sentOn.booleanValue() != target.on)) {
            packet = observedLotus ? LotusE1Frames.power(target.on) : WporterE1Candidate.power(target.on);
            field = 0;
        } else if (!target.on) {
            return;
        } else if (sentBrightness != targetBrightness && (lastDataWasColor || !colorPending)) {
            // Alternate pending data fields so continuous RGB updates cannot starve brightness.
            packet = observedLotus
                    ? LotusE1Frames.brightness(targetBrightness)
                    : WporterE1Candidate.brightness(targetBrightness);
            field = 2;
        } else if (colorPending) {
            packet = observedLotus
                    ? LotusE1Frames.color(target.red, target.green, target.blue)
                    : WporterE1Candidate.color(target.red, target.green, target.blue);
            field = 1;
        } else {
            return;
        }

        lastAttempt = monotonicMillis;
        try {
            transport.write(packet);
            successfulWrites++;
            retryAt = 0;
            retryDelay = 100;
            if (field == 0) {
                sentOn = Boolean.valueOf(target.on);
                if (!target.on) {
                    sentRed = sentGreen = sentBlue = sentBrightness = -1;
                    lastDataWasColor = false;
                }
            } else if (field == 1) {
                lastDataWasColor = true;
                sentRed = target.red;
                sentGreen = target.green;
                sentBlue = target.blue;
            } else {
                lastDataWasColor = false;
                sentBrightness = targetBrightness;
            }
        } catch (IOException ignored) {
            failed(monotonicMillis);
        }
    }

    private static int scale(int channel, int percent) {
        return (channel * percent + 50) / 100;
    }

    private void forgetSent() {
        sentOn = null;
        lastDataWasColor = false;
        sentRed = sentGreen = sentBlue = sentBrightness = -1;
    }

    private void failed(long now) {
        failures++;
        hadConnection = false;
        forgetSent();
        retryAt = now > Long.MAX_VALUE - retryDelay ? Long.MAX_VALUE : now + retryDelay;
        retryDelay = Math.min(5000, retryDelay * 2);
        try { transport.disconnect(); }
        catch (IOException ignored) { /* The original failure already records this recovery attempt. */ }
    }
}
