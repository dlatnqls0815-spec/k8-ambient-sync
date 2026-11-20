package io.github.k8ambient.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * User-defined BLE RGB color packet template (pure Java 7, no Android dependencies).
 *
 * <p>Tokens are separated by spaces, commas, or semicolons:
 * <ul>
 *   <li>2-digit hex literal (e.g. {@code 7e}, {@code 07}, {@code ef})</li>
 *   <li>{@code RR}, {@code GG}, {@code BB} — channel bytes after {@link ColorOrder} mapping</li>
 *   <li>{@code SQ} — rolling 8-bit sequence counter (1..255 wrapping)</li>
 *   <li>{@code CS} or {@code CS:n} — low 8 bits of sum of preceding bytes from index {@code n} (default 0)</li>
 *   <li>{@code XR} or {@code XR:n} — XOR of preceding bytes from index {@code n} (default 0)</li>
 * </ul>
 */
public final class CustomRgbTemplate {
    private static final int MAX_BYTES = 64;

    private static final int KIND_LITERAL = 0;
    private static final int KIND_RED = 1;
    private static final int KIND_GREEN = 2;
    private static final int KIND_BLUE = 3;
    private static final int KIND_SEQ = 4;
    private static final int KIND_SUM = 5;
    private static final int KIND_XOR = 6;

    private final int[] kinds;
    private final int[] args;

    private CustomRgbTemplate(int[] kinds, int[] args) {
        this.kinds = kinds;
        this.args = args;
    }

    public static CustomRgbTemplate parse(String spec) {
        if (spec == null) throw new IllegalArgumentException("Template is required");
        String trimmed = spec.trim();
        if (trimmed.length() == 0) throw new IllegalArgumentException("Template is empty");
        String[] tokens = trimmed.split("[\\s,;]+");
        if (tokens.length < 3 || tokens.length > MAX_BYTES) {
            throw new IllegalArgumentException("Packet length must be 3.." + MAX_BYTES + " bytes");
        }
        int[] kinds = new int[tokens.length];
        int[] args = new int[tokens.length];
        boolean hasR = false, hasG = false, hasB = false;
        for (int i = 0; i < tokens.length; i++) {
            String tok = tokens[i].trim().toUpperCase(Locale.US);
            if ("RR".equals(tok) || "R".equals(tok)) {
                kinds[i] = KIND_RED;
                hasR = true;
            } else if ("GG".equals(tok) || "G".equals(tok)) {
                kinds[i] = KIND_GREEN;
                hasG = true;
            } else if ("BB".equals(tok) || "B".equals(tok)) {
                kinds[i] = KIND_BLUE;
                hasB = true;
            } else if ("SQ".equals(tok) || "SEQ".equals(tok)) {
                kinds[i] = KIND_SEQ;
            } else if ("CS".equals(tok) || "SUM".equals(tok) || tok.startsWith("CS:") || tok.startsWith("SUM:")) {
                kinds[i] = KIND_SUM;
                args[i] = parseStartIndex(tok, i);
            } else if ("XR".equals(tok) || "XOR".equals(tok) || tok.startsWith("XR:") || tok.startsWith("XOR:")) {
                kinds[i] = KIND_XOR;
                args[i] = parseStartIndex(tok, i);
            } else {
                String hex = tok.startsWith("0X") ? tok.substring(2) : tok;
                if (hex.length() != 2) {
                    throw new IllegalArgumentException("Bad token at " + i + ": " + tokens[i]);
                }
                int value = parseHexByte(hex);
                kinds[i] = KIND_LITERAL;
                args[i] = value;
            }
        }
        if (!hasR || !hasG || !hasB) {
            throw new IllegalArgumentException("Template must include RR, GG, and BB");
        }
        return new CustomRgbTemplate(kinds, args);
    }

    private static int parseStartIndex(String tok, int position) {
        int colon = tok.indexOf(':');
        int start = 0;
        if (colon >= 0) {
            String num = tok.substring(colon + 1);
            if (num.length() == 0) throw new IllegalArgumentException("Missing checksum start index");
            try {
                start = Integer.parseInt(num);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Bad checksum start index: " + tok);
            }
        }
        if (start < 0 || start >= position) {
            throw new IllegalArgumentException("Checksum start out of range at " + position + ": " + tok);
        }
        return start;
    }

