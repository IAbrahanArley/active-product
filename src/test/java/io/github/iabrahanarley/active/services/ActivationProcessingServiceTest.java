package io.github.iabrahanarley.active.services;

import io.github.iabrahanarley.active.config.ProviderProperties;
import io.github.iabrahanarley.active.enums.ActivationStatus;
import io.github.iabrahanarley.active.enums.ProviderOutcome;
import io.github.iabrahanarley.active.model.ActivationRequest;
import io.github.iabrahanarley.active.repositories.ActivationRequestRepository;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ActivationProcessingServiceTest {
    @Test
    void shouldNotRetryWhenProviderDoesNotGuaranteeIdempotency() {
        var repository = mock(ActivationRequestRepository.class);
        var properties = new ProviderProperties(URI.create("http://localhost:8081"),
                Duration.ofSeconds(1), Duration.ofSeconds(1), 3, Duration.ofSeconds(2), false);
        var request = new ActivationRequest("EXT-1", "CUST-1", "INTERNET", "consumer-test");
        request.startProcessing();
        when(repository.findById(request.getId())).thenReturn(Optional.of(request));

        new ActivationProcessingService(repository, properties)
                .finish(request.getId(), ProviderOutcome.TEMPORARY_FAILURE);

        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getAttempts()).isEqualTo(1);
    }
}
