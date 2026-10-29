package io.github.iabrahanarley.active.services;

import io.github.iabrahanarley.active.dto.request.CreateActivationRequest;
import io.github.iabrahanarley.active.dto.response.ActivationRequestResponse;
import io.github.iabrahanarley.active.exceptions.DuplicateRequestException;
import io.github.iabrahanarley.active.exceptions.ResourceNotFoundException;
import io.github.iabrahanarley.active.model.ActivationRequest;
import io.github.iabrahanarley.active.repositories.ActivationRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class ActivationRequestService {
    private final ActivationRequestRepository repository;

    public ActivationRequestService(ActivationRequestRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ActivationRequestResponse create(CreateActivationRequest input) {
        if (repository.existsByExternalId(input.externalId())) {
            throw new DuplicateRequestException();
        }
        ActivationRequest request = new ActivationRequest(
                input.externalId(), input.customerId(), input.productCode());
        return ActivationRequestResponse.from(repository.saveAndFlush(request));
    }

    @Transactional(readOnly = true)
    public ActivationRequestResponse findById(UUID id) {
        return repository.findById(id)
                .map(ActivationRequestResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Solicitação não encontrada."));
    }
}
