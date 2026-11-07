package android.content;

import java.util.HashMap;
import java.util.Map;

public final class Intent {
    private final String action;
    private final Map<String, Object> extras = new HashMap<String, Object>();

    public Intent(String action) { this.action = action; }
    public String getAction() { return action; }
    public Intent putExtra(String key, Object value) { extras.put(key, value); return this; }
    public byte[] getByteArrayExtra(String key) { return (byte[]) extras.get(key); }
}
