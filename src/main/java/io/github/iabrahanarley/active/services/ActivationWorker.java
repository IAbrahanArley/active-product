package io.github.iabrahanarley.active.services;

import io.github.iabrahanarley.active.clients.ActivationProviderClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.processing.enabled", havingValue = "true", matchIfMissing = true)
public class ActivationWorker {
    private final ActivationProcessingService processingService;
    private final ActivationProviderClient providerClient;

    public ActivationWorker(ActivationProcessingService processingService, ActivationProviderClient providerClient) {
        this.processingService = processingService;
        this.providerClient = providerClient;
    }

    @Scheduled(fixedDelayString = "${app.processing.poll-delay:1000}")
    public void processNext() {
        processingService.claimNext().ifPresent(task -> {
            var outcome = providerClient.activate(task.id().toString(), task.payload());
            processingService.finish(task.id(), outcome);
        });
    }
}
