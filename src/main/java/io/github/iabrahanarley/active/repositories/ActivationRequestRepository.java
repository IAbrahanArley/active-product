package io.github.iabrahanarley.active.repositories;

import io.github.iabrahanarley.active.model.ActivationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
import java.util.UUID;

public interface ActivationRequestRepository extends JpaRepository<ActivationRequest, UUID> {
    boolean existsByExternalId(String externalId);
    Optional<ActivationRequest> findByIdAndConsumerId(UUID id, String consumerId);

    @Query(value = """
            SELECT * FROM activation_requests
            WHERE status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP
            ORDER BY next_attempt_at, created_at
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<ActivationRequest> findNextPending();
}
