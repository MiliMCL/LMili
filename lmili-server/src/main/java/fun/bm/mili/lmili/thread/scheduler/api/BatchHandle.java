package fun.bm.mili.lmili.thread.scheduler.api;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 批量任务句柄 —— 追踪一批相关任务的完成状态。
 *
 * <p>通过 {@link MiliScheduler#submitBatch(List)} 获取。
 * 除了继承 TaskHandle 的能力外，还支持：
 * <ul>
 *   <li>查询单个任务状态</li>
 *   <li>等待部分任务完成</li>
 *   <li>获取所有失败任务</li>
 * </ul>
 */
public interface BatchHandle extends TaskHandle {

    /**
     * 获取批量任务中的子任务数量。
     */
    int batchSize();

    /**
     * 获取指定索引位置的子任务句柄。
     *
     * @param index 子任务索引（0-based）
     * @return 子任务句柄
     * @throws IndexOutOfBoundsException 如果索引越界
     */
    @NotNull TaskHandle getChild(int index);

    /**
     * 获取所有子任务句柄。
     */
    @NotNull List<TaskHandle> children();

    /**
     * 获取所有已失败的子任务句柄。
     */
    @NotNull List<TaskHandle> failedChildren();

    /**
     * 等待至少指定数量的子任务完成。
     *
     * @param count 需要完成的任务数量
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return true 如果至少 count 个任务在超时前完成
     * @throws InterruptedException 如果等待被中断
     */
    boolean awaitUntil(int count, long timeout, @NotNull TimeUnit unit) throws InterruptedException, TimeoutException;

    /**
     * 获取已完成（成功）的子任务数量。
     */
    int successCount();
}
