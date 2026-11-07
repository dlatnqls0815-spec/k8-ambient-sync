package android.os;

import java.util.ArrayList;
import java.util.List;

/** Deterministic FIFO timers, not a simulation of Android thread races. */
public final class Looper {
    private static final class Job {
        final Runnable runnable;
        final long at;
        Job(Runnable runnable, long at) { this.runnable = runnable; this.at = at; }
    }

    private final List<Job> jobs = new ArrayList<Job>();
    private long now;
    private boolean stopped;

    boolean postDelayed(Runnable runnable, long delay) {
        if (stopped) return false;
        jobs.add(new Job(runnable, now + delay));
        return true;
    }

    public void advanceBy(long elapsed) {
        long end = now + elapsed;
        while (!stopped) {
            Job next = null;
            for (Job job : jobs) if (job.at <= end && (next == null || job.at < next.at)) next = job;
            if (next == null) break;
            jobs.remove(next);
            now = next.at;
            next.runnable.run();
        }
        now = end;
    }

    void quit() { stopped = true; jobs.clear(); }
}
