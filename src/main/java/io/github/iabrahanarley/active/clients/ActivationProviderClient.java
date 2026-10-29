package io.github.iabrahanarley.active.clients;

import io.github.iabrahanarley.active.dto.request.ProviderActivationRequest;
import io.github.iabrahanarley.active.enums.ProviderOutcome;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class ActivationProviderClient {
    private final RestClient restClient;

    public ActivationProviderClient(RestClient providerRestClient) {
        this.restClient = providerRestClient;
    }

    public ProviderOutcome activate(String idempotencyKey, ProviderActivationRequest request) {
        try {
            return restClient.post().uri("/provider/v1/activate")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON).body(request)
                    .exchange((outgoing, response) -> {
                        int status = response.getStatusCode().value();
                        if (status >= 200 && status < 300) {
                            return ProviderOutcome.SUCCESS;
                        }
                        if (status >= 500 || status == 429 || status == 408) {
                            return ProviderOutcome.TEMPORARY_FAILURE;
                        }
                        return ProviderOutcome.PERMANENT_FAILURE;
                    });
        } catch (RestClientException exception) {
            return ProviderOutcome.TEMPORARY_FAILURE;
        }
    }
}
