package hmgapi.hook;

import java.util.ArrayList;
import java.util.List;

public final class ModContext {
    public boolean enabled = true;
    public Object diagnostics;
    public boolean failHook;
    public MethodHook hook;
    public final List<String> logs = new ArrayList<String>();

    public java.lang.reflect.Member hook(ClassLoader loader, String point, MethodHook callback) {
        if (failHook) throw new IllegalArgumentException("synthetic-private-detail");
        if (loader != null || !"framework.hkmc_loader.automotive_service".equals(point))
            throw new AssertionError("unexpected hook");
        hook = callback;
        return null;
    }

    public boolean enabled() { return enabled; }
    public boolean settingBoolean(String key, boolean fallback) {
        if (!"diagnostics".equals(key)) throw new AssertionError("unexpected setting");
        return diagnostics instanceof Boolean ? ((Boolean) diagnostics).booleanValue() : fallback;
    }
    public void log(String message) { logs.add(message); }
}
