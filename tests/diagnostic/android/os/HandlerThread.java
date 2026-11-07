package android.os;

public final class HandlerThread {
    public static int activeCount;
    private final Looper looper = new Looper();
    private boolean active;
    public HandlerThread(String name) {}
    public void start() { if (!active) { active = true; activeCount++; } }
    public Looper getLooper() { return looper; }
    public boolean quit() {
        if (active) { active = false; activeCount--; }
        looper.quit();
        return true;
    }
}
