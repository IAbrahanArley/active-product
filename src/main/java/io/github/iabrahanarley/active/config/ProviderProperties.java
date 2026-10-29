package io.github.iabrahanarley.active.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.provider")
public record ProviderProperties(
        @NotNull URI baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Min(1) int maxAttempts,
        @NotNull Duration retryDelay,
        boolean idempotencySupported) {
    public ProviderProperties {
        if (connectTimeout != null && (connectTimeout.isZero() || connectTimeout.isNegative())
                || readTimeout != null && (readTimeout.isZero() || readTimeout.isNegative())
                || retryDelay != null && (retryDelay.isZero() || retryDelay.isNegative())) {
            throw new IllegalArgumentException("Os tempos do provedor devem ser positivos.");
        }
        if (baseUrl != null && (!baseUrl.isAbsolute()
                || !java.util.Set.of("http", "https").contains(baseUrl.getScheme()))) {
            throw new IllegalArgumentException("A URL do provedor deve usar HTTP ou HTTPS.");
        }
    }
}
