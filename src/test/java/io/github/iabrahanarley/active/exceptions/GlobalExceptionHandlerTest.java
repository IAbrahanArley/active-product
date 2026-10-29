package io.github.iabrahanarley.active.exceptions;

import io.github.iabrahanarley.active.controllers.ActivationRequestController;
import io.github.iabrahanarley.active.services.ActivationRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {
    @Test
    void shouldHideInternalDetailsWhenAnUnexpectedErrorOccurs() throws Exception {
        var service = mock(ActivationRequestService.class);
        var id = UUID.randomUUID();
        when(service.findById(id)).thenThrow(new IllegalStateException(
                "password=internal-secret jdbc:postgresql://private-host/database"));
        var mvc = MockMvcBuilders.standaloneSetup(new ActivationRequestController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        var result = mvc.perform(get("/api/v1/requests/{id}", id))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("Ocorreu um erro inesperado."))
                .andExpect(jsonPath("$.instance").value("/api/v1/requests/" + id))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("internal-secret", "private-host", "IllegalStateException", "stackTrace");
    }
}
