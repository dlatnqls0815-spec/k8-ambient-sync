package io.github.k8ambient.host;

import io.github.k8ambient.core.AmbientState;
import io.github.k8ambient.core.SyncEngine;
import io.github.k8ambient.core.WporterE1Candidate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class HostTestMain {
    private static int checks;

    private static final class MockTransport implements SyncEngine.Transport {
        boolean connected;
        int writes;
        WporterE1Candidate.Command color, brightness, power;
        public boolean connected() { return connected; }
        public void connect() { connected = true; }
        public void disconnect() { connected = false; }
        public void write(byte[] packet) {
            WporterE1Candidate.Command command = WporterE1Candidate.decode(packet);
            writes++;
            if (command.kind == WporterE1Candidate.Kind.COLOR) color = command;
            if (command.kind == WporterE1Candidate.Kind.BRIGHTNESS) brightness = command;
            if (command.kind == WporterE1Candidate.Kind.POWER) power = command;
        }
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
        checks++;
    }

    private static void rejects(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.US_ASCII));
        try {
            StateFile.load(file);
            throw new AssertionError("Corrupt state accepted");
        } catch (IOException expected) {
            checks++;
        }
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory(Paths.get("build"), "state-test-");
        Path file = directory.resolve("last-state.txt");
        try {
            check(StateFile.load(file) == null, "Missing state is unknown");
            AmbientState first = new AmbientState(255, 0, 128, 100, true);
            StateFile.save(file, first);
            check(first.equals(StateFile.load(file)), "State survives a fresh read");
            MockTransport mock = new MockTransport();
            SyncEngine restarted = new SyncEngine(mock, 100);
            restarted.onState(StateFile.load(file));
            restarted.tick(0);
            check(mock.writes == 0 && !mock.connected, "Restoring a file never enables synchronization");
            restarted.setEnabled(true);
            restarted.tick(0);
            restarted.tick(50);
            restarted.tick(100);
            check(mock.color.red == 255 && mock.color.green == 0 && mock.color.blue == 128
                    && mock.brightness.brightness == 100 && mock.power.on,
                    "Fresh engine restores persisted synthetic state to the mock");
            AmbientState off = new AmbientState(4, 5, 6, 0, false);
            StateFile.save(file, off);
            check(off.equals(StateFile.load(file)), "Existing state replaced, including OFF");
            try {
                StateFile.save(file, null);
                throw new AssertionError("Null state accepted");
            } catch (IllegalArgumentException expected) {
                check(off.equals(StateFile.load(file)), "Invalid save preserves previous state");
            }
            String header = "k8-ambient-sync-state-v1\n";
            rejects(file, header + "256,0,0,100,true\n");
            rejects(file, header + "0,0,0,101,true\n");
            rejects(file, header + "0,0,0,1,TRUE\n");
            rejects(file, header + "0,0,0,1,true\nextra\n");
            rejects(file, header + "0,0,0,1,true");
            rejects(file, "k8-ambient-sync-state-v2\n0,0,0,1,true\n");
            rejects(file, header + "-1,0,0,1,true\n");
            rejects(file, new String(new char[129]).replace('\0', 'x'));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
        System.out.println("Host state persistence: " + checks + " checks passed");
    }
}
