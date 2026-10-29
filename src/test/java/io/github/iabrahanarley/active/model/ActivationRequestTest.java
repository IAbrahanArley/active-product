package io.github.iabrahanarley.active.model;

import io.github.iabrahanarley.active.enums.ActivationStatus;
import io.github.iabrahanarley.active.exceptions.InvalidStatusTransitionException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ActivationRequestTest {
    @Test
    void shouldCreatePendingRequest() {
        var request = new ActivationRequest("EXT-1", "CUST-1", "INTERNET", "consumer-test");
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getId()).isNotNull();
        assertThat(request.getCreatedAt()).isEqualTo(request.getUpdatedAt());
    }

    @Test
    void shouldCompleteProcessingRequest() {
        var request = new ActivationRequest("EXT-1", "CUST-1", "INTERNET", "consumer-test");
        request.startProcessing();
        request.complete();
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThatThrownBy(request::startProcessing).isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(request::fail).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void shouldFailProcessingRequest() {
        var request = new ActivationRequest("EXT-1", "CUST-1", "INTERNET", "consumer-test");
        assertThatThrownBy(request::complete).isInstanceOf(InvalidStatusTransitionException.class);
        request.startProcessing();
        request.fail();
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThatThrownBy(request::startProcessing).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void shouldScheduleRetryAndCountAttempts() {
        var request = new ActivationRequest("EXT-1", "CUST-1", "INTERNET", "consumer-test");
        var nextAttempt = java.time.Instant.now().plusSeconds(10);
        request.startProcessing();
        request.scheduleRetry(nextAttempt);
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getNextAttemptAt()).isEqualTo(nextAttempt);
        request.startProcessing();
        assertThat(request.getAttempts()).isEqualTo(2);
        request.complete();
        assertThatThrownBy(() -> request.scheduleRetry(nextAttempt))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }
}
