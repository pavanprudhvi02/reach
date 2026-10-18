package io.github.pavanprudhvi02.reach.inference;

import io.github.pavanprudhvi02.reach.config.ReachProperties;
import io.github.pavanprudhvi02.reach.routing.Lease;
import io.github.pavanprudhvi02.reach.routing.Router;
import io.github.pavanprudhvi02.reach.worker.Worker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs one client request against the pool with failover: pick the best
 * worker, try it, and on a worker-side failure record it against that
 * worker's circuit breaker and move on to the next best worker, up to
 * {@code maxAttempts} different workers.
 */
@Service
public class InferenceService {

    private static final Logger log = LoggerFactory.getLogger(InferenceService.class);

    private final Router router;
    private final WorkerClient client;
    private final ReachProperties props;
    private final MeterRegistry meters;

    public InferenceService(Router router, WorkerClient client, ReachProperties props, MeterRegistry meters) {
        this.router = router;
        this.client = client;
        this.props = props;
        this.meters = meters;
    }

    public record Completion(Worker worker, int attempts, WorkerClient.Reply reply) {}

    /** A stream whose headers have arrived. The caller copies the body and must close the lease. */
    public record OpenStream(Worker worker, int attempts, Lease lease, HttpResponse<InputStream> response) {}

    public Completion complete(byte[] json) {
        Set<String> tried = new HashSet<>();
        List<String> failures = new ArrayList<>();
        for (int attempt = 1; attempt <= props.maxAttempts(); attempt++) {
            Lease lease = acquireLease(tried, failures);
            Worker worker = lease.worker();
            tried.add(worker.id());
            try {
                WorkerClient.Reply reply = client.complete(worker, json);
                lease.succeeded();
                record(worker, "success", lease);
                return new Completion(worker, attempt, reply);
            } catch (WorkerException e) {
                lease.failed(e);
                record(worker, "failure", lease);
                failures.add(e.getMessage());
                log.warn("attempt {} on {} failed: {}", attempt, worker.id(), e.getMessage());
            } finally {
                lease.close();
            }
        }
        throw new NoWorkerAvailableException(Router.Unavailable.NO_HEALTHY_WORKER, failures);
    }

    public OpenStream openStream(byte[] json) {
        Set<String> tried = new HashSet<>();
        List<String> failures = new ArrayList<>();
        for (int attempt = 1; attempt <= props.maxAttempts(); attempt++) {
            Lease lease = acquireLease(tried, failures);
            Worker worker = lease.worker();
            tried.add(worker.id());
            try {
                HttpResponse<InputStream> response = client.openStream(worker, json);
                return new OpenStream(worker, attempt, lease, response);
            } catch (WorkerException e) {
                lease.failed(e);
                record(worker, "failure", lease);
                lease.close();
                failures.add(e.getMessage());
                log.warn("stream attempt {} on {} failed: {}", attempt, worker.id(), e.getMessage());
            }
        }
        throw new NoWorkerAvailableException(Router.Unavailable.NO_HEALTHY_WORKER, failures);
    }

    /** Records how a stream ended once its body has been copied (or broken). */
    public void finishStream(OpenStream stream, Throwable error) {
        try (Lease lease = stream.lease()) {
            if (error == null) {
                lease.succeeded();
                record(stream.worker(), "success", lease);
            } else {
                lease.failed(error);
                record(stream.worker(), "failure", lease);
            }
        }
    }

    private Lease acquireLease(Set<String> tried, List<String> failures) {
        Router.Decision decision = router.acquire(tried);
        if (decision instanceof Router.Granted granted) {
            return granted.lease();
        }
        Router.Unavailable reason = ((Router.Refused) decision).reason();
        throw new NoWorkerAvailableException(reason, failures);
    }

    private void record(Worker worker, String outcome, Lease lease) {
        Timer.builder("reach.request.latency")
                .tag("worker", worker.id())
                .tag("outcome", outcome)
                .register(meters)
                .record(lease.elapsed());
    }
}
