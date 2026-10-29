package io.github.iabrahanarley.active.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateActivationRequest(
        @NotBlank(message = "é obrigatório") @Size(max = 100) String externalId,
        @NotBlank(message = "é obrigatório") @Size(max = 100) String customerId,
        @NotBlank(message = "é obrigatório") @Size(max = 100) String productCode) {
}
