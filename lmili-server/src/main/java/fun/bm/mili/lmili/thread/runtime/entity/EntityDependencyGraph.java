package fun.bm.mili.lmili.thread.runtime.entity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entity 依赖图 —— 表达 Entity 之间的依赖关系。
 *
 * <p>如果：
 * <pre>
 * Entity A
 *    │
 *    ▼
 * Collision
 *    │
 *    ▼
 * Entity B
 * </pre>
 *
 * <p>不能简单：{@code A || B}
 *
 * <p>必须表达：{@code A → Collision → B}
 *
 * <h3>使用场景</h3>
 * <ul>
 *   <li>碰撞检测依赖：A 碰撞 B 时，A 必须在 B 之前完成 Movement</li>
 *   <li>骑乘关系：乘客依赖坐骑的移动</li>
 *   <li>攻击关系：攻击者的伤害计算依赖被攻击者的位置</li>
 * </ul>
 */
public final class EntityDependencyGraph {

    /** Entity 依赖关系：entityId -> 依赖此 Entity 的其他 Entity */
    private final ConcurrentHashMap<Long, Set<Long>> dependencies = new ConcurrentHashMap<>();

    /** Entity 被依赖关系：entityId -> 此 Entity 依赖的其他 Entity */
    private final ConcurrentHashMap<Long, Set<Long>> dependents = new ConcurrentHashMap<>();

    /**
     * 添加依赖关系。
     *
     * @param dependent 依赖者
     * @param dependency 被依赖者
     */
    public void addDependency(long dependent, long dependency) {
        dependencies.computeIfAbsent(dependency, k -> ConcurrentHashMap.newKeySet()).add(dependent);
        dependents.computeIfAbsent(dependent, k -> ConcurrentHashMap.newKeySet()).add(dependency);
    }

    /**
     * 移除依赖关系。
     */
    public void removeDependency(long dependent, long dependency) {
        Set<Long> deps = dependencies.get(dependency);
        if (deps != null) {
            deps.remove(dependent);
        }
        Set<Long> depts = dependents.get(dependent);
        if (depts != null) {
            depts.remove(dependency);
        }
    }

    /**
     * 获取依赖于指定 Entity 的所有 Entity。
     */
    public Set<Long> getDependents(long entityId) {
        Set<Long> set = dependencies.get(entityId);
        return set != null ? Collections.unmodifiableSet(set) : Collections.emptySet();
    }

    /**
     * 获取指定 Entity 依赖的所有 Entity。
     */
    public Set<Long> getDependencies(long entityId) {
        Set<Long> set = dependents.get(entityId);
        return set != null ? Collections.unmodifiableSet(set) : Collections.emptySet();
    }

    /**
     * 移除 Entity 的所有依赖关系。
     */
    public void removeEntity(long entityId) {
        // 移除此 Entity 作为依赖者的关系
        Set<Long> deps = dependents.remove(entityId);
        if (deps != null) {
            for (Long dep : deps) {
                Set<Long> set = dependencies.get(dep);
                if (set != null) {
                    set.remove(entityId);
                }
            }
        }

        // 移除此 Entity 作为被依赖者的关系
        Set<Long> depts = dependencies.remove(entityId);
        if (depts != null) {
            for (Long dept : depts) {
                Set<Long> set = dependents.get(dept);
                if (set != null) {
                    set.remove(entityId);
                }
            }
        }
    }

    /**
     * 拓扑排序 —— 返回 ExecutionOrder。
     *
     * <p>保证：如果 A 依赖 B，则 B 在 A 之前执行。
     */
    public List<Long> topologicalSort(Collection<Long> entityIds) {
        // Kahn's algorithm
        Map<Long, Integer> inDegree = new HashMap<>();
        Map<Long, Set<Long>> adj = new HashMap<>();

        // 初始化
        for (Long id : entityIds) {
            inDegree.put(id, 0);
            adj.put(id, new HashSet<>());
        }

        // 构建子图
        for (Long id : entityIds) {
            Set<Long> deps = getDependencies(id);
            for (Long dep : deps) {
                if (inDegree.containsKey(dep)) {
                    adj.get(dep).add(id);
                    inDegree.put(id, inDegree.get(id) + 1);
                }
            }
        }

        // BFS
        Queue<Long> queue = new LinkedList<>();
        for (Map.Entry<Long, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        List<Long> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            result.add(current);

            for (Long neighbor : adj.get(current)) {
                int newDegree = inDegree.get(neighbor) - 1;
                inDegree.put(neighbor, newDegree);
                if (newDegree == 0) {
                    queue.offer(neighbor);
                }
            }
        }

        return result;
    }

    /**
     * 清除所有依赖关系。
     */
    public void clear() {
        dependencies.clear();
        dependents.clear();
    }

    /**
     * 获取 Entity 数量。
     */
    public int size() {
        return dependents.size();
    }
}
