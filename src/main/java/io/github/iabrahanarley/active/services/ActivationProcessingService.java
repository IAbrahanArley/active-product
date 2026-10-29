package io.github.iabrahanarley.active.services;

import io.github.iabrahanarley.active.config.ProviderProperties;
import io.github.iabrahanarley.active.dto.request.ProviderActivationRequest;
import io.github.iabrahanarley.active.enums.ProviderOutcome;
import io.github.iabrahanarley.active.model.ActivationRequest;
import io.github.iabrahanarley.active.repositories.ActivationRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class ActivationProcessingService {
    private final ActivationRequestRepository repository;
    private final ProviderProperties properties;

    public ActivationProcessingService(ActivationRequestRepository repository, ProviderProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional
    public Optional<ProcessingTask> claimNext() {
        return repository.findNextPending().map(request -> {
            request.startProcessing();
            return new ProcessingTask(request.getId(), new ProviderActivationRequest(
                    request.getExternalId(), request.getCustomerId(), request.getProductCode()));
        });
    }

    @Transactional
    public void finish(UUID id, ProviderOutcome outcome) {
        ActivationRequest request = repository.findById(id).orElseThrow();
        switch (outcome) {
            case SUCCESS -> request.complete();
            case PERMANENT_FAILURE -> request.fail();
            case TEMPORARY_FAILURE -> {
                if (properties.idempotencySupported() && request.getAttempts() < properties.maxAttempts()) {
                    long multiplier = 1L << Math.min(request.getAttempts() - 1, 10);
                    request.scheduleRetry(Instant.now().plus(properties.retryDelay().multipliedBy(multiplier)));
                } else {
                    request.fail();
                }
            }
        }
    }

    public record ProcessingTask(UUID id, ProviderActivationRequest payload) {
    }
}
