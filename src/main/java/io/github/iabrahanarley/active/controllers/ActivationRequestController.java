package io.github.iabrahanarley.active.controllers;

import io.github.iabrahanarley.active.dto.request.CreateActivationRequest;
import io.github.iabrahanarley.active.dto.response.ActivationRequestResponse;
import io.github.iabrahanarley.active.services.ActivationRequestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/requests")
public class ActivationRequestController implements ActivationRequestApi {
    private final ActivationRequestService service;

    public ActivationRequestController(ActivationRequestService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<ActivationRequestResponse> create(CreateActivationRequest request) {
        ActivationRequestResponse response = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/requests/" + response.id())).body(response);
    }

    @Override
    public ResponseEntity<ActivationRequestResponse> findById(UUID id) {
        return ResponseEntity.ok(service.findById(id));
    }
}
