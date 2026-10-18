package io.github.pavanprudhvi02.reach.inference;

import io.github.pavanprudhvi02.reach.config.ReachProperties;
import io.github.pavanprudhvi02.reach.worker.Worker;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Talks to llama.cpp's OpenAI-compatible endpoint. Any answer the worker is
 * responsible for (no connection, timeout, 5xx) becomes a WorkerException so
 * the caller can fail over; a 4xx is the client's problem and is returned as is.
 */
@Component
public class WorkerClient {

    static final String CHAT_PATH = "/v1/chat/completions";

    private final HttpClient http;
    private final Duration requestTimeout;

    public WorkerClient(ReachProperties props) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.requestTimeout = props.requestTimeout();
    }

    public record Reply(int status, String contentType, byte[] body) {}

    /** Sends a non-streaming request and reads the whole answer. */
    public Reply complete(Worker worker, byte[] json) throws WorkerException {
        HttpResponse<byte[]> response = send(worker, json, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 500) {
            throw new WorkerException(worker.id() + " answered HTTP " + response.statusCode());
        }
        return new Reply(response.statusCode(),
                response.headers().firstValue("content-type").orElse("application/json"),
                response.body());
    }

    /**
     * Sends a streaming request and returns once the response headers arrive,
     * which is the last point where failing over to another worker is still possible.
     */
    public HttpResponse<InputStream> openStream(Worker worker, byte[] json) throws WorkerException {
        HttpResponse<InputStream> response = send(worker, json, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 500) {
            closeQuietly(response.body());
            throw new WorkerException(worker.id() + " answered HTTP " + response.statusCode());
        }
        return response;
    }

    private <T> HttpResponse<T> send(Worker worker, byte[] json, HttpResponse.BodyHandler<T> handler)
            throws WorkerException {
        HttpRequest request = HttpRequest.newBuilder(worker.endpoint(CHAT_PATH))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
        try {
            return http.send(request, handler);
        } catch (HttpTimeoutException e) {
            throw new WorkerException(worker.id() + " timed out after " + requestTimeout.toMillis() + " ms", e);
        } catch (IOException e) {
            throw new WorkerException(worker.id() + " unreachable: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkerException(worker.id() + " request interrupted", e);
        }
    }

    static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }
}
