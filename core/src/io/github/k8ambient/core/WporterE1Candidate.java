package io.github.k8ambient.core;

/** Offline-only candidate from the pinned source in docs/research/LOTUS_LAMP_X.md. */
public final class WporterE1Candidate {
    public enum Kind { COLOR, BRIGHTNESS, POWER }

    public static final class Command {
        public final Kind kind;
        public final int red, green, blue, brightness;
        public final boolean on;

        private Command(Kind kind, int red, int green, int blue, int brightness, boolean on) {
            this.kind = kind;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.brightness = brightness;
            this.on = on;
        }
    }

    private WporterE1Candidate() {}

    public static byte[] color(int red, int green, int blue) {
        AmbientState.checkRange(red, 255);
        AmbientState.checkRange(green, 255);
        AmbientState.checkRange(blue, 255);
        return new byte[] {0x7e, 7, 5, 3, (byte) red, (byte) green, (byte) blue, 0x10, (byte) 0xef};
    }

    public static byte[] brightness(int percent) {
        AmbientState.checkRange(percent, 100);
        return new byte[] {0x7e, 7, 1, (byte) percent, -1, -1, -1, 0, (byte) 0xef};
    }

    public static byte[] power(boolean on) {
        return new byte[] {0x7e, 7, 4, (byte) (on ? 1 : 0), 0, -1, -1, 0, (byte) 0xef};
    }

    public static Command decode(byte[] packet) {
        if (packet == null || packet.length != 9 || packet[0] != 0x7e
                || packet[1] != 7 || packet[8] != (byte) 0xef)
            throw new IllegalArgumentException("Invalid candidate frame");
        switch (packet[2]) {
            case 5:
                require(packet[3] == 3 && packet[7] == 0x10);
                return new Command(Kind.COLOR, packet[4] & 255, packet[5] & 255, packet[6] & 255, -1, false);
            case 1:
                require((packet[3] & 255) <= 100 && packet[4] == -1 && packet[5] == -1
                        && packet[6] == -1 && packet[7] == 0);
                return new Command(Kind.BRIGHTNESS, -1, -1, -1, packet[3] & 255, false);
            case 4:
                require((packet[3] == 0 || packet[3] == 1) && packet[4] == 0
                        && packet[5] == -1 && packet[6] == -1 && packet[7] == 0);
                return new Command(Kind.POWER, -1, -1, -1, -1, packet[3] == 1);
            default:
                throw new IllegalArgumentException("Unsupported candidate command");
        }
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid candidate command fields");
    }
}
