package io.github.iabrahanarley.active.model;

import io.github.iabrahanarley.active.enums.ActivationStatus;
import io.github.iabrahanarley.active.exceptions.InvalidStatusTransitionException;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "activation_requests")
public class ActivationRequest {
    @Id
    private UUID id;
    @Column(nullable = false, unique = true, length = 100)
    private String externalId;
    @Column(nullable = false, length = 100)
    private String customerId;
    @Column(nullable = false, length = 100)
    private String productCode;
    @Column(length = 200)
    private String consumerId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActivationStatus status;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    @Version
    private Long version;
    @Column(nullable = false)
    private int attempts;
    @Column(nullable = false)
    private Instant nextAttemptAt;

    protected ActivationRequest() {
    }

    public ActivationRequest(String externalId, String customerId, String productCode, String consumerId) {
        if (consumerId == null || consumerId.isBlank() || consumerId.length() > 200) {
            throw new IllegalArgumentException("Consumidor inválido.");
        }
        this.id = UUID.randomUUID();
        this.externalId = externalId;
        this.customerId = customerId;
        this.productCode = productCode;
        this.consumerId = consumerId;
        this.status = ActivationStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        this.nextAttemptAt = createdAt;
    }

    public void startProcessing() {
        transition(ActivationStatus.PENDING, ActivationStatus.PROCESSING);
        attempts++;
    }

    public void complete() {
        transition(ActivationStatus.PROCESSING, ActivationStatus.COMPLETED);
    }

    public void fail() {
        transition(ActivationStatus.PROCESSING, ActivationStatus.FAILED);
    }

    public void scheduleRetry(Instant nextAttemptAt) {
        transition(ActivationStatus.PROCESSING, ActivationStatus.PENDING);
        this.nextAttemptAt = nextAttemptAt;
    }

    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }

    private void transition(ActivationStatus expected, ActivationStatus target) {
        if (status != expected) {
            throw new InvalidStatusTransitionException(status, target);
        }
        status = target;
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getExternalId() { return externalId; }
    public String getCustomerId() { return customerId; }
    public String getProductCode() { return productCode; }
    public ActivationStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
