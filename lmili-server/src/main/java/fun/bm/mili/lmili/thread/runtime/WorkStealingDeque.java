package fun.bm.mili.lmili.thread.runtime;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicStampedReference;

/**
 * Work Stealing Deque —— 支持 LIFO 本地执行和 FIFO 窃取的无锁双端队列。
 *
 * <h3>设计</h3>
 * <ul>
 *   <li>本地 Worker：从顶部 push/pop（LIFO，利于缓存局部性）</li>
 *   <li>其他 Worker：从底部 steal（FIFO，减少竞争）</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <ul>
 *   <li>push/pop：只有 owner Worker 线程调用</li>
 *   <li>steal：其他 Worker 线程调用</li>
 * </ul>
 *
 * <h3>ABA 问题解决</h3>
 * <p>使用 {@link AtomicStampedReference} 防止 ABA 问题。
 */
public final class WorkStealingDeque<T> {

    /** 初始容量 */
    private static final int INITIAL_CAPACITY = 64;

    /** 最大容量 */
    private static final int MAX_CAPACITY = 1 << 20;

    /** 底层数组 */
    private volatile AtomicReferenceArray<T> array;

    /** 底部索引（steal 端），所有线程可见 */
    private final AtomicInteger bottom = new AtomicInteger(0);

    /** 顶部索引（pop 端），使用 AtomicStampedReference 防止 ABA */
    private final AtomicStampedReference<Integer> top = new AtomicStampedReference<>(0, 0);

    /** Owner Worker ID */
    private final int ownerId;

    /** 统计：本地 push 次数 */
    private final AtomicInteger pushCount = new AtomicInteger(0);

    /** 本地 pop 次数 */
    private final AtomicInteger popCount = new AtomicInteger(0);

    /** 被窃取次数 */
    private final AtomicInteger stealCount = new AtomicInteger(0);

    /** 扩容次数 */
    private final AtomicInteger resizeCount = new AtomicInteger(0);

    public WorkStealingDeque(int ownerId) {
        this(ownerId, INITIAL_CAPACITY);
    }

    public WorkStealingDeque(int ownerId, int initialCapacity) {
        this.ownerId = ownerId;
        this.array = new AtomicReferenceArray<>(initialCapacity);
    }

    /**
     * 从顶部添加任务（LIFO，本地操作）。
     *
     * <p>只有 owner Worker 线程可以调用。
     */
    public void push(T item) {
        int b = bottom.get();
        int t = top.getReference();

        AtomicReferenceArray<T> arr = array;

        // 检查是否需要扩容
        if (b - t >= arr.length() - 1) {
            arr = resize(arr, b, t);
        }

        arr.set(b % arr.length(), item);
        bottom.set(b + 1);
        pushCount.incrementAndGet();
    }

    /**
     * 从顶部弹出任务（LIFO，本地操作）。
     *
     * <p>只有 owner Worker 线程可以调用。
     *
     * @return 任务，如果队列为空返回 null
     */
    public T pop() {
        int b = bottom.get() - 1;
        bottom.set(b);

        int[] stampHolder = new int[1];
        int t = top.get(stampHolder);
        int stamp = stampHolder[0];

        if (b < t) {
            // 队列为空，恢复 bottom
            bottom.set(t);
            return null;
        }

        AtomicReferenceArray<T> arr = array;
        T item = arr.get(b % arr.length());

        if (b > t) {
            // 队列中还有多个元素，直接返回
            popCount.incrementAndGet();
            return item;
        }

        // 队列中只有一个元素，需要 CAS 竞争
        if (top.compareAndSet(t, t + 1, stamp, stamp + 1)) {
            bottom.set(t + 1);
            popCount.incrementAndGet();
            return item;
        }

        // CAS 失败，其他线程已经窃取
        bottom.set(t);
        return null;
    }

    /**
     * 从底部窃取任务（FIFO，其他 Worker 操作）。
     *
     * @return 任务，如果窃取失败返回 null
     */
    public T steal() {
        int[] stampHolder = new int[1];
        int t = top.get(stampHolder);
        int stamp = stampHolder[0];

        int b = bottom.get();

        if (t >= b) {
            return null; // 队列为空
        }

        AtomicReferenceArray<T> arr = array;
        T item = arr.get(t % arr.length());

        // CAS 移动 top
        if (top.compareAndSet(t, t + 1, stamp, stamp + 1)) {
            stealCount.incrementAndGet();
            return item;
        }

        // CAS 失败
        return null;
    }

    /**
     * 获取队列大小（近似值）。
     */
    public int size() {
        int b = bottom.get();
        int t = top.getReference();
        return Math.max(0, b - t);
    }

    /**
     * 检查队列是否为空。
     */
    public boolean isEmpty() {
        return bottom.get() <= top.getReference();
    }

    // ---- 统计 ----

    public int getPushCount() { return pushCount.get(); }
    public int getPopCount() { return popCount.get(); }
    public int getStealCount() { return stealCount.get(); }
    public int getResizeCount() { return resizeCount.get(); }
    public int ownerId() { return ownerId; }

    // ---- 内部方法 ----

    /**
     * 扩容底层数组。
     */
    private AtomicReferenceArray<T> resize(AtomicReferenceArray<T> oldArray, int b, int t) {
        int oldCapacity = oldArray.length();
        if (oldCapacity >= MAX_CAPACITY) {
            return oldArray; // 达到最大容量，不再扩容
        }

        int newCapacity = Math.min(oldCapacity * 2, MAX_CAPACITY);
        AtomicReferenceArray<T> newArray = new AtomicReferenceArray<>(newCapacity);

        for (int i = t; i < b; i++) {
            newArray.set(i % newCapacity, oldArray.get(i % oldCapacity));
        }

        array = newArray;
        resizeCount.incrementAndGet();
        return newArray;
    }
}
