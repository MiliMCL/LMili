package fun.bm.mili.lmili.thread.regiontick.dag;

import org.jetbrains.annotations.NotNull;
import java.util.concurrent.atomic.AtomicReference;

public final class DagNode {

    private final int id;
    private final SystemProfile profile;
    private final Scope scope;
    private final AtomicReference<NodeState> state = new AtomicReference<>(NodeState.SCHEDULED);

    public DagNode(final int id, final SystemProfile profile, final Scope scope) {
        this.id = id;
        this.profile = profile;
        this.scope = scope;
    }

    public int id() { return this.id; }
    public @NotNull SystemProfile profile() { return this.profile; }
    public @NotNull Scope scope() { return this.scope; }
    public @NotNull NodeState state() { return this.state.get(); }

    public boolean tryBeginExecution() {
        return this.state.compareAndSet(NodeState.SCHEDULED, NodeState.EXECUTING);
    }

    public void complete() { this.state.set(NodeState.COMPLETED); }
    public void fail() { this.state.set(NodeState.FAILED); }
    public int priority() { return this.profile.priority(); }

    @Override
    public String toString() {
        return "DagNode{" + profile.name() + ", scope=" + scope + ", state=" + state.get() + "}";
    }

    public enum NodeState { SCHEDULED, EXECUTING, COMPLETED, FAILED }
}
