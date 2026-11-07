package android.content;

/** Test-only signature; real API compatibility is checked separately. */
public abstract class BroadcastReceiver {
    public abstract void onReceive(Context context, Intent intent);
}
