package io.github.pavanprudhvi02.reach.worker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime view of one llama.cpp worker: its health as seen by the health
 * checker, how many requests it is serving right now, a smoothed latency, and
 * its circuit breaker. All fields are safe to read and update from many threads.
 */
public final class Worker {

    private static final double LATENCY_ALPHA = 0.3;

    private final String id;
    private final URI baseUrl;
    private final CircuitBreaker breaker;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong served = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    // Exponentially weighted moving average of request latency in microseconds; -1 until the first sample.
    private final AtomicLong latencyEwmaMicros = new AtomicLong(-1);
    private volatile boolean healthy;
    private volatile String lastError;
    private volatile Instant lastHealthCheck;

    public Worker(String id, URI baseUrl, CircuitBreaker breaker) {
        this.id = id;
        this.baseUrl = baseUrl;
        this.breaker = breaker;
    }

    public String id() {
        return id;
    }

    public URI baseUrl() {
        return baseUrl;
    }

    public URI endpoint(String path) {
        String base = baseUrl.toString();
        return URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path);
    }

    public CircuitBreaker breaker() {
        return breaker;
    }

    public boolean healthy() {
        return healthy;
    }

    public int inFlight() {
        return inFlight.get();
    }

    public long served() {
        return served.get();
    }

    public long failed() {
        return failed.get();
    }

    public String lastError() {
        return lastError;
    }

    public Instant lastHealthCheck() {
        return lastHealthCheck;
    }

    /** Smoothed latency in milliseconds, or -1 if the worker has not served anything yet. */
    public double latencyMillis() {
        long micros = latencyEwmaMicros.get();
        return micros < 0 ? -1 : micros / 1000.0;
    }

    /** True if the router may send this worker new requests. */
    public boolean routable() {
        return healthy && breaker.getState() != CircuitBreaker.State.OPEN
                && breaker.getState() != CircuitBreaker.State.FORCED_OPEN;
    }

    /** Takes one of the worker's concurrency slots, if one is free. */
    public boolean tryAcquireSlot(int max) {
        while (true) {
            int current = inFlight.get();
            if (current >= max) {
                return false;
            }
            if (inFlight.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void releaseSlot() {
        inFlight.decrementAndGet();
    }

    public void recordSuccess(Duration latency) {
        served.incrementAndGet();
        long sample = latency.toNanos() / 1000;
        latencyEwmaMicros.updateAndGet(prev -> prev < 0 ? sample : (long) (LATENCY_ALPHA * sample + (1 - LATENCY_ALPHA) * prev));
    }

    public void recordFailure(String error) {
        failed.incrementAndGet();
        lastError = error;
    }

    public void markHealth(boolean healthy, String error) {
        this.healthy = healthy;
        this.lastHealthCheck = Instant.now();
        if (!healthy) {
            this.lastError = error;
        }
    }

    /** Closes the breaker and forgets statistics (operator action and tests). */
    public void reset() {
        breaker.reset();
        served.set(0);
        failed.set(0);
        latencyEwmaMicros.set(-1);
        lastError = null;
    }
}
