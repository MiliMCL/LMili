package fun.bm.mili.lmili.thread.regiontick;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Region tick 执行器接口 —— 单方法 SAM + P0-2 新增的 default {@link #executeSliceStage}。
 *
 * <p><b>P0-2 提交 / 完成 / 提交 语义分离</b>：默认 {@link #executeSlice} 仍然同步阻塞，
 * 但支持异步语义的执行器（{@code ModernDagTickExecutor}）应同时实现
 * {@link #executeSliceStage}，返回 DAG 的非阻塞完成阶段。Region tick 调度器
 * （{@code ChunkTickDispatcher}）负责把完成阶段连接到 slice barrier —— 这样 region tick
 * 线程通过统一的 {@code awaitTickCompletion} 屏障等待全部子系统完成，不再被内部
 * {@code join()} 阻塞。
 *
 * <p><b>完成契约</b>：
 * <ol>
 *   <li>异步执行器的 {@link #executeSliceStage} 在节点提交成功后立即返回，
 *       <b>不会</b>阻塞调用线程。</li>
 *   <li>返回的 {@link CompletionStage} 在以下情况之一完成：
 *       <ul>><li>正常完成 —— 成功路径，{@code result == null}</li>
 *           <li>异常完成 —— 包含实际异常（包括 {@code DagSchedulerUnavailableException}）</li></ul></li>
 *   <li>调用方负责把完成阶段连接到 slice barrier（{@code arriveSlice/failSlice + endTick}）；
 *       不允许两边都重复调用同一 barrier —— 否则违反 generation isolation（P0-5 §5.3）。</li>
 * </ol>
 */
@FunctionalInterface
public interface RegionTickExecutor {

    void executeSlice(@NotNull RegionTickWorker worker,
                      @NotNull RegionTickSlice slice,
                      @NotNull RegionTickContext context);

    /**
     * 异步执行 slice 并返回非阻塞完成阶段。
     *
     * <p>P0-2 默认实现：同步执行 {@link #executeSlice} 后立即返回已完成阶段。
     * 异步执行器必须重写以真正实现非阻塞语义。</p>
     *
     * <p>返回阶段的语义与 {@link #executeSlice} 的隐式 join 等价，但允许调用方
     * 选择 <b>连接完成阶段到 slice barrier</b>而非直接等待。</p>
     *
     * @param worker  执行 worker（可为 null —— 由 RegionTickDispatcher 传入）
     * @param slice   tick 切片（包含 generationId —— 用于完成时的 generation 验证）
     * @param context region tick 上下文
     * @return 完成阶段；在切片工作全部完成时正常完成，失败时异常完成
     */
    default @NotNull CompletionStage<Void> executeSliceStage(@NotNull RegionTickWorker worker,
                                                              @NotNull RegionTickSlice slice,
                                                              @NotNull RegionTickContext context) {
        executeSlice(worker, slice, context);
        return CompletableFuture.completedFuture(null);
    }

    class Registry {
        private static volatile RegionTickExecutor executor;
        public static void register(@NotNull RegionTickExecutor exec) { executor = exec; }
        public static RegionTickExecutor get() { return executor; }
    }

    static RegionTickExecutor getRegisteredExecutor() { return Registry.get(); }
    static void register(@NotNull RegionTickExecutor executor) { Registry.register(executor); }
}