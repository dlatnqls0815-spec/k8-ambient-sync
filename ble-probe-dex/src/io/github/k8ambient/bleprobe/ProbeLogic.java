package io.github.k8ambient.bleprobe;

import java.util.Locale;

/** Android-free helpers for the headless probe, so they can be tested on a desktop JVM (Java 7). */
public final class ProbeLogic {
    public static final String VERSION = "0.2.0";
    public static final int DEFAULT_SCAN_SECONDS = 12;
    public static final int MAX_SCAN_SECONDS = 30;
    /** 16-bit UUIDs of the LotusLamp X service and write characteristic seen over HCI. */
    public static final int SERVICE_16 = 0xfff0, WRITE_16 = 0xfff3;
    /** BluetoothGattCharacteristic property bits (API 18+ constants). */
    static final int PROPERTY_WRITE_NO_RESPONSE = 0x04, PROPERTY_WRITE = 0x08;

    private ProbeLogic() {}

    /** Parsed command line: a target address, or null for a scan-only listing. */
    public static final class Options {
        public final String address;
        public final int scanSeconds;
        Options(String address, int scanSeconds) {
            this.address = address;
            this.scanSeconds = scanSeconds;
        }
    }

    public static Options parse(String[] args) {
        String address = null;
        int seconds = DEFAULT_SCAN_SECONDS;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--address".equals(arg) && i + 1 < args.length) {
                address = normalizeAddress(args[++i]);
                if (address == null) throw new IllegalArgumentException("INVALID_ADDRESS");
            } else if ("--scan-seconds".equals(arg) && i + 1 < args.length) {
                try { seconds = Integer.parseInt(args[++i]); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("INVALID_SCAN_SECONDS"); }
                if (seconds < 1 || seconds > MAX_SCAN_SECONDS) throw new IllegalArgumentException("INVALID_SCAN_SECONDS");
            } else if (!"--scan-only".equals(arg)) {
                throw new IllegalArgumentException("UNKNOWN_ARGUMENT");
            }
        }
        return new Options(address, seconds);
    }

    /** Accepts AA:BB:CC:DD:EE:FF (any case, surrounding spaces); returns upper case or null. */
    public static String normalizeAddress(String text) {
        if (text == null) return null;
        String value = text.trim().toUpperCase(Locale.US);
        if (value.length() != 17) return null;
        for (int i = 0; i < 17; i++) {
            char c = value.charAt(i);
            boolean colon = i % 3 == 2;
            if (colon ? c != ':' : !((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F'))) return null;
        }
        return value;
    }

    /** Hides the first four octets of devices we are not targeting. */
    public static String maskAddress(String address) {
        if (address == null || address.length() != 17) return "??:??:??:??:??:??";
        return "XX:XX:XX:XX" + address.substring(11);
    }

    /** True when the advertisement lists 0xFFF0 in a 16-bit service UUID field (AD types 0x02/0x03). */
    public static boolean advertisesService(byte[] record, int uuid16) {
        if (record == null) return false;
        int i = 0;
        while (i < record.length) {
            int length = record[i] & 255;
            // One AD structure is [length][type][data...]; length counts type + data.
            if (length == 0 || i + length >= record.length) break;
            int type = record[i + 1] & 255;
            if (type == 0x02 || type == 0x03) {
                for (int j = i + 2; j + 1 <= i + length; j += 2) {
                    int value = (record[j] & 255) | ((record[j + 1] & 255) << 8);
                    if (value == uuid16) return true;
                }
            }
            i += length + 1;
        }
        return false;
    }

    /** Matches a full 128-bit Bluetooth base UUID string against a 16-bit short UUID. */
    public static boolean isShortUuid(String uuid, int uuid16) {
        if (uuid == null) return false;
        String expected = String.format(Locale.US, "0000%04x-0000-1000-8000-00805f9b34fb", uuid16);
        return expected.equalsIgnoreCase(uuid);
    }

    /** Final result code once services are known. */
    public static String classify(boolean serviceFound, boolean characteristicFound, int properties) {
        if (!serviceFound) return "CONNECTED_FFF0_NOT_FOUND";
        if (!characteristicFound) return "CONNECTED_FFF3_NOT_FOUND";
        if ((properties & PROPERTY_WRITE_NO_RESPONSE) != 0) return "CONNECTED_FFF0_FFF3_WRITE_NO_RESPONSE";
        if ((properties & PROPERTY_WRITE) != 0) return "CONNECTED_FFF0_FFF3_WRITE";
        return "CONNECTED_FFF0_FFF3_NOT_WRITABLE";
    }

    public static String describeProperties(int properties) {
        StringBuilder text = new StringBuilder();
        String[] names = {"BROADCAST", "READ", "WRITE_NO_RESPONSE", "WRITE", "NOTIFY", "INDICATE", "SIGNED_WRITE", "EXTENDED"};
        for (int bit = 0; bit < names.length; bit++) {
            if ((properties & (1 << bit)) == 0) continue;
            if (text.length() > 0) text.append('|');
            text.append(names[bit]);
        }
        return text.length() == 0 ? "NONE" : text.toString();
    }
}
