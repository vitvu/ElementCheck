package com.vvtool.elementcheck;

/** Change notifications that drive the screen stream: nothing changes, nothing is sent. */
final class Signals {
    private static final Object lock = new Object();
    private static long version;
    private static long burstUntil;

    private Signals() { }

    static void changed() {
        synchronized (lock) {
            version++;
            lock.notifyAll();
        }
    }

    /** Keeps frames flowing for a while after user input, to catch animations. */
    static void burst(long ms) {
        synchronized (lock) {
            burstUntil = Math.max(burstUntil, System.currentTimeMillis() + ms);
            version++;
            lock.notifyAll();
        }
    }

    static void wake() {
        synchronized (lock) { lock.notifyAll(); }
    }

    /** Blocks until something changed after {@code seen} (or a burst is active). Returns the new version, or -1 on timeout. */
    static long await(long seen, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (version == seen && System.currentTimeMillis() >= burstUntil) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) return -1;
                lock.wait(left);
            }
            return version;
        }
    }
}
