package io.github.pavanprudhvi02.reach.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pavanprudhvi02.reach.inference.InferenceService;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * OpenAI-compatible chat endpoint. The request body is passed through to the
 * chosen llama.cpp worker unchanged; the response carries which worker served
 * it and how many attempts it took.
 */
@RestController
public class ChatController {

    static final String WORKER_HEADER = "X-Reach-Worker";
    static final String ATTEMPTS_HEADER = "X-Reach-Attempts";

    private final InferenceService inference;
    private final ObjectMapper mapper;

    public ChatController(InferenceService inference, ObjectMapper mapper) {
        this.inference = inference;
        this.mapper = mapper;
    }

    // Both paths return a StreamingResponseBody: Spring MVC only treats the body
    // as a stream when the declared return type says so.
    @PostMapping(path = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> chat(@RequestBody byte[] body) {
        JsonNode request = parse(body);
        boolean stream = request.path("stream").asBoolean(false);
        return stream ? streamed(body) : buffered(body);
    }

    private ResponseEntity<StreamingResponseBody> buffered(byte[] body) {
        InferenceService.Completion c = inference.complete(body);
        byte[] answer = c.reply().body();
        return ResponseEntity.status(c.reply().status())
                .contentType(MediaType.parseMediaType(c.reply().contentType()))
                .contentLength(answer.length)
                .header(WORKER_HEADER, c.worker().id())
                .header(ATTEMPTS_HEADER, String.valueOf(c.attempts()))
                .body(out -> out.write(answer));
    }

    /**
     * Server-sent events are relayed as they arrive. Failover can only happen
     * before the first byte reaches the client; if the worker dies mid-stream
     * the client gets an error event, because the tokens already sent cannot
     * be taken back.
     */
    private ResponseEntity<StreamingResponseBody> streamed(byte[] body) {
        InferenceService.OpenStream open = inference.openStream(body);
        int status = open.response().statusCode();
        StreamingResponseBody relay = out -> {
            Throwable error = null;
            try (InputStream in = open.response().body()) {
                copyFlushing(in, out);
            } catch (IOException e) {
                error = e;
                writeStreamError(out, open.worker().id());
            } finally {
                inference.finishStream(open, error);
            }
        };
        MediaType type = status == 200 ? MediaType.TEXT_EVENT_STREAM
                : MediaType.parseMediaType(open.response().headers().firstValue("content-type").orElse("application/json"));
        return ResponseEntity.status(status)
                .contentType(type)
                .header("Cache-Control", "no-cache")
                .header(WORKER_HEADER, open.worker().id())
                .header(ATTEMPTS_HEADER, String.valueOf(open.attempts()))
                .body(relay);
    }

    private static void copyFlushing(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            out.flush(); // push each token to the client as soon as it arrives
        }
    }

    private static void writeStreamError(OutputStream out, String workerId) {
        String event = "data: {\"error\":{\"message\":\"worker " + workerId
                + " failed mid-stream\",\"type\":\"worker_failure\"}}\n\n";
        try {
            out.write(event.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
            // the client is gone too
        }
    }

    private JsonNode parse(byte[] body) {
        JsonNode node;
        try {
            node = mapper.readTree(body);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body is not valid JSON");
        }
        if (node == null || !node.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body must be a JSON object");
        }
        JsonNode messages = node.get("messages");
        if (messages == null || !messages.isArray() || messages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messages must be a non-empty array");
        }
        return node;
    }
}
