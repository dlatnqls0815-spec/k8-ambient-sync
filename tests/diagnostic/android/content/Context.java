package android.content;

import android.os.Handler;

public abstract class Context {
    public abstract Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String permission, Handler handler);
    public abstract void unregisterReceiver(BroadcastReceiver receiver);
}
