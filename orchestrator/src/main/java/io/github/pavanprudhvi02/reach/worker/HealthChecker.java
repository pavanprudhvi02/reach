package io.github.pavanprudhvi02.reach.worker;

import io.github.pavanprudhvi02.reach.config.ReachProperties;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Polls every worker's {@code /health} endpoint on a fixed delay. llama.cpp
 * answers 200 once the model is loaded and 503 while it is still loading, so
 * a worker only receives traffic after its model is ready.
 */
@Component
public class HealthChecker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(HealthChecker.class);

    private final WorkerRegistry registry;
    private final ReachProperties props;
    private final HttpClient http;
    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    public HealthChecker(WorkerRegistry registry, ReachProperties props) {
        this.registry = registry;
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(props.healthTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reach-health");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::checkAll, 0, props.healthInterval().toMillis(), TimeUnit.MILLISECONDS);
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Checks all workers in parallel and waits for the results. Public so tests can force a round. */
    public void checkAll() {
        try {
            CompletableFuture<?>[] checks = registry.all().stream().map(this::check).toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(checks).join();
        } catch (RuntimeException e) {
            log.warn("health round failed", e);
        }
    }

    private CompletableFuture<Void> check(Worker worker) {
        HttpRequest request = HttpRequest.newBuilder(worker.endpoint("/health"))
                .timeout(props.healthTimeout())
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .handle((response, error) -> {
                    boolean wasHealthy = worker.healthy();
                    if (error != null) {
                        worker.markHealth(false, "health check: " + rootMessage(error));
                    } else if (response.statusCode() == 200) {
                        worker.markHealth(true, null);
                    } else {
                        worker.markHealth(false, "health check: HTTP " + response.statusCode());
                    }
                    if (wasHealthy != worker.healthy()) {
                        log.info("worker {} is now {}", worker.id(), worker.healthy() ? "healthy" : "unhealthy (" + worker.lastError() + ")");
                    }
                    return null;
                });
    }

    static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return cur.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg);
    }
}
