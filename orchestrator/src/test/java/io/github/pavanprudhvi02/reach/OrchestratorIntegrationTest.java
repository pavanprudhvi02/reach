package io.github.pavanprudhvi02.reach;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pavanprudhvi02.reach.worker.HealthChecker;
import io.github.pavanprudhvi02.reach.worker.WorkerRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the real orchestrator against three fake llama.cpp workers and breaks
 * them in different ways.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrchestratorIntegrationTest {

    private static final FakeWorker W1;
    private static final FakeWorker W2;
    private static final FakeWorker W3;

    static {
        try {
            W1 = new FakeWorker("w1");
            W2 = new FakeWorker("w2");
            W3 = new FakeWorker("w3");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String CHAT = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    private static final String CHAT_STREAM = "{\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

    @DynamicPropertySource
    static void workers(DynamicPropertyRegistry r) {
        List<FakeWorker> all = List.of(W1, W2, W3);
        for (int i = 0; i < all.size(); i++) {
            int idx = i;
            r.add("reach.workers[" + i + "].id", () -> all.get(idx).name());
            r.add("reach.workers[" + i + "].url", () -> all.get(idx).url());
        }
        r.add("reach.health-interval", () -> "100ms");
        r.add("reach.request-timeout", () -> "1s");
        r.add("reach.breaker.minimum-calls", () -> "3");
        r.add("reach.breaker.sliding-window-size", () -> "4");
        r.add("reach.breaker.open-duration", () -> "60s");
    }

    @LocalServerPort
    int port;

    @Autowired
    WorkerRegistry registry;

    @Autowired
    HealthChecker health;

    @Autowired
    ObjectMapper mapper;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterAll
    static void stopWorkers() {
        W1.close();
        W2.close();
        W3.close();
    }

    @BeforeEach
    void reset() throws Exception {
        for (FakeWorker w : List.of(W1, W2, W3)) {
            w.mode(FakeWorker.Mode.OK);
            w.healthStatus(200);
            w.resetCounters();
        }
        registry.all().forEach(w -> w.reset());
        health.checkAll();
    }

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode workersView() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/workers")).build();
        return mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
    }

    private JsonNode view(String id) throws Exception {
        for (JsonNode n : workersView()) {
            if (n.get("id").asText().equals(id)) {
                return n;
            }
        }
        throw new AssertionError("no worker " + id);
    }

    @Test
    void servesARequestAndSaysWhichWorkerAnswered() throws Exception {
        HttpResponse<String> res = post(CHAT);
        assertThat(res.statusCode()).isEqualTo(200);
        String worker = res.headers().firstValue("X-Reach-Worker").orElseThrow();
        assertThat(res.body()).contains("hello from " + worker);
        assertThat(res.headers().firstValue("X-Reach-Attempts")).hasValue("1");
    }

    @Test
    void spreadsConcurrentRequestsAcrossWorkers() throws Exception {
        for (FakeWorker w : List.of(W1, W2, W3)) {
            w.mode(FakeWorker.Mode.SLOW);
            w.slowMillis(300);
        }
        List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(CHAT))
                    .build();
            calls.add(http.sendAsync(req, HttpResponse.BodyHandlers.ofString()));
        }
        Set<String> servedBy = new HashSet<>();
        for (CompletableFuture<HttpResponse<String>> c : calls) {
            HttpResponse<String> res = c.join();
            assertThat(res.statusCode()).isEqualTo(200);
            servedBy.add(res.headers().firstValue("X-Reach-Worker").orElseThrow());
        }
        assertThat(servedBy).containsExactlyInAnyOrder("w1", "w2", "w3");
    }

    @Test
    void failsOverWhenAWorkerReturnsAnError() throws Exception {
        W1.mode(FakeWorker.Mode.FAIL);
        W2.mode(FakeWorker.Mode.FAIL);
        HttpResponse<String> res = post(CHAT);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("X-Reach-Worker")).hasValue("w3");
        assertThat(res.body()).contains("hello from w3");
    }

    @Test
    void failsOverWhenAWorkerTimesOut() throws Exception {
        W1.mode(FakeWorker.Mode.SLOW);
        W1.slowMillis(5000);
        W2.mode(FakeWorker.Mode.SLOW);
        W2.slowMillis(5000);
        long start = System.nanoTime();
        HttpResponse<String> res = post(CHAT);
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("X-Reach-Worker")).hasValue("w3");
        assertThat(millis).as("two 1 s timeouts, not two 5 s stalls").isLessThan(4000);
    }

    @Test
    void failsOverWhenAWorkerProcessIsGoneBeforeHealthChecksNotice() throws Exception {
        W1.kill();
        try {
            for (int i = 0; i < 5; i++) {
                HttpResponse<String> res = post(CHAT);
                assertThat(res.statusCode()).isEqualTo(200);
                assertThat(res.headers().firstValue("X-Reach-Worker").orElseThrow()).isNotEqualTo("w1");
            }
        } finally {
            W1.revive();
        }
    }

    @Test
    void stopsRoutingToAWorkerThatReportsUnhealthy() throws Exception {
        W2.healthStatus(503); // e.g. still loading its model
        health.checkAll();
        assertThat(view("w2").get("healthy").asBoolean()).isFalse();
        for (int i = 0; i < 6; i++) {
            assertThat(post(CHAT).headers().firstValue("X-Reach-Worker").orElseThrow()).isNotEqualTo("w2");
        }
        assertThat(W2.chatRequests()).isZero();

        W2.healthStatus(200);
        health.checkAll();
        assertThat(view("w2").get("healthy").asBoolean()).isTrue();
    }

    @Test
    void opensTheCircuitOnAWorkerThatKeepsFailing() throws Exception {
        W1.mode(FakeWorker.Mode.FAIL);
        for (int i = 0; i < 12; i++) {
            assertThat(post(CHAT).statusCode()).isEqualTo(200);
        }
        JsonNode w1 = view("w1");
        assertThat(w1.get("circuit").asText()).isEqualTo("OPEN");
        // Once open, w1 gets no more traffic: at most minimum-calls attempts reached it.
        assertThat(W1.chatRequests()).isLessThanOrEqualTo(4);
        assertThat(w1.get("failed").asLong()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void returns503WithEveryFailedAttemptWhenNothingWorks() throws Exception {
        for (FakeWorker w : List.of(W1, W2, W3)) {
            w.mode(FakeWorker.Mode.FAIL);
        }
        HttpResponse<String> res = post(CHAT);
        assertThat(res.statusCode()).isEqualTo(503);
        assertThat(res.headers().firstValue("Retry-After")).isPresent();
        JsonNode error = mapper.readTree(res.body()).get("error");
        assertThat(error.get("type").asText()).isEqualTo("no_worker_available");
        assertThat(error.get("attempts")).hasSize(3);
    }

    @Test
    void streamsTokensInOrder() throws Exception {
        for (FakeWorker w : List.of(W1, W2, W3)) {
            w.mode(FakeWorker.Mode.STREAM);
        }
        HttpResponse<String> res = post(CHAT_STREAM);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        String body = res.body();
        assertThat(body.indexOf("one")).isLessThan(body.indexOf("two"));
        assertThat(body.indexOf("two")).isLessThan(body.indexOf("three"));
        assertThat(body).contains("[DONE]");
    }

    @Test
    void streamFailsOverBeforeTheFirstByte() throws Exception {
        W1.mode(FakeWorker.Mode.FAIL);
        W2.mode(FakeWorker.Mode.FAIL);
        W3.mode(FakeWorker.Mode.STREAM);
        HttpResponse<String> res = post(CHAT_STREAM);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("X-Reach-Worker")).hasValue("w3");
        assertThat(res.body()).contains("[DONE]");
    }

    @Test
    void rejectsRequestsWithoutMessages() throws Exception {
        HttpResponse<String> res = post("{\"messages\":[]}");
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("messages");
        assertThat(post("not json").statusCode()).isEqualTo(400);
    }

    @Test
    void exposesPrometheusMetrics() throws Exception {
        post(CHAT);
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/prometheus")).build();
        String metrics = http.send(req, HttpResponse.BodyHandlers.ofString()).body();
        assertThat(metrics).contains("reach_worker_healthy").contains("reach_request_latency_seconds");
    }
}
