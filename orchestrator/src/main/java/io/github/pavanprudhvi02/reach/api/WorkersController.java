package io.github.pavanprudhvi02.reach.api;

import io.github.pavanprudhvi02.reach.worker.Worker;
import io.github.pavanprudhvi02.reach.worker.WorkerRegistry;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Operator view of the pool: health, circuit state, load and latency per worker. */
@RestController
public class WorkersController {

    private final WorkerRegistry registry;

    public WorkersController(WorkerRegistry registry) {
        this.registry = registry;
    }

    public record WorkerView(
            String id,
            String url,
            boolean healthy,
            String circuit,
            int inFlight,
            long served,
            long failed,
            Double latencyMs,
            String lastError,
            Instant lastHealthCheck) {

        static WorkerView of(Worker w) {
            double latency = w.latencyMillis();
            return new WorkerView(w.id(), w.baseUrl().toString(), w.healthy(), w.breaker().getState().name(),
                    w.inFlight(), w.served(), w.failed(),
                    latency < 0 ? null : Math.round(latency * 10) / 10.0,
                    w.lastError(), w.lastHealthCheck());
        }
    }

    @GetMapping("/v1/workers")
    public List<WorkerView> workers() {
        return registry.all().stream().map(WorkerView::of).toList();
    }

    /** Closes a worker's circuit by hand, e.g. after fixing it, without waiting for the half-open probe. */
    @PostMapping("/v1/workers/{id}/reset")
    public WorkerView reset(@PathVariable String id) {
        Worker w = registry.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown worker " + id));
        w.reset();
        return WorkerView.of(w);
    }
}
