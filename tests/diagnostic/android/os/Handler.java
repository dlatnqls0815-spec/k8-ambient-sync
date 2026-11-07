package android.os;

public final class Handler {
    private final Looper looper;
    public Handler(Looper looper) { this.looper = looper; }
    public boolean postDelayed(Runnable runnable, long delay) { return looper.postDelayed(runnable, delay); }
    public void advanceBy(long elapsed) { looper.advanceBy(elapsed); }
}
