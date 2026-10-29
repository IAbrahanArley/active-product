package io.github.iabrahanarley.active.services;

import io.github.iabrahanarley.active.clients.ActivationProviderClient;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class ActivationWorkerTest {
    @Test
    void shouldStopClaimingRequestsDuringShutdown() {
        var processing = mock(ActivationProcessingService.class);
        var provider = mock(ActivationProviderClient.class);
        var worker = new ActivationWorker(processing, provider);
        worker.stopAcceptingWork();
        worker.processNext();
        verifyNoInteractions(processing, provider);
    }
}
