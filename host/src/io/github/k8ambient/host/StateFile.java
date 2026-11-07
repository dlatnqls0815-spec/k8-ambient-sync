package io.github.k8ambient.host;

import io.github.k8ambient.core.AmbientState;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** PC-only persistence; Android integration must use android.util.AtomicFile. */
public final class StateFile {
    private static final String HEADER = "k8-ambient-sync-state-v1\n";

    private StateFile() {}

    public static AmbientState load(Path file) throws IOException {
        if (!Files.exists(file)) return null;
        byte[] bytes = new byte[129];
        int count = 0;
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while (count < bytes.length && (read = input.read(bytes, count, bytes.length - count)) != -1) {
                count += read;
            }
        }
        if (count == bytes.length) throw new IOException("State file exceeds 128 bytes");
        String text = new String(bytes, 0, count, StandardCharsets.US_ASCII);
        if (!text.startsWith(HEADER) || !text.endsWith("\n")) throw new IOException("Invalid state file");
        String[] fields = text.substring(HEADER.length(), text.length() - 1).split(",", -1);
        if (fields.length != 5 || !("true".equals(fields[4]) || "false".equals(fields[4]))) {
            throw new IOException("Invalid state fields");
        }
        try {
            int[] values = new int[4];
            for (int i = 0; i < values.length; i++) {
                if (!fields[i].matches("[0-9]{1,3}")) throw new IllegalArgumentException();
                values[i] = Integer.parseInt(fields[i]);
            }
            return new AmbientState(values[0], values[1], values[2], values[3], Boolean.parseBoolean(fields[4]));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid state values", invalid);
        }
    }

    public static void save(Path file, AmbientState state) throws IOException {
        if (state == null) throw new IllegalArgumentException("State is required");
        Path absolute = file.toAbsolutePath();
        Path temporary = Files.createTempFile(absolute.getParent(), ".ambient-state-", ".tmp");
        try {
            byte[] content = (HEADER + state.red + "," + state.green + "," + state.blue + ","
                    + state.brightness + "," + state.on + "\n").getBytes(StandardCharsets.US_ASCII);
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                output.write(content);
                output.getFD().sync();
            }
            // Fail rather than replace non-atomically on an unsupported filesystem.
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
