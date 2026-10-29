package io.github.iabrahanarley.active.dto.request;

public record ProviderActivationRequest(String externalId, String customerId, String productCode) {
}
