package io.github.k8ambient.core;

/**
 * Pure decision rules for the car relay's Wi-Fi keeper (no Android or process access, Java 7).
 *
 * <p>After ignition the head unit's built-in Wi-Fi is off and the user had to switch it on by hand
 * (2026-11-18). The keeper turns the client radio on in a short window after boot or ignition so the head
 * unit joins the phone hotspot, but never while the car's own hotspot (wireless Android Auto / CarPlay AP)
 * is running, because the single Wi-Fi chip cannot be both. Since 0.4.3 it also looks every
 * {@link #WATCH_SECONDS} outside a window (an app update could reset the head unit's Wi-Fi without a relay
 * restart), and a switch-off from the user's own Wi-Fi screen or status bar is respected until the next trigger.
 */
public final class WifiKeeperPolicy {
    public enum Decision { ENABLE, ALREADY_ON, YIELD_TO_AP, STATE_UNKNOWN, LIMIT_REACHED, DISABLED, USER_OFF }

    /** Seconds between background looks outside a window (head unit resets without a relay restart). */
    public static final int WATCH_SECONDS = 120;
    private static final String SET_WIFI = "setWifiEnabled: ";

    /** Seconds after the trigger before the first look; Wi-Fi and the settings service need to be up. */
    public static final int SETTLE_SECONDS = 20;
    /** Seconds between looks while a window is open. */
    public static final int POLL_SECONDS = 10;
    /** Seconds a window stays open after the settle time. */
    public static final int WINDOW_SECONDS = 180;
    /** Most enable commands per window; a unit that switches Wi-Fi off again is not fought forever. */
    public static final int MAX_ENABLES = 4;

    private WifiKeeperPolicy() {
    }

    /**
     * @param wifiOn         parsed {@code settings get global wifi_on}: TRUE, FALSE or null when unreadable
     * @param accessPoint    the car hotspot daemon (hostapd) is running
     * @param enablesSoFar   enable commands already sent in this window
     * @param switchOffFile  the user's kill-switch file exists
     */
    public static Decision decide(Boolean wifiOn, boolean accessPoint, int enablesSoFar, boolean switchOffFile) {
        return decide(wifiOn, accessPoint, enablesSoFar, switchOffFile, false);
    }

    /** @param userOff the user switched Wi-Fi off from the Wi-Fi screen or status bar since the last trigger */
    public static Decision decide(Boolean wifiOn, boolean accessPoint, int enablesSoFar, boolean switchOffFile,
                                  boolean userOff) {
        if (switchOffFile) return Decision.DISABLED;
        if (accessPoint) return Decision.YIELD_TO_AP;
        if (wifiOn == null) return Decision.STATE_UNKNOWN;
        if (wifiOn.booleanValue()) return Decision.ALREADY_ON;
        if (userOff) return Decision.USER_OFF;
        if (enablesSoFar >= MAX_ENABLES) return Decision.LIMIT_REACHED;
        return Decision.ENABLE;
    }

    /** Parses the output of {@code settings get global wifi_on}: "1" is on, "0" is off, anything else is unknown. */
    public static Boolean parseWifiOn(String output) {
        if (output == null) return null;
        String value = output.trim();
        if ("1".equals(value)) return Boolean.TRUE;
        if ("0".equals(value)) return Boolean.FALSE;
        return null;
    }

    /**
     * Parses a WifiService log line such as {@code "D/WifiService(  607): setWifiEnabled: true pid=4021, uid=1000"}
     * (seen on the car 2026-11-18). Returns {enabled 1/0, pid, uid} or null.
     */
    public static int[] parseSetWifiEnabled(String line) {
        if (line == null) return null;
        int at = line.indexOf(SET_WIFI);
        if (at < 0) return null;
        String rest = line.substring(at + SET_WIFI.length());
        int enabled;
        if (rest.startsWith("true")) enabled = 1;
        else if (rest.startsWith("false")) enabled = 0;
        else return null;
        int pid = number(rest, "pid="), uid = number(rest, "uid=");
        if (pid < 0 || uid < 0) return null;
        return new int[] {enabled, pid, uid};
    }

    private static int number(String text, String key) {
        int at = text.indexOf(key);
        if (at < 0) return -1;
        int start = at + key.length(), end = start;
        while (end < text.length() && end - start < 9 && Character.isDigit(text.charAt(end))) end++;
        if (end == start) return -1;
        return Integer.parseInt(text.substring(start, end));
    }

    /** True when the process that called setWifiEnabled is a screen the user switches Wi-Fi with. */
    public static boolean isUserWifiApp(String processName) {
        if (processName == null) return false;
        String name = processName.trim();
        return name.startsWith("com.hkmc.wifisettings") || name.contains("systemui") || name.contains("statusbar");
    }

    /** True when a {@code ps} listing shows a hostapd process (the car hotspot). */
    public static boolean hasAccessPoint(String psOutput) {
        if (psOutput == null) return false;
        String[] lines = psOutput.split("\n");
        for (String line : lines) {
            String text = line.trim();
            if (text.endsWith("hostapd") || text.contains("/hostapd ")) return true;
        }
        return false;
    }
}
