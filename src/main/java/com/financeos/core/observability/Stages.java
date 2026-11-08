package com.financeos.core.observability;

/**
 * Times consecutive steps of one long method whose locals span all the steps, so the steps cannot be wrapped in
 * lambdas. {@link #next} ends the previous step and starts the next; {@link #close} ends the last one. Use it in a
 * try-with-resources and call {@link #error} from the catch so the open step gets the outcome.
 */
public final class Stages implements AutoCloseable {

    private Timing.Span current;

    public Stages next(String op) {
        end();
        current = Timing.start(op);
        return this;
    }

    /** Ends the open step, if any. */
    public void end() {
        if (current != null) {
            current.close();
            current = null;
        }
    }

    public void error(Throwable t) {
        if (current != null) {
            current.error(t);
        }
    }

    @Override
    public void close() {
        end();
    }
}
