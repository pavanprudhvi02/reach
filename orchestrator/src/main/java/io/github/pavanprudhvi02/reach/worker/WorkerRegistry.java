package io.github.pavanprudhvi02.reach.worker;

import io.github.pavanprudhvi02.reach.config.ReachProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Builds the worker pool from configuration and owns one circuit breaker per worker. */
@Component
public class WorkerRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkerRegistry.class);

    private final Map<String, Worker> workers = new LinkedHashMap<>();

    public WorkerRegistry(ReachProperties props, MeterRegistry meters) {
        ReachProperties.Breaker b = props.breaker();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(b.slidingWindowSize())
                .minimumNumberOfCalls(b.minimumCalls())
                .failureRateThreshold(b.failureRateThreshold())
                .waitDurationInOpenState(b.openDuration())
                .permittedNumberOfCallsInHalfOpenState(b.halfOpenCalls())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(config);

        for (ReachProperties.WorkerSpec spec : props.workers()) {
            if (workers.containsKey(spec.id())) {
                throw new IllegalArgumentException("duplicate worker id " + spec.id());
            }
            CircuitBreaker breaker = breakers.circuitBreaker(spec.id());
            breaker.getEventPublisher().onStateTransition(e ->
                    log.warn("circuit for worker {} moved {}", spec.id(), e.getStateTransition()));
            Worker worker = new Worker(spec.id(), spec.url(), breaker);
            workers.put(spec.id(), worker);

            Gauge.builder("reach.worker.in_flight", worker, Worker::inFlight)
                    .tag("worker", spec.id()).register(meters);
            Gauge.builder("reach.worker.healthy", worker, w -> w.healthy() ? 1 : 0)
                    .tag("worker", spec.id()).register(meters);
            Gauge.builder("reach.worker.circuit_open", worker,
                            w -> w.breaker().getState() == CircuitBreaker.State.OPEN ? 1 : 0)
                    .tag("worker", spec.id()).register(meters);
        }
        log.info("worker pool: {}", workers.keySet());
    }

    public List<Worker> all() {
        return List.copyOf(workers.values());
    }

    public Optional<Worker> find(String id) {
        return Optional.ofNullable(workers.get(id));
    }
}
