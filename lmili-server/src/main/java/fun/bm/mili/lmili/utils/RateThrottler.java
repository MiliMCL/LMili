package fun.bm.mili.lmili.utils;

import org.jetbrains.annotations.NotNull;

// Mili start - fix: add volatile for thread safety and fix division by zero
public class RateThrottler {
    private static final byte STATE_NOT_BEGIN = 0;
    private static final byte STATE_RECORDING = 1;
    private static final byte STATE_DESTROYED = 2;

    private volatile byte status;
    private volatile int recordedThisTick = 0;

    private volatile int totallyTicked = 0;
    private volatile int totalCount = 0;
    // Mili end

    private void checkDestroyed() {
        if (this.status == STATE_DESTROYED) {
            throw new IllegalStateException("Already destroyed!");
        }
    }

    public void increase() {
        this.recordedThisTick++;
    }

    public void mergeWith(@NotNull RateThrottler other) {
        this.checkDestroyed();
        other.checkDestroyed();

        this.totallyTicked += other.totallyTicked;
        this.totalCount += other.totalCount;
    }

    public void splitInto(@NotNull RateThrottler other) {
        this.checkDestroyed();
        other.checkDestroyed();

        other.totalCount = this.totalCount;
        other.totallyTicked = this.totallyTicked;
    }

    public double getAvgCount() {
        // Mili start - fix: use Math.max instead of Math.min to prevent division by zero
        return (double) this.totalCount / Math.max(this.totallyTicked, 1);
        // Mili end
    }

    public int getCountThisTick() {
        return this.recordedThisTick;
    }

    public boolean isOutOfRate(int expected) {
        return this.recordedThisTick >= expected;
    }

    public void destroy() {
        if (this.status == STATE_DESTROYED) {
            throw new IllegalStateException("Already destroyed!");
        }

        this.status = STATE_DESTROYED;
    }

    public void begin() {
        this.checkDestroyed();

        if (this.status == STATE_RECORDING) {
            throw new IllegalStateException("Attempt to begin a already recording throttler!");
        }

        this.status = STATE_RECORDING;
    }

    public void done() {
        this.checkDestroyed();

        // Mili start - fix: check for STATE_RECORDING instead of STATE_NOT_BEGIN
        if (this.status != STATE_RECORDING) {
            throw new IllegalStateException("Attempt to done a throttler that is not recording!");
        }
        // Mili end

        this.status = STATE_NOT_BEGIN;

        this.totallyTicked++;
        this.totalCount += this.recordedThisTick;

        this.recordedThisTick = 0;
    }
}
