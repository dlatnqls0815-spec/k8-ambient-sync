package io.github.k8ambient.core;

/**
 * Physical channel order of an LED strip. The name lists which wanted channel each wire slot
 * (R, G, B as sent in the frame) must carry. Example: the user's Zengge strip lights green for a
 * frame red of 255 and red for green, so it is {@link #GRB}: slot R carries the wanted green.
 */
public enum ColorOrder {
    RGB(0, 1, 2), RBG(0, 2, 1), GRB(1, 0, 2), GBR(1, 2, 0), BRG(2, 0, 1), BGR(2, 1, 0);

    private final int r, g, b;

    ColorOrder(int r, int g, int b) {
        this.r = r;
        this.g = g;
        this.b = b;
    }

    /** Returns the frame's R, G, B bytes that make the strip show the wanted color. */
    public int[] toWire(int red, int green, int blue) {
        int[] want = {red, green, blue};
        return new int[] {want[r], want[g], want[b]};
    }

    /** Lenient lookup for stored settings; unknown text falls back to RGB. */
    public static ColorOrder parse(String text) {
        if (text != null) for (ColorOrder order : values()) if (order.name().equalsIgnoreCase(text.trim())) return order;
        return RGB;
    }
}
