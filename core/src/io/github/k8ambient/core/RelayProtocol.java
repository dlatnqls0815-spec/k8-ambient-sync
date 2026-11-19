package io.github.k8ambient.core;

import java.util.Locale;
import java.util.UUID;

/**
 * Line protocol between the K8 car relay (RFCOMM server) and the phone relay (client), plus the
 * parser for the OEM SystemService log line the car relay reads. Java 7, no Android classes.
 *
 * <p>Car to phone, one ASCII line each: {@code K8AMB 1} on connect, {@code SML <hex>} for a raw
 * SML status (at least 9 bytes), {@code ALERT 1}/{@code ALERT 0} when the navigation's speed camera
 * alert starts/ends (the factory light then blinks red; the phone should switch without fading),
 * {@code MODE <hex>} when the drive mode changes (e.g. {@code MODE 03}),
 * {@code MYDRIVE <a> <b> <c>} (decimal powertrain, suspension, steering feedback from My Drive),
 * {@code PING} as keepalive. The phone never sends commands. Older phones ignore ALERT, MODE and
 * MYDRIVE lines.
 */
public final class RelayProtocol {
    /** Fixed service UUID for the SDP record (random, project-specific). */
    public static final UUID SERVICE_UUID = UUID.fromString("6b38a0d2-4f7e-4c1b-9d5a-2e8f71c3b904");
    public static final String HELLO = "K8AMB 1";
    public static final String PING = "PING";
    static final int MAX_STATUS_BYTES = 32;
    public static final String ALERT_ON = "ALERT 1", ALERT_OFF = "ALERT 0";
    private static final String LOG_MARKER = "SYS_SML_STATUS_M_RESP:";
    public static final String MODE_PREFIX = "MODE ";
    public static final String MYDRIVE_PREFIX = "MYDRIVE ";
    private static final String DRIVE_MODE_MARKER = "SYS_DRIVE_MODE_DATA_M_RESP:";
    private static final String MYDRIVE_MARKER = "mIndividualModeFeedback:";
    private static final String ALERT_ACTION = "NaviCameraAlertState", ALERT_FIELD = "mTempCameraAlertState :";

    private RelayProtocol() {}

    public static String encodeStatus(byte[] status) {
        if (status == null || status.length < 9 || status.length > MAX_STATUS_BYTES)
            throw new IllegalArgumentException("SML status must be 9..32 bytes");
        StringBuilder line = new StringBuilder("SML ");
        for (byte b : status) line.append(String.format(Locale.US, "%02x", b & 255));
        return line.toString();
    }

    private static final String CONFIG_COMMAND = "[SystemService.setSMLConfiguration] mode:";

    /**
     * Predicts configuration bytes from the head unit's command log line, which appears about
     * 0.3 s before the matching {@code SYS_SML_STATUS_M_RESP} (captured 2026-11-09):
     * <pre>
     * [SystemService.setSMLConfiguration] mode: 5, data: 9, called by 2060
     * </pre>
     * RGB changes require a vehicle status response. Configuration mode 5 sets byte 6 (brightness raw), mode 8 byte 7
     * (dimmed while driving) and mode 9 byte 8 (drive mode link), as seen in the captured pairs.
     * Returns a new array, or null when the line is not such a command, the mode is another one, a
     * value is out of range, or there is no current status to change. The real response still
     * follows and corrects any wrong prediction.
     */
    public static byte[] predictStatus(byte[] current, String line) {
        if (current == null || current.length < 9 || line == null) return null;
        int at = line.indexOf(CONFIG_COMMAND);
        if (at < 0) return null;
        String[] parts = line.substring(at + CONFIG_COMMAND.length()).split(",");
        if (parts.length < 2 || !parts[1].trim().startsWith("data:")) return null;
        int mode, data;
        try {
            mode = Integer.parseInt(parts[0].trim());
            data = Integer.parseInt(parts[1].trim().substring(5).trim());
        } catch (NumberFormatException bad) {
            return null;
        }
        if (data < 0 || data > 255) return null;
        int index = mode == 5 ? 6 : mode == 8 ? 7 : mode == 9 ? 8 : -1;
        if (index < 0) return null;
        byte[] out = current.clone();
        out[index] = (byte) data;
        return out;
    }

