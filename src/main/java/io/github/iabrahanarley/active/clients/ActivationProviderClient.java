package io.github.iabrahanarley.active.clients;

import io.github.iabrahanarley.active.dto.request.ProviderActivationRequest;
import io.github.iabrahanarley.active.enums.ProviderOutcome;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeUnit;

@Component
public class ActivationProviderClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(ActivationProviderClient.class);
    private final RestClient restClient;

    public ActivationProviderClient(RestClient providerRestClient) {
        this.restClient = providerRestClient;
    }

    public ProviderOutcome activate(String idempotencyKey, ProviderActivationRequest request) {
        long started = System.nanoTime();
        try {
            return restClient.post().uri("/provider/v1/activate")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON).body(request)
                    .exchange((outgoing, response) -> {
                        int status = response.getStatusCode().value();
                        LOGGER.info("provider requestId={} httpStatus={} durationMs={}",
                                idempotencyKey, status, elapsedMillis(started));
                        if (status >= 200 && status < 300) {
                            return ProviderOutcome.SUCCESS;
                        }
                        if (status >= 500 || status == 429 || status == 408) {
                            return ProviderOutcome.TEMPORARY_FAILURE;
                        }
                        return ProviderOutcome.PERMANENT_FAILURE;
                    });
        } catch (RestClientException exception) {
            LOGGER.warn("provider requestId={} category={} durationMs={}",
                    idempotencyKey, failureCategory(exception), elapsedMillis(started));
            return ProviderOutcome.TEMPORARY_FAILURE;
        }
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private String failureCategory(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException) {
                return "TIMEOUT";
            }
        }
        return "CLIENT_OR_TRANSPORT_ERROR";
    }
}
