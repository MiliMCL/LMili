package fun.bm.mili.lmili.runtime.control;

import com.mojang.logging.LogUtils;
import fun.bm.mili.lmili.runtime.policy.PolicyCommand;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Chunk 系统的控制入口（ARCHITECTURE_AdaptiveRuntime.md §3.6）。
 *
 * <p>接入现有：ChunkTickDispatcher、PlayerChunkPreloader、CrossRegionChunkPreloader、
 * AsyncChunkAccessor、SaveAllUtil。禁止 Chunk 自己开线程 —— 一切异步经 Scheduler/IO 通道。
 *
 * <p><strong>紧急保存也不直接写 IOController</strong>（D-23）：任何"改变行为"的调用必须出自
 * PolicyController 编排 —— 本控制器经受控通道提交 SET_FLUSH_PRIORITY 命令。
 */
public final class ChunkController {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final AtomicBoolean preloadEnabled = new AtomicBoolean(true);
    private final AtomicLong chunkSaveBudgetNanos = new AtomicLong(-1L);
    private final AtomicBoolean lowPrioritySavesPaused = new AtomicBoolean(false);

    /** 受控通道（MiliRuntime 装配期接线；null = 未装配，requestEmergencySave 记日志降级） */
    private volatile BiConsumer<PolicyCommand, String> policyChannel = null;

    public void setPreloadEnabled(boolean on) {
        preloadEnabled.set(on);
    }

    public boolean isPreloadEnabled() {
        return preloadEnabled.get();
    }

    /** chunk save 预算（纳秒；-1 = 自动换算） */
    public void setChunkSaveBudgetNanos(long nanos) {
        chunkSaveBudgetNanos.set(nanos < 0 ? -1 : Math.min(nanos, 100_000_000L));
    }

    public long chunkSaveBudgetNanos() {
        return chunkSaveBudgetNanos.get();
    }

    /** IO_PRESSURE 时调用：低优先级 chunk save 延后（backoff）。实现：提交 save 任务入口检查 IOController.isSaturated()（只读、非阻塞） */
    public void pauseLowPrioritySaves() {
        lowPrioritySavesPaused.set(true);
        LOGGER.info("[ChunkController] Low-priority chunk saves paused (IO_PRESSURE)");
    }

    public void resumeLowPrioritySaves() {
        lowPrioritySavesPaused.set(false);
        LOGGER.info("[ChunkController] Low-priority chunk saves resumed");
    }

    public boolean areLowPrioritySavesPaused() {
        return lowPrioritySavesPaused.get();
    }

    /** 紧急保存（/lmili control save-all 语义；提升为 CRITICAL 经受控通道） */
    public void requestEmergencySave(long regionId) {
        final BiConsumer<PolicyCommand, String> channel = policyChannel;
        if (channel == null) {
            LOGGER.warn("[ChunkController] requestEmergencySave({}) ignored: policy channel not wired", regionId);
            return;
        }
        channel.accept(PolicyCommand.fromModule("ChunkController", fun.bm.mili.lmili.runtime.policy.CommandAction.SET_FLUSH_PRIORITY,
                Map.of("regionId", String.valueOf(regionId), "priority", "CRITICAL")), "emergency-save");
    }

    /** 受控通道接线（仅 MiliRuntime 装配期调用） */
    public void wirePolicyChannel(BiConsumer<PolicyCommand, String> channel) {
        this.policyChannel = channel;
    }
}
