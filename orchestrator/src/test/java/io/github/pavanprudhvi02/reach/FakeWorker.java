package io.github.pavanprudhvi02.reach;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stand-in for a llama.cpp server with switchable behaviour, so tests can
 * make a worker fail, stall, stream, report unhealthy or vanish entirely.
 */
final class FakeWorker implements AutoCloseable {

    enum Mode { OK, FAIL, SLOW, STREAM }

    private final String name;
    private HttpServer server;
    private int port;
    private volatile Mode mode = Mode.OK;
    private volatile int healthStatus = 200;
    private volatile long slowMillis = 3000;
    private final AtomicInteger chatRequests = new AtomicInteger();

    FakeWorker(String name) throws IOException {
        this.name = name;
        start(0);
    }

    private void start(int requestedPort) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", requestedPort), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/health", this::health);
        server.createContext("/v1/chat/completions", this::chat);
        server.start();
        port = server.getAddress().getPort();
    }

    String url() {
        return "http://127.0.0.1:" + port;
    }

    String name() {
        return name;
    }

    void mode(Mode m) {
        mode = m;
    }

    void healthStatus(int status) {
        healthStatus = status;
    }

    void slowMillis(long ms) {
        slowMillis = ms;
    }

    int chatRequests() {
        return chatRequests.get();
    }

    void resetCounters() {
        chatRequests.set(0);
    }

    /** Simulates the process dying: the port stops accepting connections. */
    void kill() {
        server.stop(0);
    }

    /** Brings the worker back on the same port. */
    void revive() throws IOException {
        start(port);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void health(HttpExchange ex) throws IOException {
        byte[] body = (healthStatus == 200 ? "{\"status\":\"ok\"}" : "{\"error\":\"loading model\"}")
                .getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(healthStatus, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private void chat(HttpExchange ex) throws IOException {
        chatRequests.incrementAndGet();
        ex.getRequestBody().readAllBytes();
        switch (mode) {
            case FAIL -> send(ex, 500, "{\"error\":\"model crashed\"}");
            case SLOW -> {
                sleep(slowMillis);
                send(ex, 200, completion());
            }
            case STREAM -> stream(ex);
            default -> send(ex, 200, completion());
        }
    }

    private String completion() {
        return "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"model\":\"fake\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hello from "
                + name + "\"},\"finish_reason\":\"stop\"}]}";
    }

    private void stream(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0); // chunked
        OutputStream out = ex.getResponseBody();
        String[] tokens = {"one", "two", "three"};
        for (int i = 0; i < tokens.length; i++) {
            String event = "data: {\"choices\":[{\"delta\":{\"content\":\"" + tokens[i] + "\"}}]}\n\n";
            out.write(event.getBytes(StandardCharsets.UTF_8));
            out.flush();
            sleep(50);
        }
        out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        out.close();
    }

    private static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