    private static int parseHexByte(String hex) {
        int hi = Character.digit(hex.charAt(0), 16);
        int lo = Character.digit(hex.charAt(1), 16);
        if (hi < 0 || lo < 0) throw new IllegalArgumentException("Bad hex byte: " + hex);
        return (hi << 4) | lo;
    }

    public int packetLength() {
        return kinds.length;
    }

    /** Formats one packet with the given wire RGB bytes and sequence counter (0..255). */
    public byte[] format(int red, int green, int blue, int sequence) {
        AmbientState.checkRange(red, 255);
        AmbientState.checkRange(green, 255);
        AmbientState.checkRange(blue, 255);
        AmbientState.checkRange(sequence, 255);
        byte[] out = new byte[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            switch (kinds[i]) {
                case KIND_LITERAL:
                    out[i] = (byte) args[i];
                    break;
                case KIND_RED:
                    out[i] = (byte) red;
                    break;
                case KIND_GREEN:
                    out[i] = (byte) green;
                    break;
                case KIND_BLUE:
                    out[i] = (byte) blue;
                    break;
                case KIND_SEQ:
                    out[i] = (byte) sequence;
                    break;
                case KIND_SUM: {
                    int sum = 0;
                    for (int j = args[i]; j < i; j++) sum += out[j] & 0xff;
                    out[i] = (byte) (sum & 0xff);
                    break;
                }
                case KIND_XOR: {
                    int xor = 0;
                    for (int j = args[i]; j < i; j++) xor ^= out[j] & 0xff;
                    out[i] = (byte) (xor & 0xff);
                    break;
                }
                default:
                    throw new IllegalStateException("Unexpected token kind");
            }
        }
        return out;
    }