    /** Returns the status bytes of an {@code SML} line, or null for any other valid line. */
    public static byte[] decodeLine(String line) {
        if (line == null) throw new IllegalArgumentException("Missing line");
        String text = line.trim();
        if (HELLO.equals(text) || PING.equals(text) || ALERT_ON.equals(text) || ALERT_OFF.equals(text)
                || decodeDriveMode(text) != null || decodeMyDrive(text) != null) return null;
        if (!text.startsWith("SML ")) throw new IllegalArgumentException("Unknown relay line");
        String hex = text.substring(4);
        if (hex.length() % 2 != 0 || hex.length() < 18 || hex.length() > MAX_STATUS_BYTES * 2)
            throw new IllegalArgumentException("Bad SML length");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) ((digit(hex.charAt(2 * i)) << 4) | digit(hex.charAt(2 * i + 1)));
        return out;
    }

    /**
     * 0x00 follows every change and 0x0f is what the car reports at ignition (captured 2026-11-10,
     * 11 s after boot, while the car itself was back in ECO): neither names a drive mode.
     */
    private static Integer realMode(int mode) {
        return mode == 0 || mode == 0x0f ? null : Integer.valueOf(mode);
    }

    private static final String IGNITION_MARKER = "SYS_IGN_STATUS_M:";

    /**
     * Ignition from {@code D/SystemService( 691): SYS_IGN_STATUS_M: 0} (off) / {@code 1} (on).
     * The head unit can stay on through a short ignition cycle (captured 2026-11-10: 18:10:34 off,
     * 18:10:38 on), and the car returns to ECO at every ignition without logging a mode line.
     */
    public static Boolean parseIgnitionLogLine(String line) {
        if (line == null) return null;
        int at = line.indexOf(IGNITION_MARKER);
        if (at < 0) return null;
        String value = line.substring(at + IGNITION_MARKER.length()).trim();
        if ("1".equals(value)) return Boolean.TRUE;
        if ("0".equals(value)) return Boolean.FALSE;
        return null;
    }

    public static String encodeDriveMode(int mode) {
        if (mode < 1 || mode > 255) throw new IllegalArgumentException("Drive mode must be 1..255");
        return MODE_PREFIX + String.format(Locale.US, "%02x", mode);
    }

    /** The drive mode of a {@code MODE} line, or null for any other line. */
    public static Integer decodeDriveMode(String line) {
        if (line == null) return null;
        String text = line.trim();
        if (!text.startsWith(MODE_PREFIX) || text.length() != MODE_PREFIX.length() + 2) return null;
        try {
            int mode = (digit(text.charAt(5)) << 4) | digit(text.charAt(6));
            return realMode(mode);
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }

    /**
     * Drive mode from {@code SystemService: SYS_DRIVE_MODE_DATA_M_RESP: 0x0f 0x01 0x03 ...} (byte 2).
     * The head unit reports 0x00 a moment after each change; that and unrelated lines return null.
     */
    public static Integer parseDriveModeLogLine(String line) {
        if (line == null) return null;
        int at = line.indexOf(DRIVE_MODE_MARKER);
        if (at < 0) return null;
        String[] parts = line.substring(at + DRIVE_MODE_MARKER.length()).trim().split("\\s+");
        if (parts.length < 3 || parts[2].length() != 4 || !parts[2].startsWith("0x")) return null;
        try {
            int mode = (digit(parts[2].charAt(2)) << 4) | digit(parts[2].charAt(3));
            return realMode(mode);
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }

    public static String encodeMyDrive(int[] feedback) {
        if (feedback == null || feedback.length != 3)
            throw new IllegalArgumentException("My Drive feedback must have 3 values");
        return encodeMyDrive(feedback[0], feedback[1], feedback[2]);
    }

    public static String encodeMyDrive(int powertrain, int suspension, int steering) {
        if (powertrain < 0 || powertrain > 255 || suspension < 0 || suspension > 255
                || steering < 0 || steering > 255)
            throw new IllegalArgumentException("My Drive feedback values must be 0..255");
        return MYDRIVE_PREFIX + powertrain + " " + suspension + " " + steering;
    }

    /** The 3 decimal My Drive feedback values of a {@code MYDRIVE} line, or null for any other line. */
    public static int[] decodeMyDrive(String line) {
        if (line == null) return null;
        String text = line.trim();
        if (!text.startsWith(MYDRIVE_PREFIX)) return null;
        String[] parts = text.substring(MYDRIVE_PREFIX.length()).trim().split("\\s+");
        if (parts.length != 3) return null;
        int a = parseByteDecimal(parts[0]);
        int b = parseByteDecimal(parts[1]);
        int c = parseByteDecimal(parts[2]);
        if (a < 0 || b < 0 || c < 0) return null;
        return new int[] {a, b, c};
    }

    /**
     * My Drive item feedback from {@code D/SystemService( 2060): mIndividualModeFeedback: [0] 4 [1] 5 [2] 5 ...}.
     * Returns {@code {powertrain, suspension, steering}} (indices [0]..[2], ignoring [3] and later),
     * or null for unrelated or malformed lines.
     */
    public static int[] parseMyDriveLogLine(String line) {
        if (line == null) return null;
        int at = line.indexOf(MYDRIVE_MARKER);
        if (at < 0) return null;
        String[] parts = line.substring(at + MYDRIVE_MARKER.length()).trim().split("\\s+");
        if (parts.length < 6) return null;
        if (!"[0]".equals(parts[0]) || !"[1]".equals(parts[2]) || !"[2]".equals(parts[4])) return null;
        int a = parseByteDecimal(parts[1]);
        int b = parseByteDecimal(parts[3]);
        int c = parseByteDecimal(parts[5]);
        if (a < 0 || b < 0 || c < 0) return null;
        return new int[] {a, b, c};
    }

    private static int parseByteDecimal(String token) {
        if (token == null || token.isEmpty() || token.length() > 3) return -1;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < '0' || c > '9') return -1;
        }
        int value = Integer.parseInt(token);
        return value <= 255 ? value : -1;
    }

    public static String encodeAlert(boolean active) {
        return active ? ALERT_ON : ALERT_OFF;
    }

    /** TRUE/FALSE for an {@code ALERT} line, null for any other line. */
    public static Boolean decodeAlert(String line) {
        if (line == null) return null;
        String text = line.trim();
        if (ALERT_ON.equals(text)) return Boolean.TRUE;
        if (ALERT_OFF.equals(text)) return Boolean.FALSE;
        return null;
    }

    /**
     * Reads the OEM LightService log line for the navigation camera alert, e.g.
     * {@code D/LightService( 2060): action : com.mnsoft.navi.action.NaviCameraAlertState,
     * mTempCameraAlertState : 1, mTempDelaySec : 1000, ...}. Returns TRUE for state 1, FALSE for 0,
     * null for unrelated lines or other values (captured 2026-11-09, 80 ms before the red status).
     */
    public static Boolean parseAlertLogLine(String line) {
        if (line == null || line.indexOf(ALERT_ACTION) < 0) return null;
        int at = line.indexOf(ALERT_FIELD);
        if (at < 0) return null;
        String rest = line.substring(at + ALERT_FIELD.length()).trim();
        if (rest.startsWith("1") && (rest.length() == 1 || !Character.isDigit(rest.charAt(1)))) return Boolean.TRUE;
        if (rest.startsWith("0") && (rest.length() == 1 || !Character.isDigit(rest.charAt(1)))) return Boolean.FALSE;
        return null;
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
