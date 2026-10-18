package io.github.pavanprudhvi02.reach;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pavanprudhvi02.reach.config.ReachProperties;
import io.github.pavanprudhvi02.reach.routing.Lease;
import io.github.pavanprudhvi02.reach.routing.Router;
import io.github.pavanprudhvi02.reach.worker.Worker;
import io.github.pavanprudhvi02.reach.worker.WorkerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RouterTest {

    private WorkerRegistry registry;
    private Router router;

    @BeforeEach
    void setUp() {
        ReachProperties props = new ReachProperties(
                List.of(spec("a"), spec("b"), spec("c")),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(5),
                3, 2,
                new ReachProperties.Breaker(50, 4, 2, Duration.ofSeconds(30), 1));
        registry = new WorkerRegistry(props, new SimpleMeterRegistry());
        registry.all().forEach(w -> w.markHealth(true, null));
        router = new Router(registry, props);
    }

    private static ReachProperties.WorkerSpec spec(String id) {
        return new ReachProperties.WorkerSpec(id, URI.create("http://" + id + ":8080"));
    }

    private Worker worker(String id) {
        return registry.find(id).orElseThrow();
    }

    private Lease grant(Set<String> exclude) {
        Router.Decision d = router.acquire(exclude);
        assertThat(d).isInstanceOf(Router.Granted.class);
        return ((Router.Granted) d).lease();
    }

    @Test
    void spreadsConcurrentRequestsByLoad() {
        Lease first = grant(Set.of());
        Lease second = grant(Set.of());
        Lease third = grant(Set.of());
        assertThat(List.of(first.worker().id(), second.worker().id(), third.worker().id()))
                .containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void prefersTheFasterWorkerWhenLoadIsEqual() {
        worker("a").recordSuccess(Duration.ofMillis(900));
        worker("b").recordSuccess(Duration.ofMillis(100));
        worker("c").recordSuccess(Duration.ofMillis(400));
        assertThat(grant(Set.of()).worker().id()).isEqualTo("b");
    }

    @Test
    void skipsUnhealthyExcludedAndOpenCircuitWorkers() {
        worker("a").markHealth(false, "down");
        worker("b").breaker().transitionToOpenState();
        assertThat(grant(Set.of()).worker().id()).isEqualTo("c");
        assertThat(router.acquire(Set.of("c"))).isEqualTo(new Router.Refused(Router.Unavailable.NO_HEALTHY_WORKER));
    }

    @Test
    void refusesWithAllBusyWhenEveryWorkerIsAtCapacity() {
        for (int i = 0; i < 6; i++) {
            grant(Set.of()); // capacity is 2 per worker
        }
        assertThat(router.acquire(Set.of())).isEqualTo(new Router.Refused(Router.Unavailable.ALL_BUSY));
    }

    @Test
    void closingALeaseFreesTheSlot() {
        for (int i = 0; i < 6; i++) {
            grant(Set.of());
        }
        Worker a = worker("a");
        a.releaseSlot();
        assertThat(grant(Set.of()).worker().id()).isEqualTo("a");
    }

    @Test
    void repeatedFailuresOpenTheCircuit() {
        Worker a = worker("a");
        // minimumCalls = 2 and threshold 50%: two failures in a row open the circuit.
        for (int i = 0; i < 2; i++) {
            try (Lease lease = grant(Set.of("b", "c"))) {
                lease.failed(new RuntimeException("boom"));
            }
        }
        assertThat(a.routable()).isFalse();
        assertThat(a.failed()).isEqualTo(2);
        assertThat(router.acquire(Set.of("b", "c"))).isEqualTo(new Router.Refused(Router.Unavailable.NO_HEALTHY_WORKER));
    }
}
