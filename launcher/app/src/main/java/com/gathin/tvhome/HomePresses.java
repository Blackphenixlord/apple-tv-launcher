package com.gathin.tvhome;

/**
 * Family mode's Home button (HomeKeyService): one press opens this launcher at once, and a second press
 * within {@link #DOUBLE_PRESS_MS} of the first opens Google TV's own home instead. Nothing waits for a
 * possible second press, so a single press is never delayed. Plain Java, so it is unit tested.
 */
final class HomePresses {
    /** Measured between the two key-downs, as Android's own double-tap timeout is. */
    static final long DOUBLE_PRESS_MS = 500;

    private long lastPressAt = Long.MIN_VALUE;

    /**
     * A Home press that went down at downTime (ms, uptime clock). True when it completes a double press;
     * a third quick press then starts a new pair rather than counting as another double.
     */
    boolean isDoublePress(long downTime) {
        boolean dbl = lastPressAt != Long.MIN_VALUE && downTime >= lastPressAt
                && downTime - lastPressAt <= DOUBLE_PRESS_MS;
        lastPressAt = dbl ? Long.MIN_VALUE : downTime;
        return dbl;
    }

    void reset() {
        lastPressAt = Long.MIN_VALUE;
    }
}
