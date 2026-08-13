package fun.bm.mili.lmili.thread.regiontick.dag;

public enum AccessMode {
    READ, WRITE, READ_WRITE;

    public boolean conflictsWith(final AccessMode other) {
        return !(this == READ && other == READ);
    }
}
