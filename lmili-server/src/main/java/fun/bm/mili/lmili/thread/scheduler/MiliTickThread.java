package fun.bm.mili.lmili.thread.scheduler;

import ca.spottedleaf.moonrise.common.util.TickThread;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegions;
import org.jetbrains.annotations.Nullable;

/**
 * Mili 调度器的 TickThread 实现 —— 替代 Folia 的 TickThreadRunner。
 *
 * <p>此类扩展 {@link TickThread}，使 Mili 调度器的 worker 线程能够被
 * Folia 的线程安全检查（{@code TickThread.isTickThreadFor}）正确识别。
 *
 * <p>每个 worker 线程在执行 region tick 任务时，会设置当前 region 的上下文信息：
 * <ul>
 *   <li>{@link #currentTickingRegion} —— 当前正在 tick 的 region</li>
 *   <li>{@link #currentTickingWorldRegionizedData} —— 当前 region 的世界数据</li>
 * </ul>
 *
 * <p>这些字段替代了 Folia 的 {@code TickThreadRunner} 中的同名字段，
 * 使 {@code TickRegionScheduler.getCurrentRegion()} 等静态方法能够正确返回 region 信息。
 *
 * <h3>线程安全</h3>
 * <p>每个 MiliTickThread 实例只在其自己的线程上运行，字段访问无需同步。
 */
public class MiliTickThread extends TickThread {

    /**
     * 当前正在 tick 的 region。
     * <p>在 region tick 开始时设置，tick 结束时清除。</p>
     */
    @Nullable
    public ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> currentTickingRegion;

    /**
     * 当前正在 tick 的 region 的世界数据。
     * <p>在 region tick 开始时设置，tick 结束时清除。</p>
     */
    @Nullable
    public RegionizedWorldData currentTickingWorldRegionizedData;

    /**
     * 创建 MiliTickThread。
     *
     * @param run  要执行的任务
     * @param name 线程名称
     */
    public MiliTickThread(final Runnable run, final String name) {
        super(null, run, name);
        this.setDaemon(true);
    }

    /**
     * 设置当前线程的 region 上下文。
     *
     * @param region     当前 region（null 表示清除）
     * @param worldData  当前 region 的世界数据（null 表示清除）
     */
    public void setTickingRegion(
            @Nullable final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region,
            @Nullable final RegionizedWorldData worldData) {
        this.currentTickingRegion = region;
        this.currentTickingWorldRegionizedData = worldData;
    }

    /**
     * 清除当前线程的 region 上下文。
     */
    public void clearTickingRegion() {
        this.currentTickingRegion = null;
        this.currentTickingWorldRegionizedData = null;
    }

    /**
     * 获取当前线程（如果是 MiliTickThread）。
     *
     * @return 当前线程的 MiliTickThread 实例，或 null
     */
    @Nullable
    public static MiliTickThread currentOrNull() {
        final Thread thread = Thread.currentThread();
        if (thread instanceof MiliTickThread miliThread) {
            return miliThread;
        }
        return null;
    }
}
