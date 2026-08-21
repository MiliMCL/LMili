package fun.bm.mili.lmili.thread.runtime.ownership;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Region 所有权系统 —— 定义 Minecraft 对象的线程所有权。
 *
 * <p>每个对象必须有明确 Owner。如果 Thread A owns Region 1，Thread B 不得直接修改 Region 1 state。
 * 必须通过 message/queue/deferred operation 进入 Owner 线程。
 *
 * <h3>所有权层级</h3>
 * <pre>
 * Region (owner = Worker thread)
 *   ├── Chunk (owner = Region owner)
 *   │    ├── Blocks (owner = Region owner)
 *   │    ├── BlockEntities (owner = Region owner)
 *   │    ├── Heightmap (owner = Region owner)
 *   │    └── TickLists (owner = Region owner)
 *   └── Entities (owner = Region owner)
 * </pre>
 *
 * <h3>线程安全</h3>
 * <p>所有方法都是线程安全的。所有权转移通过 CAS 保证原子性。
 */
public final class RegionOwnership {

    /** Region 所有者记录 */
    private final ConcurrentHashMap<Long, OwnershipRecord> ownershipMap = new ConcurrentHashMap<>();

    /** 全局 Generation 计数器 */
    private final AtomicInteger generationCounter = new AtomicInteger(0);

    /**
     * Region 所有权记录。
     */
    public static final class OwnershipRecord {
        private final long regionId;
        private final AtomicReference<OwnerInfo> owner;

        OwnershipRecord(long regionId, int workerId, long generation) {
            this.regionId = regionId;
            this.owner = new AtomicReference<>(new OwnerInfo(workerId, generation, System.nanoTime()));
        }

        public long regionId() { return regionId; }
        public OwnerInfo owner() { return owner.get(); }

        /**
         * 尝试转移所有权。
         *
         * @param expected 当前所有者信息
         * @param newOwner 新的所有者信息
         * @return true 如果成功转移
         */
        boolean tryTransfer(OwnerInfo expected, OwnerInfo newOwner) {
            return owner.compareAndSet(expected, newOwner);
        }

        /**
         * 强制转移所有权（用于 unregister 等场景）。
         *
         * @param newOwner 新的所有者信息
         */
        void forceTransfer(OwnerInfo newOwner) {
            owner.set(newOwner);
        }
    }

    /**
     * 所有者信息。
     */
    public record OwnerInfo(int workerId, long generation, long acquiredAtNanos) {
        /**
         * 检查所有者是否仍然有效。
         *
         * @param currentGeneration 当前 Generation
         * @return true 如果所有者仍然有效
         */
        public boolean isValid(long currentGeneration) {
            return generation == currentGeneration && workerId >= 0;
        }

        @Override
        public String toString() {
            return "Owner{worker=" + workerId + ", gen=" + generation + "}";
        }
    }

    /**
     * 注册 Region 所有权。
     *
     * @param regionId Region ID
     * @param workerId Worker ID
     * @return 新创建的所有权记录
     */
    public OwnershipRecord register(long regionId, int workerId) {
        long generation = generationCounter.incrementAndGet();
        OwnershipRecord record = new OwnershipRecord(regionId, workerId, generation);
        ownershipMap.put(regionId, record);
        return record;
    }

    /**
     * 注销 Region 所有权。
     *
     * @param regionId Region ID
     * @return 被移除的所有权记录，如果不存在返回 null
     */
    public OwnershipRecord unregister(long regionId) {
        return ownershipMap.remove(regionId);
    }

    /**
     * 获取 Region 的所有权记录。
     *
     * @param regionId Region ID
     * @return 所有权记录，如果不存在返回 null
     */
    public OwnershipRecord getOwnership(long regionId) {
        return ownershipMap.get(regionId);
    }

    /**
     * 检查指定线程是否有权访问 Region。
     *
     * @param regionId  Region ID
     * @param workerId  Worker ID
     * @return true 如果该 Worker 拥有该 Region
     */
    public boolean hasAccess(long regionId, int workerId) {
        OwnershipRecord record = ownershipMap.get(regionId);
        if (record == null) return false;
        OwnerInfo owner = record.owner();
        return owner.workerId() == workerId;
    }

    /**
     * 检查指定所有者是否仍然有效。
     *
     * @param regionId           Region ID
     * @param expectedOwner      预期的所有者信息
     * @return true 如果所有者仍然有效
     */
    public boolean isOwnerValid(long regionId, OwnerInfo expectedOwner) {
        OwnershipRecord record = ownershipMap.get(regionId);
        if (record == null) return false;
        return record.owner().equals(expectedOwner);
    }

    /**
     * 尝试转移 Region 所有权。
     *
     * @param regionId  Region ID
     * @param expected  当前所有者（验证）
     * @param newWorker 新的 Worker ID
     * @return true 如果成功转移
     */
    public boolean tryTransferOwnership(long regionId, OwnerInfo expected, int newWorker) {
        OwnershipRecord record = ownershipMap.get(regionId);
        if (record == null) return false;

        long newGeneration = generationCounter.incrementAndGet();
        OwnerInfo newOwner = new OwnerInfo(newWorker, newGeneration, System.nanoTime());
        return record.tryTransfer(expected, newOwner);
    }

    /**
     * 强制转移 Region 所有权（用于紧急情况）。
     *
     * @param regionId  Region ID
     * @param newWorker 新的 Worker ID
     * @return 新的所有者信息
     */
    public OwnerInfo forceTransferOwnership(long regionId, int newWorker) {
        OwnershipRecord record = ownershipMap.get(regionId);
        if (record == null) return null;

        long newGeneration = generationCounter.incrementAndGet();
        OwnerInfo newOwner = new OwnerInfo(newWorker, newGeneration, System.nanoTime());
        record.forceTransfer(newOwner);
        return newOwner;
    }

    /**
     * 获取所有已注册 Region 的数量。
     */
    public int registeredCount() {
        return ownershipMap.size();
    }

    /**
     * 获取所有 Region 的所有权快照。
     */
    public java.util.Map<Long, OwnerInfo> getAllOwnerships() {
        java.util.Map<Long, OwnerInfo> result = new java.util.HashMap<>();
        for (var entry : ownershipMap.entrySet()) {
            result.put(entry.getKey(), entry.getValue().owner());
        }
        return result;
    }

    /**
     * 获取指定 Worker 拥有的所有 Region。
     */
    public java.util.List<Long> getRegionsByOwner(int workerId) {
        java.util.List<Long> result = new java.util.ArrayList<>();
        for (var entry : ownershipMap.entrySet()) {
            if (entry.getValue().owner().workerId() == workerId) {
                result.add(entry.getKey());
            }
        }
        return result;
    }
}
