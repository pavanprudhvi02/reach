package io.github.pavanprudhvi02.reach.inference;

import io.github.pavanprudhvi02.reach.routing.Router;
import java.util.List;

/** Every attempt failed, or no worker could take the request. */
public class NoWorkerAvailableException extends RuntimeException {

    private final Router.Unavailable reason;
    private final List<String> attempts;

    public NoWorkerAvailableException(Router.Unavailable reason, List<String> attempts) {
        super(describe(reason, attempts));
        this.reason = reason;
        this.attempts = List.copyOf(attempts);
    }

    public Router.Unavailable reason() {
        return reason;
    }

    public List<String> attempts() {
        return attempts;
    }

    private static String describe(Router.Unavailable reason, List<String> attempts) {
        String base = switch (reason) {
            case ALL_BUSY -> "all workers are at capacity";
            case NO_HEALTHY_WORKER -> attempts.isEmpty()
                    ? "no healthy worker is available"
                    : "no healthy worker left to try after " + attempts.size() + " failed attempt(s)";
        };
        return base;
    }
}
