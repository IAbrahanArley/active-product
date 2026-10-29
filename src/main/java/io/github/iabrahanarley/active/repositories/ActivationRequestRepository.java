package io.github.iabrahanarley.active.repositories;

import io.github.iabrahanarley.active.model.ActivationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ActivationRequestRepository extends JpaRepository<ActivationRequest, UUID> {
    boolean existsByExternalId(String externalId);
}
