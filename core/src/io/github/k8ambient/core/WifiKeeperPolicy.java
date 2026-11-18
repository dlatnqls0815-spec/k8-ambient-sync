package io.github.k8ambient.core;

/**
 * Pure decision rules for the car relay's Wi-Fi keeper (no Android or process access, Java 7).
 *
 * <p>After ignition the head unit's built-in Wi-Fi is off and the user had to switch it on by hand
 * (2026-11-18). The keeper turns the client radio on in a short window after boot or ignition so the head
 * unit joins the phone hotspot, but never while the car's own hotspot (wireless Android Auto / CarPlay AP)
 * is running, because the single Wi-Fi chip cannot be both. After the window it leaves Wi-Fi alone, so a
 * later manual switch-off stays off.
 */
public final class WifiKeeperPolicy {
    public enum Decision { ENABLE, ALREADY_ON, YIELD_TO_AP, STATE_UNKNOWN, LIMIT_REACHED, DISABLED }

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
        if (switchOffFile) return Decision.DISABLED;
        if (accessPoint) return Decision.YIELD_TO_AP;
        if (wifiOn == null) return Decision.STATE_UNKNOWN;
        if (wifiOn.booleanValue()) return Decision.ALREADY_ON;
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
