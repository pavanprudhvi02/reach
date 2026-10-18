package io.github.pavanprudhvi02.reach.inference;

/** A worker failed to answer: connection refused, timeout, or a 5xx response. */
public class WorkerException extends Exception {

    public WorkerException(String message) {
        super(message);
    }

    public WorkerException(String message, Throwable cause) {
        super(message, cause);
    }
}
