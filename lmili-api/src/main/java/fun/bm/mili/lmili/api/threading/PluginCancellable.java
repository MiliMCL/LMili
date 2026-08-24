package fun.bm.mili.lmili.api.threading;

/**
 * plugin 周期任务的取消句柄。
 */
public interface PluginCancellable {

    void cancel();

    boolean isCancelled();

    /** no-op fallback */
    final class Noop implements PluginCancellable {
        public static final Noop INSTANCE = new Noop();
        private Noop() {}
        @Override public void cancel() {}
        @Override public boolean isCancelled() { return true; }
    }
}