    /** Canonical space-separated string representation. */
    public String normalized() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kinds.length; i++) {
            if (i > 0) sb.append(' ');
            switch (kinds[i]) {
                case KIND_LITERAL:
                    sb.append(String.format(Locale.US, "%02x", args[i] & 0xff));
                    break;
                case KIND_RED:
                    sb.append("RR");
                    break;
                case KIND_GREEN:
                    sb.append("GG");
                    break;
                case KIND_BLUE:
                    sb.append("BB");
                    break;
                case KIND_SEQ:
                    sb.append("SQ");
                    break;
                case KIND_SUM:
                    sb.append(args[i] == 0 ? "CS" : "CS:" + args[i]);
                    break;
                case KIND_XOR:
                    sb.append(args[i] == 0 ? "XR" : "XR:" + args[i]);
                    break;
                default:
                    throw new IllegalStateException("Unexpected token kind");
            }
        }
        return sb.toString();
    }

    public SyncEngine.ColorCodec codec(ColorOrder order) {
        return new Codec(this, order);
    }

    /** Stateful codec applying channel order and a rolling 8-bit sequence number. */
    public static final class Codec implements SyncEngine.ColorCodec {
        private final CustomRgbTemplate template;
        private final ColorOrder order;
        private int sequence;

        public Codec(CustomRgbTemplate template, ColorOrder order) {
            if (template == null) throw new IllegalArgumentException("Template is required");
            if (order == null) throw new IllegalArgumentException("Order is required");
            this.template = template;
            this.order = order;
        }

        public byte[] color(int red, int green, int blue) {
            int[] wire = order.toWire(red, green, blue);
            sequence = (sequence + 1) & 0xff;
            return template.format(wire[0], wire[1], wire[2], sequence);
        }
    }

    /** Parses a hex packet string (spaced, colon-separated, or contiguous even-length hex). */
    public static byte[] parseHexPacket(String hex) {
        if (hex == null) throw new IllegalArgumentException("Hex packet is required");
        String cleaned = hex.trim().replaceAll("(?i)0x", "").replaceAll("[\\s,:;-]+", "");
        if (cleaned.length() < 6 || (cleaned.length() & 1) != 0) {
            throw new IllegalArgumentException("Hex packet must have at least 3 bytes (even hex length)");
        }
        int len = cleaned.length() / 2;
        if (len > MAX_BYTES) throw new IllegalArgumentException("Hex packet too long");
        byte[] bytes = new byte[len];
        for (int i = 0; i < len; i++) {
            bytes[i] = (byte) parseHexByte(cleaned.substring(i * 2, i * 2 + 2));
        }
        return bytes;
    }

    /**
     * Infers an RGB template from a single captured packet with three distinct R, G, B values
     * (e.g. R=18, G=52, B=86 -> 0x12, 0x34, 0x56).
     */
    public static CustomRgbTemplate inferFromSample(String packetHex, int red, int green, int blue) {
        AmbientState.checkRange(red, 255);
        AmbientState.checkRange(green, 255);
        AmbientState.checkRange(blue, 255);
        if (red == green || red == blue || green == blue) {
            throw new IllegalArgumentException("R, G, B must be three distinct values for 1-sample inference");
        }
        byte[] bytes = parseHexPacket(packetHex);
        int rIdx = uniqueIndexOf(bytes, red);
        int gIdx = uniqueIndexOf(bytes, green);
        int bIdx = uniqueIndexOf(bytes, blue);
        if (rIdx < 0 || gIdx < 0 || bIdx < 0) {
            // Fallback: if a channel value collides with a header/footer byte, allow a unique contiguous R,G,B triplet.
            int triplet = uniqueContiguousRgb(bytes, red, green, blue);
            if (triplet >= 0) {
                rIdx = triplet;
                gIdx = triplet + 1;
                bIdx = triplet + 2;
            } else {
                throw new IllegalArgumentException("Could not uniquely locate R, G, B bytes in sample packet");
            }
        }
        int[] kinds = new int[bytes.length];
        int[] args = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            if (i == rIdx) kinds[i] = KIND_RED;
            else if (i == gIdx) kinds[i] = KIND_GREEN;
            else if (i == bIdx) kinds[i] = KIND_BLUE;
            else {
                kinds[i] = KIND_LITERAL;
                args[i] = bytes[i] & 0xff;
            }
        }
        return new CustomRgbTemplate(kinds, args);
    }

    /**
     * Infers an RGB template (plus optional SQ / CS / XR checksum) from two or more captured
     * packets and their corresponding RGB values.
     */
    public static CustomRgbTemplate inferFromSamples(String[] hexPackets, int[][] rgbValues) {
        if (hexPackets == null || rgbValues == null || hexPackets.length != rgbValues.length || hexPackets.length < 2) {
            throw new IllegalArgumentException("At least two samples are required");
        }
        int count = hexPackets.length;
        byte[][] packets = new byte[count][];
        int len = -1;
        for (int s = 0; s < count; s++) {
            if (rgbValues[s] == null || rgbValues[s].length != 3) {
                throw new IllegalArgumentException("Each RGB entry must have 3 values");
            }
            AmbientState.checkRange(rgbValues[s][0], 255);
            AmbientState.checkRange(rgbValues[s][1], 255);
            AmbientState.checkRange(rgbValues[s][2], 255);
            packets[s] = parseHexPacket(hexPackets[s]);
            if (len < 0) len = packets[s].length;
            else if (packets[s].length != len) {
                throw new IllegalArgumentException("All sample packets must have the same length");
            }
        }

        List<Integer> rCandidates = new ArrayList<Integer>();
        List<Integer> gCandidates = new ArrayList<Integer>();
        List<Integer> bCandidates = new ArrayList<Integer>();
        boolean rVaries = varies(rgbValues, 0);
        boolean gVaries = varies(rgbValues, 1);
        boolean bVaries = varies(rgbValues, 2);
        if (!rVaries || !gVaries || !bVaries) {
            throw new IllegalArgumentException("Each of R, G, B must vary across the samples");
        }

        for (int i = 0; i < len; i++) {
            if (matchesChannel(packets, rgbValues, i, 0)) rCandidates.add(Integer.valueOf(i));
            if (matchesChannel(packets, rgbValues, i, 1)) gCandidates.add(Integer.valueOf(i));
            if (matchesChannel(packets, rgbValues, i, 2)) bCandidates.add(Integer.valueOf(i));
        }
        if (rCandidates.size() != 1 || gCandidates.size() != 1 || bCandidates.size() != 1) {
            throw new IllegalArgumentException("Could not uniquely identify R, G, B positions from samples");
        }
        int rIdx = rCandidates.get(0).intValue();
        int gIdx = gCandidates.get(0).intValue();
        int bIdx = bCandidates.get(0).intValue();
        if (rIdx == gIdx || rIdx == bIdx || gIdx == bIdx) {
            throw new IllegalArgumentException("Overlapping R, G, B positions; provide more distinct samples");
        }

        int[] kinds = new int[len];
        int[] args = new int[len];
        for (int i = 0; i < len; i++) {
            if (i == rIdx) {
                kinds[i] = KIND_RED;
            } else if (i == gIdx) {
                kinds[i] = KIND_GREEN;
            } else if (i == bIdx) {
                kinds[i] = KIND_BLUE;
            } else if (allEqualAt(packets, i)) {
                kinds[i] = KIND_LITERAL;
                args[i] = packets[0][i] & 0xff;
            } else if (isIncrementingSeq(packets, i)) {
                kinds[i] = KIND_SEQ;
            } else {
                int sumStart = matchChecksumStart(packets, i, true);
                if (sumStart >= 0) {
                    kinds[i] = KIND_SUM;
                    args[i] = sumStart;
                } else {
                    int xorStart = matchChecksumStart(packets, i, false);
                    if (xorStart >= 0) {
                        kinds[i] = KIND_XOR;
                        args[i] = xorStart;
                    } else {
                        throw new IllegalArgumentException("Unexplained varying byte at index " + i);
                    }
                }
            }
        }
        return new CustomRgbTemplate(kinds, args);
    }

    private static int uniqueIndexOf(byte[] bytes, int target) {
        int found = -1;
        for (int i = 0; i < bytes.length; i++) {
            if ((bytes[i] & 0xff) == target) {
                if (found >= 0) return -1;
                found = i;
            }
        }
        return found;
    }

    private static int uniqueContiguousRgb(byte[] bytes, int red, int green, int blue) {
        int found = -1;
        for (int i = 0; i <= bytes.length - 3; i++) {
            if ((bytes[i] & 0xff) == red && (bytes[i + 1] & 0xff) == green && (bytes[i + 2] & 0xff) == blue) {
                if (found >= 0) return -1;
                found = i;
            }
        }
        return found;
    }

    private static boolean varies(int[][] rgbValues, int channel) {
        int first = rgbValues[0][channel];
        for (int s = 1; s < rgbValues.length; s++) {
            if (rgbValues[s][channel] != first) return true;
        }
        return false;
    }

    private static boolean matchesChannel(byte[][] packets, int[][] rgbValues, int byteIndex, int channel) {
        for (int s = 0; s < packets.length; s++) {
            if ((packets[s][byteIndex] & 0xff) != rgbValues[s][channel]) return false;
        }
        return true;
    }

    private static boolean allEqualAt(byte[][] packets, int byteIndex) {
        int first = packets[0][byteIndex] & 0xff;
        for (int s = 1; s < packets.length; s++) {
            if ((packets[s][byteIndex] & 0xff) != first) return false;
        }
        return true;
    }

    private static boolean isIncrementingSeq(byte[][] packets, int byteIndex) {
        for (int s = 1; s < packets.length; s++) {
            int prev = packets[s - 1][byteIndex] & 0xff;
            int curr = packets[s][byteIndex] & 0xff;
            if (curr != ((prev + 1) & 0xff)) return false;
        }
        return true;
    }

    private static int matchChecksumStart(byte[][] packets, int byteIndex, boolean isSum) {
        if (byteIndex <= 0) return -1;
        // Prefer start=0, then start=8 (Zengge payload header), then any valid start.
        int[] preferred = {0, 8};
        for (int k = 0; k < preferred.length; k++) {
            int start = preferred[k];
            if (start < byteIndex && allMatchChecksum(packets, byteIndex, start, isSum)) return start;
        }
        for (int start = 1; start < byteIndex; start++) {
            if (start == 8) continue;
            if (allMatchChecksum(packets, byteIndex, start, isSum)) return start;
        }
        return -1;
    }

    private static boolean allMatchChecksum(byte[][] packets, int byteIndex, int start, boolean isSum) {
        for (int s = 0; s < packets.length; s++) {
            int acc = 0;
            for (int j = start; j < byteIndex; j++) {
                int v = packets[s][j] & 0xff;
                acc = isSum ? ((acc + v) & 0xff) : (acc ^ v);
            }
            if ((packets[s][byteIndex] & 0xff) != acc) return false;
        }
        return true;
    }

    @Override public String toString() {
        return normalized();
    }
}
