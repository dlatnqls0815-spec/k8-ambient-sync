package hmgapi.hook;

public abstract class MethodHook {
    protected void afterHookedMethod(Param param) throws Throwable {}

    public final void simulate(Object result, Throwable error, Object... args) throws Throwable {
        afterHookedMethod(new Param(result, error, args));
    }

    public static final class Param {
        public final Object[] args;
        private final Object result;
        private final Throwable error;
        private Param(Object result, Throwable error, Object[] args) {
            this.result = result;
            this.error = error;
            this.args = args;
        }
        public Object getResult() { return result; }
        public boolean hasThrowable() { return error != null; }
    }
}
