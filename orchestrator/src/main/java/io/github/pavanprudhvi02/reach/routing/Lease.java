package io.github.pavanprudhvi02.reach.routing;

import io.github.pavanprudhvi02.reach.worker.Worker;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * A reserved slot on one worker for one attempt. Report the outcome with
 * {@link #succeeded} or {@link #failed} so the circuit breaker and latency
 * statistics see it, then {@link #close} to give the slot back.
 */
public final class Lease implements AutoCloseable {

    private final Worker worker;
    private final long startNanos = System.nanoTime();
    private boolean reported;
    private boolean closed;

    Lease(Worker worker) {
        this.worker = worker;
    }

    public Worker worker() {
        return worker;
    }

    public Duration elapsed() {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    public void succeeded() {
        if (reported) {
            return;
        }
        reported = true;
        Duration d = elapsed();
        worker.breaker().onSuccess(d.toNanos(), TimeUnit.NANOSECONDS);
        worker.recordSuccess(d);
    }

    public void failed(Throwable cause) {
        if (reported) {
            return;
        }
        reported = true;
        worker.breaker().onError(elapsed().toNanos(), TimeUnit.NANOSECONDS, cause);
        worker.recordFailure(cause.getMessage());
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (!reported) {
            // Abandoned without an outcome (e.g. the client went away): free the
            // breaker's half-open permit without counting it either way.
            worker.breaker().releasePermission();
        }
        worker.releaseSlot();
    }
}
