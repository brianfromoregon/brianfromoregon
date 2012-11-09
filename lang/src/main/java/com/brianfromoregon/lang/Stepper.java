package com.brianfromoregon.lang;

/**
 * Thread-safe utility for controlling the order of operations across threads.
 * <p/>
 * {@code Stepper} guarantees that operations performed in Thread1 before Thread1 calls {@code takeStep(n)}
 * <a href="http://docs.oracle.com/javase/7/docs/api/java/util/concurrent/package-summary.html#MemoryVisibility">
 * happen-before</a>:
 * <ul>
 * <li>operations performed in Thread2 after Thread2 calls {@code awaitStep(n)}</li>
 * <li>operations performed in Thread2 after Thread2 calls {@code takeStep(n+1)}</li>
 * </ul>
 */
public class Stepper {
    private int next = 0;

    /**
     * Take one step (if possible) and return.
     * <p/>
     * Let step {@code c} be either the previously taken step or {@code -1} if no steps have yet been taken. Let step
     * {@code n} be the first requested step such that {@code n > c}. If such an {@code n} exists then this method will
     * block until {@code c == n-1} at which point it will take step {@code n} and return. Else, all requested steps
     * have already been taken so this method will return immediately.
     *
     * @param oneOf The steps which can be taken, must be in increasing order.
     */
    public synchronized void takeStep(int... oneOf) {
        for (int step : oneOf) {
            if (step >= next) {
                awaitStep(step - 1);
                next++;
                notifyAll();
                return;
            }
        }
    }

    /**
     * Block until the requested step has been taken.
     *
     * @param step Step to wait for.
     */
    public synchronized void awaitStep(int step) {
        while (step >= next) {
            try {
                wait();
            } catch (InterruptedException ignored) {
            }
        }
    }
}
