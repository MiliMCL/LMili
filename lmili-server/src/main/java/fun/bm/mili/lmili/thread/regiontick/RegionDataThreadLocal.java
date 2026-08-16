package fun.bm.mili.lmili.thread.regiontick;

import io.papermc.paper.threadedregions.RegionizedWorldData;
import org.jetbrains.annotations.Nullable;

/**
 * ThreadLocal 回退机制，用于在非 Folia tick 线程（如虚拟线程）中提供当前 region 的 WorldData。
 *
 * <p>Folia 通过 {@code TickThreadRunner.currentTickingWorldRegionizedData} 来跟踪当前 region data，
 * 但虚拟线程不属于该体系。此 ThreadLocal 提供手动设置的回退路径，使 {@code Level.getCurrentWorldData()}
 * 能在虚拟线程中正确返回 region data，避免 NPE。
 *
 * <p>使用模式：
 * <pre>{@code
 * RegionizedWorldData data = level.getCurrentWorldData();  // capture on tick thread
 * RegionDataThreadLocal.setCurrent(data);
 * try {
 *     // ... 在虚拟线程中执行 chunk tick，内部调用 getCurrentWorldData() 会返回 data
 * } finally {
 *     RegionDataThreadLocal.clear();
 * }
 * }</pre>
 */
public final class RegionDataThreadLocal {

    private static final ThreadLocal<RegionizedWorldData> CURRENT = new ThreadLocal<>();

    private RegionDataThreadLocal() {}

    /**
     * 设置当前线程的 region data 回退值。
     */
    public static void setCurrent(@Nullable final RegionizedWorldData data) {
        if (data == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(data);
        }
    }

    /**
     * 获取当前线程的 region data 回退值，若无则返回 null。
     */
    public static @Nullable RegionizedWorldData getCurrent() {
        return CURRENT.get();
    }

    /**
     * 清除当前线程的回退值。应在 finally 块中调用。
     */
    public static void clear() {
        CURRENT.remove();
    }
}
