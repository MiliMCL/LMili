package fun.bm.mili.lmili.thread.scheduler.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 实体调度接口 —— 针对单个实体的任务调度。
 *
 * <p>确保所有针对同一实体的任务按顺序执行，避免并发修改。
 * 当实体被移除或移动到其他 region 时，调度器会自动处理任务取消。
 *
 * <h3>线程安全</h3>
 * <p>本接口的所有实现必须是线程安全的。多个线程可以同时提交任务到同一实体。
 *
 * <h3>C-10 修复</h3>
 * <p>修复前：使用 entityId 作为 regionId —— 错误，entityId 是全局唯一序号，
 * 与实体空间位置无关。多个不同实体可能映射到同一 queue（ID 复用时）或同一实体
 * 移动后仍使用旧 queue。
 *
 * <p>修复后：使用 entity → position → chunk coordinates → regionId 映射。
 * RegionId 在 EntityRef 创建时从实体位置计算，后续通过 {@link EntityRef#regionId()} 获取。
 */
public interface EntityScheduler {

    /**
     * 提交一个针对此实体的任务。
     *
     * <p>任务会在实体所属的 region 队列中排队执行。
     * 如果实体已被移除，任务会被取消并抛出 {@link EntityOrphanedException}。
     *
     * @param task 要执行的任务
     * @return 任务句柄
     * @throws EntityOrphanedException 如果实体已被移除
     */
    @NotNull TaskHandle submit(@NotNull EntityTask task);

    /**
     * 提交一个延迟执行的任务。
     *
     * @param task 要执行的任务
     * @param delayTicks 延迟的 tick 数
     * @return 任务句柄
     */
    @NotNull TaskHandle submitDelayed(@NotNull EntityTask task, long delayTicks);

    /**
     * 检查当前线程是否拥有此实体的执行权。
     *
     * <p>如果当前线程是此实体所在 region 的 tick 线程，返回 true。
     * 在拥有执行权的线程上可以直接访问实体，无需同步。
     */
    boolean isOnOwningThread();

    /**
     * 确保当前线程拥有此实体的执行权。
     *
     * @throws EntityOrphanedException 如果实体已被移除
     * @throws IllegalStateException 如果当前线程不拥有执行权
     */
    void ensureOnOwningThread() throws EntityOrphanedException;

    /**
     * 检查实体是否仍然存活。
     */
    boolean isEntityAlive();

    /**
     * 获取实体引用。
     */
    @NotNull EntityRef entityRef();

    /**
     * 实体引用 —— 轻量级实体标识，避免直接持有 Entity 对象引用。
     *
     * <p>使用 entityId + level + regionId 标识实体。
     * regionId 在创建时从实体位置计算，允许 GC 回收实体对象
     * （虽然 Minecraft 的实体管理通常保持强引用）。
     *
     * <h3>C-10 修复</h3>
     * <p>增加 {@link #regionId()} 方法，使用 entity → position → chunk → region 映射，
     * 替代直接使用 entityId 作为 regionId。
     */
    interface EntityRef {
        int entityId();
        @Nullable Object level(); // ServerLevel，使用 Object 避免编译期依赖

        /**
         * 获取实体所在的 Region ID。
         *
         * <p>修复 C-10：regionId 从实体位置计算（chunkX, chunkZ），不是直接使用 entityId。
         * 这样同一 region 的所有实体共享同一队列，保证顺序执行。
         *
         * <p>如果实体位置未知或不可访问，返回 entityId 作为 fallback（但这不应发生）。
         *
         * @return regionId（基于 chunk 坐标编码）
         */
        long regionId();

        /**
         * 创建 EntityRef（包含从位置计算的 regionId）。
         *
         * @param entityId 实体 ID
         * @param level    ServerLevel
         * @param regionId 从实体位置计算的 regionId
         * @return EntityRef 实例
         */
        static @NotNull EntityRef of(int entityId, @Nullable Object level, long regionId) {
            return new EntityRef() {
                @Override
                public int entityId() { return entityId; }

                @Override
                public @Nullable Object level() { return level; }

                @Override
                public long regionId() { return regionId; }
            };
        }

        /**
         * 创建 EntityRef（使用 entityId 作为 regionId 的兼容方法）。
         *
         * <p><b>注意</b>：此方法仅用于向后兼容。新代码应使用 {@link #of(int, Object, long)}。
         *
         * @param entityId 实体 ID
         * @param level    ServerLevel
         * @return EntityRef 实例（regionId = entityId）
         */
        static @NotNull EntityRef of(int entityId, @Nullable Object level) {
            return of(entityId, level, entityId); // Fallback: 使用 entityId（不正确但兼容）
        }
    }
}
