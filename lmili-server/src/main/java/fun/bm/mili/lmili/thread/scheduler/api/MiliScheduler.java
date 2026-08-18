package fun.bm.mili.lmili.thread.scheduler.api;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Mili 调度器主接口 —— 新调度系统的公共 API。
 *
 * <p>这是整个调度系统的入口点，提供以下核心能力：
 * <ul>
 *   <li>{@link #submit(RegionTask)} —— 提交单个区域任务，立即返回 TaskHandle</li>
 *   <li>{@link #submitBatch(List)} —— 批量提交任务，统一追踪</li>
 *   <li>{@link #scheduleDelayed(RegionTask, long, TimeUnit)} —— 延迟执行</li>
 *   <li>{@link #forEntity(fun.bm.mili.lmili.thread.scheduler.api.EntityScheduler.EntityRef)} —— 实体调度</li>
 *   <li>{@link #shutdown()} —— 优雅关闭</li>
 * </ul>
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li><b>非阻塞</b>：所有提交方法立即返回，不阻塞调用线程</li>
 *   <li><b>可组合</b>：TaskHandle 支持链式组合和批量等待</li>
 *   <li><b>可观测</b>：内置性能指标和诊断接口</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>本接口的所有实现必须是线程安全的。多个线程可以同时提交任务。
 */
public interface MiliScheduler {

    /**
     * 提交一个区域任务执行。
     *
     * <p>任务会被路由到对应 region 的队列中，由调度器决定执行时机。
     * 此方法立即返回 {@link TaskHandle}，不阻塞调用线程。
     *
     * @param task 要执行的任务，不能为 null
     * @return 任务句柄，用于追踪完成状态
     * @throws NullPointerException 如果 task 为 null
     * @throws IllegalStateException 如果调度器已关闭
     */
    @NotNull TaskHandle submit(@NotNull RegionTask task);

    /**
     * 批量提交多个任务。
     *
     * <p>返回的 BatchHandle 追踪所有任务的完成状态。
     * 批量提交可以减少同步开销，适合一次性提交大量任务的场景。
     *
     * @param tasks 任务列表，不能为 null 或包含 null 元素
     * @return 批量任务句柄
     * @throws NullPointerException 如果 tasks 为 null 或包含 null 元素
     * @throws IllegalStateException 如果调度器已关闭
     */
    @NotNull BatchHandle submitBatch(@NotNull List<RegionTask> tasks);

    /**
     * 提交一个延迟执行的任务。
     *
     * <p>任务会在指定的延迟后被调度执行。延迟精度取决于调度器负载，
     * 不保证精确的时间准确性。
     *
     * @param task 要执行的任务
     * @param delay 延迟时间
     * @param unit 时间单位
     * @return 任务句柄（任务实际执行前可取消）
     * @throws IllegalStateException 如果调度器已关闭
     */
    @NotNull TaskHandle scheduleDelayed(@NotNull RegionTask task, long delay, @NotNull TimeUnit unit);

    /**
     * 获取实体调度器。
     *
     * <p>实体调度器提供针对单个实体的任务调度能力，确保任务在正确的线程上执行。
     *
     * @param entityRef 实体引用
     * @return 实体调度器
     */
    @NotNull EntityScheduler forEntity(@NotNull EntityScheduler.EntityRef entityRef);

    /**
     * 获取调度器当前的性能指标快照。
     *
     * @return 不可变的性能指标快照
     */
    @NotNull PerformanceSnapshot performanceSnapshot();

    /**
     * 优雅关闭调度器。
     *
     * <p>关闭过程：
     * <ol>
     *   <li>停止接受新任务</li>
     *   <li>等待已提交任务完成（最多等待 timeout）</li>
     *   <li>强制中断未完成的任务</li>
     *   <li>释放所有资源</li>
     * </ol>
     *
     * @param timeout 等待超时时间
     * @param unit 时间单位
     * @return true 如果所有任务在超时前完成
     * @throws InterruptedException 如果等待被中断
     */
    boolean shutdown(long timeout, @NotNull TimeUnit unit) throws InterruptedException;

    /**
     * 检查调度器是否已关闭。
     */
    boolean isShutdown();
}
