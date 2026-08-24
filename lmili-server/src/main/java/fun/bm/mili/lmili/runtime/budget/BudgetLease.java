package fun.bm.mili.lmili.runtime.budget;

/**
 * 预算租约 —— RAII：try-with-resources 自动归还，防泄漏（ARCHITECTURE_AdaptiveRuntime.md §3.12）。
 */
public final class BudgetLease implements AutoCloseable {

    private final RegionTickBudget budget;
    private final long nanos;
    private boolean closed;

    /** 构造即扣减（预算已在 tryAcquire 时扣减，本类只负责登记）；close() 归还（幂等） */
    public BudgetLease(RegionTickBudget budget, long nanos) {
        this.budget = budget;
        this.nanos = nanos;
    }

    @Override
    public void close() {
        if (closed) {
            return; // 幂等
        }
        closed = true;
        budget.release(nanos);
    }

    public long grantedNanos() {
        return nanos;
    }

    public boolean isClosed() {
        return closed;
    }
}
