package io.github.k8ambient.phone;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Rolling event log for intermittent failures ("가끔 안 됨"), kept in the app's own external files
 * directory so it can be pulled over USB long after the system log has rolled over:
 * {@code adb pull /sdcard/Android/data/io.github.k8ambient.phone/files/relay-events.log}.
 *
 * <p>One line per event: {@code MM-dd HH:mm:ss.SSS <tag> <text>}. A message identical to the previous
 * one for the same tag is not written again; the count is written when the message changes.
 * Holds no addresses or other identifiers. Thread-safe.
 */
final class DiagLog {
    static final String FILE_NAME = "relay-events.log";
    private static final long MAX_BYTES = 1024 * 1024;
    private static final Object LOCK = new Object();
    private static final Map<String, String> LAST = new HashMap<String, String>();
    private static final Map<String, Integer> REPEATS = new HashMap<String, Integer>();
    private static File file;

    private DiagLog() {}

    static void init(Context context) {
        synchronized (LOCK) {
            if (file != null) return;
            File dir = context.getApplicationContext().getExternalFilesDir(null);
            if (dir == null) dir = context.getApplicationContext().getFilesDir();
            file = new File(dir, FILE_NAME);
        }
    }

    static String path() {
        synchronized (LOCK) { return file == null ? "-" : file.getAbsolutePath(); }
    }

    static void event(String tag, String text) {
        synchronized (LOCK) {
            if (file == null || text == null) return;
            if (text.equals(LAST.get(tag))) {
                Integer n = REPEATS.get(tag);
                REPEATS.put(tag, n == null ? 1 : n + 1);
                return;
            }
            Integer repeated = REPEATS.remove(tag);
            if (repeated != null) write(tag, "(앞 줄 " + repeated + "회 더 반복)");
            LAST.put(tag, text);
            write(tag, text);
        }
    }

    /** Caller holds {@link #LOCK}. Logging must never break the relay, so errors are ignored. */
    private static void write(String tag, String text) {
        try {
            if (file.length() > MAX_BYTES) {
                File old = new File(file.getParentFile(), FILE_NAME + ".1");
                old.delete();
                file.renameTo(old);
            }
            String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                    + " " + tag + " " + text + "\n";
            FileOutputStream out = new FileOutputStream(file, true);
            try { out.write(line.getBytes("UTF-8")); } finally { out.close(); }
        } catch (IOException ignored) {
        } catch (RuntimeException ignored) {
        }
    }
}
