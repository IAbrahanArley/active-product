package io.github.iabrahanarley.active.model;

import io.github.iabrahanarley.active.enums.ActivationStatus;
import io.github.iabrahanarley.active.exceptions.InvalidStatusTransitionException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ActivationRequestTest {
    @Test
    void shouldCreatePendingRequest() {
        var request = new ActivationRequest("EXT-1", "CUST-1", "INTERNET");
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.PENDING);
        assertThat(request.getId()).isNotNull();
        assertThat(request.getCreatedAt()).isEqualTo(request.getUpdatedAt());
    }

    @Test
    void shouldCompleteProcessingRequest() {
        var request = new ActivationRequest("EXT-1", "CUST-1", "INTERNET");
        request.startProcessing();
        request.complete();
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThatThrownBy(request::startProcessing).isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(request::fail).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void shouldFailProcessingRequest() {
        var request = new ActivationRequest("EXT-1", "CUST-1", "INTERNET");
        assertThatThrownBy(request::complete).isInstanceOf(InvalidStatusTransitionException.class);
        request.startProcessing();
        request.fail();
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThatThrownBy(request::startProcessing).isInstanceOf(InvalidStatusTransitionException.class);
    }
}
