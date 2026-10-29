package io.github.iabrahanarley.active;

import io.github.iabrahanarley.active.dto.request.CreateActivationRequest;
import io.github.iabrahanarley.active.repositories.ActivationRequestRepository;
import io.github.iabrahanarley.active.services.ActivationRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.processing.enabled=false")
@AutoConfigureMockMvc
class ActivationRequestIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ActivationRequestRepository repository;
    @Autowired ActivationRequestService service;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void shouldCreateAndFindRequest() throws Exception {
        mvc.perform(post("/api/v1/requests").with(authorizedJwt())
                .contentType("application/json").content(payload("EXT-1")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.externalId").value("EXT-1"));

        var id = repository.findAll().getFirst().getId();
        mvc.perform(get("/api/v1/requests/{id}", id).with(authorizedJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.customerId").value("CUST-1"));
    }

    @Test
    void shouldRejectInvalidInput() throws Exception {
        mvc.perform(post("/api/v1/requests").with(authorizedJwt())
                .contentType("application/json")
                .content("{\"externalId\":\" \",\"customerId\":\"\",\"productCode\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.errors.length()").value(3));
        assertThat(repository.count()).isZero();
    }

    @Test
    void shouldRejectMalformedBodyAndInvalidId() throws Exception {
        mvc.perform(post("/api/v1/requests").with(authorizedJwt())
                .contentType("application/json").content("{"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/requests/not-a-uuid").with(authorizedJwt()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnNotFound() throws Exception {
        mvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()).with(authorizedJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Solicitação não encontrada."));
    }

    @Test
    void shouldRejectDuplicateEvenWithDifferentContent() throws Exception {
        service.create(new CreateActivationRequest("EXT-1", "CUST-1", "INTERNET"));
        for (String body : new String[]{payload("EXT-1"), payload("EXT-1").replace("INTERNET", "PHONE")}) {
            mvc.perform(post("/api/v1/requests").with(authorizedJwt())
                    .contentType("application/json").content(body))
                    .andExpect(status().isConflict());
        }
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void shouldRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/requests").contentType("application/json").content(payload("EXT-1")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldPublishInterfaceContractInOpenApi() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/requests'].post.summary").value("Criar solicitação"))
                .andExpect(jsonPath("$.paths['/api/v1/requests/{id}'].get").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
    }

    @Test
    void shouldCreateExactlyOneRequestUnderConcurrency() throws Exception {
        int callers = 6;
        var ready = new CountDownLatch(callers);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(callers)) {
            var results = IntStream.range(0, callers).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timeout aguardando início do teste");
                }
                return mvc.perform(post("/api/v1/requests").with(authorizedJwt())
                        .contentType("application/json").content(payload("EXT-CONCURRENT")))
                        .andReturn().getResponse().getStatus();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var statuses = new java.util.ArrayList<Integer>();
            for (var result : results) {
                statuses.add(result.get(20, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses.stream().filter(code -> code == 201).count()).isEqualTo(1);
            assertThat(repository.count()).isEqualTo(1);
        }
    }

    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor authorizedJwt() {
        return jwt().jwt(token -> token.claim("scope", "requests:read requests:write"));
    }

    private String payload(String externalId) {
        return """
                {"externalId":"%s","customerId":"CUST-1","productCode":"INTERNET"}
                """.formatted(externalId);
    }
}
