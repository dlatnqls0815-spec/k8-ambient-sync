package io.github.k8ambient.relay;

import io.github.k8ambient.core.WifiKeeperPolicy;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Switches the head unit's built-in Wi-Fi on after boot and ignition so it joins the phone hotspot
 * (and the wireless ADB that already listens on port 5555 is reachable) without the user opening the
 * Wi-Fi settings each time. Rules live in {@link WifiKeeperPolicy}; this class only runs commands.
 *
 * <p>It runs {@code svc wifi enable}, the same call the vehicle Wi-Fi settings screen makes, and only inside
 * a short window after a trigger. It yields while the car's own hotspot (hostapd) runs. It sends no vehicle
 * commands and changes no system properties or files. Every look and action is logged to the relay log.
 * Create the file {@link #SWITCH_OFF_FILE} to turn it off without reinstalling.
 */
final class WifiKeeper {
    static final String SWITCH_OFF_FILE = "/data/local/tmp/k8-ambient-relay/wifi-keeper.off";
    private static final long COMMAND_TIMEOUT_MS = 15000;
    private boolean running;

    /** Starts a window unless one is already open. Safe to call from any thread. */
    void trigger(final String reason) {
        synchronized (this) {
            if (running) {
                CarRelay.log("wifi: " + reason + " (window already open)");
                return;
            }
            running = true;
        }
        Thread thread = new Thread(new Runnable() {
            public void run() {
                try {
                    window(reason);
                } finally {
                    synchronized (WifiKeeper.this) { running = false; }
                }
            }
        }, "wifi-keeper");
        thread.setDaemon(true);
        thread.start();
    }

    private void window(String reason) {
        CarRelay.log("wifi: window start (" + reason + ")");
        sleepSeconds(WifiKeeperPolicy.SETTLE_SECONDS);
        int enables = 0;
        String last = null;
        int polls = WifiKeeperPolicy.WINDOW_SECONDS / WifiKeeperPolicy.POLL_SECONDS;
        for (int i = 0; i <= polls; i++) {
            Boolean on = WifiKeeperPolicy.parseWifiOn(run("settings", "get", "global", "wifi_on"));
            boolean accessPoint = WifiKeeperPolicy.hasAccessPoint(run("ps"));
            boolean off = new File(SWITCH_OFF_FILE).exists();
            WifiKeeperPolicy.Decision decision = WifiKeeperPolicy.decide(on, accessPoint, enables, off);
            String state = "on=" + on + " ap=" + accessPoint + " " + decision;
            if (!state.equals(last)) {
                CarRelay.log("wifi: " + state + " persist=" + run("getprop", "persist.wifi.state").trim());
                last = state;
            }
            if (decision == WifiKeeperPolicy.Decision.DISABLED || decision == WifiKeeperPolicy.Decision.LIMIT_REACHED
                    || decision == WifiKeeperPolicy.Decision.YIELD_TO_AP) {
                break;
            }
            if (decision == WifiKeeperPolicy.Decision.ENABLE) {
                enables++;
                String out = run("svc", "wifi", "enable").trim();
                CarRelay.log("wifi: svc wifi enable #" + enables + (out.length() == 0 ? "" : " -> " + out));
                last = null;  // log the result of the next look even if it reads the same
            }
            sleepSeconds(WifiKeeperPolicy.POLL_SECONDS);
        }
        CarRelay.log("wifi: window end");
    }

    /** Runs one command with a timeout and returns its output ("" on failure). */
    private static String run(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final Process watched = process;
            Thread watchdog = new Thread(new Runnable() {
                public void run() {
                    try {
                        Thread.sleep(COMMAND_TIMEOUT_MS);
                        watched.destroy();
                    } catch (InterruptedException done) {
                        // command finished in time
                    }
                }
            }, "wifi-cmd-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && text.length() < 20000) text.append(line).append('\n');
            watchdog.interrupt();
            return text.toString();
        } catch (IOException error) {
            return "";
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static void sleepSeconds(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
