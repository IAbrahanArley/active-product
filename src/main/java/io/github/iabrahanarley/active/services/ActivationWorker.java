package io.github.iabrahanarley.active.services;

import io.github.iabrahanarley.active.clients.ActivationProviderClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

@Component
@ConditionalOnProperty(name = "app.processing.enabled", havingValue = "true", matchIfMissing = true)
public class ActivationWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(ActivationWorker.class);
    private final ActivationProcessingService processingService;
    private final ActivationProviderClient providerClient;
    private volatile boolean stopping;

    public ActivationWorker(ActivationProcessingService processingService, ActivationProviderClient providerClient) {
        this.processingService = processingService;
        this.providerClient = providerClient;
    }

    @Scheduled(fixedDelayString = "${app.processing.poll-delay:1000}")
    public void processNext() {
        if (stopping) {
            return;
        }
        processingService.claimNext().ifPresent(task -> {
            LOGGER.info("activation requestId={} attempt={} stage=STARTED", task.id(), task.attempt());
            var outcome = providerClient.activate(task.id().toString(), task.payload());
            processingService.finish(task.id(), outcome);
            LOGGER.info("activation requestId={} attempt={} stage=FINISHED outcome={}",
                    task.id(), task.attempt(), outcome);
        });
    }

    @EventListener(ContextClosedEvent.class)
    public void stopAcceptingWork() {
        stopping = true;
    }
}
