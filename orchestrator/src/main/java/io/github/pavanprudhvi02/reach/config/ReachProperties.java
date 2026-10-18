package io.github.pavanprudhvi02.reach.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Everything the orchestrator needs to know about its worker pool, bound from
 * the {@code reach.*} properties (application.yml or environment variables).
 */
@Validated
@ConfigurationProperties(prefix = "reach")
public record ReachProperties(
        @NotEmpty List<@Valid WorkerSpec> workers,
        @DefaultValue("2s") Duration healthInterval,
        @DefaultValue("1s") Duration healthTimeout,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("120s") Duration requestTimeout,
        @DefaultValue("3") @Min(1) int maxAttempts,
        @DefaultValue("4") @Min(1) int maxInFlightPerWorker,
        @DefaultValue @Valid Breaker breaker) {

    /** One llama.cpp server. {@code url} is its base address, e.g. http://worker-1:8080. */
    public record WorkerSpec(@NotBlank String id, @NotNull URI url) {}

    /** Circuit breaker settings applied to every worker. */
    public record Breaker(
            @DefaultValue("50") float failureRateThreshold,
            @DefaultValue("6") int slidingWindowSize,
            @DefaultValue("4") int minimumCalls,
            @DefaultValue("15s") Duration openDuration,
            @DefaultValue("2") int halfOpenCalls) {}
}
