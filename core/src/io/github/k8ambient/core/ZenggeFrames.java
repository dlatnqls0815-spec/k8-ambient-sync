package io.github.k8ambient.core;

/**
 * Frames for the user's Zengge/LEDnetWF Symphony controller (product 0xA3, BLE v5; service 0xFFFF,
 * write 0xFF01 without response). Matches the three color writes captured over HCI on 2026-11-09:
 * {@code 00 sq 80 00 00 0d 0e 0b | 41 01 RR GG BB 00 00 00 01 00 00 f0 chk}. The 8-byte transport
 * header and the 0x41 "settled mode 1" layout agree with lednetwf_ble (MIT, 4f9c8bb).
 *
 * <p>Only the static color frame is used: brightness is folded into the RGB values and OFF is black,
 * because no brightness or power write was captured from this device.
 */
public final class ZenggeFrames {
    private ZenggeFrames() {}

    /** Transport v0 wrapper, single segment, cmdId 0x0b (no response expected). */
    public static byte[] wrap(int sequence, byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > 254) throw new IllegalArgumentException("Bad payload");
        AmbientState.checkRange(sequence, 255);
        byte[] out = new byte[8 + payload.length];
        out[0] = 0;
        out[1] = (byte) sequence;
        out[2] = (byte) 0x80;
        out[3] = 0;
        out[4] = (byte) (payload.length >> 8);
        out[5] = (byte) payload.length;
        out[6] = (byte) (payload.length + 1);
        out[7] = 0x0b;
        System.arraycopy(payload, 0, out, 8, payload.length);
        return out;
    }

    /** Static foreground color (0x41 mode 1, black background, speed 1), checksum = byte sum. */
    public static byte[] staticColor(int sequence, int red, int green, int blue) {
        AmbientState.checkRange(red, 255);
        AmbientState.checkRange(green, 255);
        AmbientState.checkRange(blue, 255);
        byte[] p = {0x41, 1, (byte) red, (byte) green, (byte) blue, 0, 0, 0, 1, 0, 0, (byte) 0xf0, 0};
        int sum = 0;
        for (int i = 0; i < p.length - 1; i++) sum += p[i] & 0xff;
        p[p.length - 1] = (byte) sum;
        return wrap(sequence, p);
    }

    /**
     * Builds successive frames with a rolling sequence number and a channel order fix-up
     * for strips wired in a different order. Not thread-safe.
     */
    public static final class Codec implements SyncEngine.ColorCodec {
        private final ColorOrder order;
        private int sequence;

        public Codec(ColorOrder order) {
            if (order == null) throw new IllegalArgumentException("Order is required");
            this.order = order;
        }

        public byte[] color(int red, int green, int blue) {
            int[] wire = order.toWire(red, green, blue);
            sequence = (sequence + 1) & 0xff;
            return staticColor(sequence, wire[0], wire[1], wire[2]);
        }
    }
}
