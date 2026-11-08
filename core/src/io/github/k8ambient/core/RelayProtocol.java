package io.github.k8ambient.core;

import java.util.Locale;
import java.util.UUID;

/**
 * Line protocol between the K8 car relay (RFCOMM server) and the phone relay (client), plus the
 * parser for the OEM SystemService log line the car relay reads. Java 7, no Android classes.
 *
 * <p>Car to phone, one ASCII line each: {@code K8AMB 1} on connect, {@code SML <hex>} for a raw
 * SML status (at least 9 bytes), {@code PING} as keepalive. The phone never sends commands.
 */
public final class RelayProtocol {
    /** Fixed service UUID for the SDP record (random, project-specific). */
    public static final UUID SERVICE_UUID = UUID.fromString("6b38a0d2-4f7e-4c1b-9d5a-2e8f71c3b904");
    public static final String HELLO = "K8AMB 1";
    public static final String PING = "PING";
    static final int MAX_STATUS_BYTES = 32;
    private static final String LOG_MARKER = "SYS_SML_STATUS_M_RESP:";

    private RelayProtocol() {}

    public static String encodeStatus(byte[] status) {
        if (status == null || status.length < 9 || status.length > MAX_STATUS_BYTES)
            throw new IllegalArgumentException("SML status must be 9..32 bytes");
        StringBuilder line = new StringBuilder("SML ");
        for (byte b : status) line.append(String.format(Locale.US, "%02x", b & 255));
        return line.toString();
    }

    /** Returns the status bytes of an {@code SML} line, or null for any other valid line. */
    public static byte[] decodeLine(String line) {
        if (line == null) throw new IllegalArgumentException("Missing line");
        String text = line.trim();
        if (HELLO.equals(text) || PING.equals(text)) return null;
        if (!text.startsWith("SML ")) throw new IllegalArgumentException("Unknown relay line");
        String hex = text.substring(4);
        if (hex.length() % 2 != 0 || hex.length() < 18 || hex.length() > MAX_STATUS_BYTES * 2)
            throw new IllegalArgumentException("Bad SML length");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) ((digit(hex.charAt(2 * i)) << 4) | digit(hex.charAt(2 * i + 1)));
        return out;
    }

    /**
     * Extracts the response bytes from a SystemService log line such as
     * {@code D/SystemService(  688): SYS_SML_STATUS_M_RESP: 0x01 0x6b ... 0x01}. Returns null for
     * unrelated lines; both the SystemService and MicomEventHandler variants are accepted.
     */
    public static byte[] parseLogLine(String line) {
        if (line == null) return null;
        int at = line.indexOf(LOG_MARKER);
        if (at < 0) return null;
        String[] parts = line.substring(at + LOG_MARKER.length()).trim().split("\\s+");
        if (parts.length < 9 || parts.length > MAX_STATUS_BYTES) return null;
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.length() != 4 || !part.startsWith("0x")) return null;
            try {
                out[i] = (byte) ((digit(part.charAt(2)) << 4) | digit(part.charAt(3)));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return out;
    }

    private static int digit(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        throw new IllegalArgumentException("Bad hex digit");
    }
}
