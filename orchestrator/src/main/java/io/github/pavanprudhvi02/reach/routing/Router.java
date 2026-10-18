package io.github.pavanprudhvi02.reach.routing;

import io.github.pavanprudhvi02.reach.config.ReachProperties;
import io.github.pavanprudhvi02.reach.worker.Worker;
import io.github.pavanprudhvi02.reach.worker.WorkerRegistry;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Chooses a worker for the next attempt: least requests in flight first, then
 * lowest smoothed latency. Only healthy workers whose circuit is not open are
 * considered, and each worker serves at most {@code maxInFlightPerWorker}
 * requests at once, since a CPU-bound llama.cpp server slows down for
 * everyone when it is oversubscribed.
 */
@Component
public class Router {

    /** Least loaded first; among equally loaded, fastest first; unknown latency counts as fast. */
    static final Comparator<Worker> PREFERENCE = Comparator
            .comparingInt(Worker::inFlight)
            .thenComparingDouble(w -> Math.max(0, w.latencyMillis()))
            .thenComparing(Worker::id);

    private final WorkerRegistry registry;
    private final int maxInFlight;

    public Router(WorkerRegistry registry, ReachProperties props) {
        this.registry = registry;
        this.maxInFlight = props.maxInFlightPerWorker();
    }

    /** Why no lease could be granted. */
    public enum Unavailable { NO_HEALTHY_WORKER, ALL_BUSY }

    public sealed interface Decision permits Granted, Refused {}

    public record Granted(Lease lease) implements Decision {}

    public record Refused(Unavailable reason) implements Decision {}

    /**
     * Picks and reserves a worker, skipping any in {@code exclude} (workers
     * already tried for this request). The caller must close the lease.
     */
    public Decision acquire(Set<String> exclude) {
        List<Worker> candidates = registry.all().stream()
                .filter(w -> !exclude.contains(w.id()))
                .filter(Worker::routable)
                .sorted(PREFERENCE)
                .toList();
        if (candidates.isEmpty()) {
            return new Refused(Unavailable.NO_HEALTHY_WORKER);
        }
        for (Worker w : candidates) {
            if (!w.tryAcquireSlot(maxInFlight)) {
                continue;
            }
            // In half-open state the breaker admits only a few trial calls.
            if (!w.breaker().tryAcquirePermission()) {
                w.releaseSlot();
                continue;
            }
            return new Granted(new Lease(w));
        }
        return new Refused(Unavailable.ALL_BUSY);
    }
}
