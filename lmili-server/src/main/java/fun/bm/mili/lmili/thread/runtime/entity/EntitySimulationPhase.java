package fun.bm.mili.lmili.thread.runtime.entity;

/**
 * Entity 模拟阶段 —— 定义 Entity Tick 拆分的各个阶段。
 *
 * <p>Entity Tick 必须拆成 Phase：
 * <pre>
 * Entity Simulation
 *         │
 *         ├── AI
 *         ├── Movement
 *         ├── Sensors
 *         ├── Collision
 *         ├── Brain
 *         │
 *         ▼
 * Commit Phase
 *         │
 *         ▼
 * World State
 * </pre>
 *
 * <h3>依赖关系</h3>
 * <ul>
 *   <li>AI → Movement（AI 决定移动方向）</li>
 *   <li>Sensors → AI（传感器输入影响 AI）</li>
 *   <li>Movement → Collision（移动后检测碰撞）</li>
 *   <li>Brain → Movement（大脑决策影响移动）</li>
 *   <li>All → Commit（所有阶段完成后提交）</li>
 * </ul>
 */
public enum EntitySimulationPhase {

    /**
     * AI 阶段 —— 实体 AI 决策。
     *
     * <p>读取：ENTITY_AI_STATE, ENTITY_POSITION
     * <p>写入：ENTITY_AI_STATE, ENTITY_TARGET
     */
    AI(0, "AI"),

    /**
     * Movement 阶段 —— 实体移动。
     *
     * <p>读取：ENTITY_POSITION, ENTITY_MOTION, ENTITY_AI_STATE
     * <p>写入：ENTITY_POSITION, ENTITY_MOTION
     */
    MOVEMENT(1, "Movement"),

    /**
     * Sensors 阶段 —— 实体传感器。
     *
     * <p>读取：ENTITY_POSITION, WORLD_STATE
     * <p>写入：ENTITY_SENSOR_DATA
     */
    SENSORS(2, "Sensors"),

    /**
     * Collision 阶段 —— 碰撞检测。
     *
     * <p>读取：ENTITY_POSITION, ENTITY_MOTION, ENTITY_BOUNDING_BOX
     * <p>写入：ENTITY_MOTION, ENTITY_POSITION
     */
    COLLISION(3, "Collision"),

    /**
     * Brain 阶段 —— 大脑决策（复杂 AI）。
     *
     * <p>读取：ENTITY_AI_STATE, ENTITY_SENSOR_DATA, ENTITY_MEMORY
     * <p>写入：ENTITY_AI_STATE, ENTITY_BEHAVIOR
     */
    BRAIN(4, "Brain"),

    /**
     * Commit 阶段 —— 提交状态到世界。
     *
     * <p>读取：ENTITY_POSITION, ENTITY_MOTION, ENTITY_STATE
     * <p>写入：WORLD_STATE
     */
    COMMIT(5, "Commit");

    private final int index;
    private final String displayName;

    EntitySimulationPhase(int index, String displayName) {
        this.index = index;
        this.displayName = displayName;
    }

    public int index() {
        return index;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * 获取执行顺序（按 index 排序）。
     */
    public static EntitySimulationPhase[] executionOrder() {
        return values();
    }

    /**
     * 获取可并行执行的阶段组。
     *
     * <p>同一组内的阶段可以并行执行。
     */
    public static EntitySimulationPhase[][] parallelGroups() {
        return new EntitySimulationPhase[][] {
            { SENSORS, BRAIN },  // 传感器和大脑可以并行
            { AI },              // AI 依赖传感器输入
            { MOVEMENT },        // 移动依赖 AI 决策
            { COLLISION },       // 碰撞检测依赖移动
            { COMMIT }           // 提交需要所有阶段完成
        };
    }
}
