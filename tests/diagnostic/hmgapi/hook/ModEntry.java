package hmgapi.hook;

public abstract class ModEntry {
    public void zygoteInit(ModContext mod) throws Throwable {}
    public void onEnabled(ModContext mod, boolean enabled) throws Throwable {}
    public void onSettingsChanged(ModContext mod) throws Throwable {}
}
