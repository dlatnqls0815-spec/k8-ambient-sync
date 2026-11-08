import io.github.k8ambient.bleprobe.ProbeLogic;

public final class ProbeLogicTestMain {
    private static int checks;

    private static void check(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }

    private static void rejects(String[] args, String code) {
        checks++;
        try { ProbeLogic.parse(args); }
        catch (IllegalArgumentException expected) {
            if (!code.equals(expected.getMessage())) throw new AssertionError("wrong code " + expected.getMessage());
            return;
        }
        throw new AssertionError("Expected rejection " + code);
    }

    public static void main(String[] args) {
        check(ProbeLogic.parse(new String[0]).address == null, "no address means scan-only");
        check(ProbeLogic.parse(new String[] {"--scan-only"}).scanSeconds == 12, "default scan time");
        ProbeLogic.Options target = ProbeLogic.parse(new String[] {"--address", " aa:bb:cc:dd:ee:0f ", "--scan-seconds", "20"});
        check("AA:BB:CC:DD:EE:0F".equals(target.address) && target.scanSeconds == 20, "address normalized");
        rejects(new String[] {"--address", "AA-BB-CC-DD-EE-FF"}, "INVALID_ADDRESS");
        rejects(new String[] {"--address", "AA:BB:CC:DD:EE:FG"}, "INVALID_ADDRESS");
        rejects(new String[] {"--scan-seconds", "0"}, "INVALID_SCAN_SECONDS");
        rejects(new String[] {"--scan-seconds", "31"}, "INVALID_SCAN_SECONDS");
        rejects(new String[] {"--scan-seconds", "x"}, "INVALID_SCAN_SECONDS");
        rejects(new String[] {"--write"}, "UNKNOWN_ARGUMENT");

        check("XX:XX:XX:XX:EE:FF".equals(ProbeLogic.maskAddress("AA:BB:CC:DD:EE:FF")), "mask keeps last two octets");

        byte[] flagsThenFff0 = {2, 0x01, 0x06, 5, 0x03, (byte) 0xe0, (byte) 0xff, (byte) 0xf0, (byte) 0xff, 0, 0};
        check(ProbeLogic.advertisesService(flagsThenFff0, 0xfff0), "FFF0 in complete 16-bit list");
        byte[] incomplete = {3, 0x02, (byte) 0xf0, (byte) 0xff};
        check(ProbeLogic.advertisesService(incomplete, 0xfff0), "FFF0 in incomplete 16-bit list");
        byte[] nameOnly = {5, 0x09, 'L', 'a', 'm', 'p'};
        check(!ProbeLogic.advertisesService(nameOnly, 0xfff0), "name does not imply service");
        byte[] truncated = {9, 0x03, (byte) 0xf0, (byte) 0xff};
        check(!ProbeLogic.advertisesService(truncated, 0xfff0), "truncated structure is ignored");
        check(!ProbeLogic.advertisesService(null, 0xfff0), "null record");

        check(ProbeLogic.isShortUuid("0000FFF0-0000-1000-8000-00805F9B34FB", 0xfff0), "base UUID match");
        check(!ProbeLogic.isShortUuid("0000fff0-0000-1000-8000-00805f9b34fc", 0xfff0), "non-base UUID");

        check("CONNECTED_FFF0_NOT_FOUND".equals(ProbeLogic.classify(false, false, 0)), "no service");
        check("CONNECTED_FFF3_NOT_FOUND".equals(ProbeLogic.classify(true, false, 0)), "no characteristic");
        check("CONNECTED_FFF0_FFF3_WRITE_NO_RESPONSE".equals(ProbeLogic.classify(true, true, 0x0c)), "write without response");
        check("CONNECTED_FFF0_FFF3_WRITE".equals(ProbeLogic.classify(true, true, 0x08)), "write with response");
        check("CONNECTED_FFF0_FFF3_NOT_WRITABLE".equals(ProbeLogic.classify(true, true, 0x02)), "read only");
        check("READ|WRITE_NO_RESPONSE".equals(ProbeLogic.describeProperties(0x06)), "property names");
        check("NONE".equals(ProbeLogic.describeProperties(0)), "no properties");
        System.out.println("ProbeLogicTestMain: " + checks + " checks passed");
    }
}
