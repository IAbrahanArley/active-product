package io.github.iabrahanarley.active;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.iabrahanarley.active.config.AppProperties;
import io.github.iabrahanarley.active.dto.request.CreateActivationRequest;
import io.github.iabrahanarley.active.repositories.ActivationRequestRepository;
import io.github.iabrahanarley.active.services.ActivationRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.processing.enabled=false")
@AutoConfigureMockMvc
class SecurityIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired AppProperties properties;
    @Autowired ActivationRequestRepository repository;
    @Autowired ActivationRequestService service;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void shouldAllowWriteScopeToCreate() throws Exception {
        mvc.perform(post("/api/v1/requests")
                .header("Authorization", bearer("requests:write"))
                .contentType("application/json").content(payload()))
                .andExpect(status().isCreated());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void shouldAllowReadScopeToConsult() throws Exception {
        UUID id = service.create(new CreateActivationRequest("EXT-SECURITY", "CUST-1", "INTERNET")).id();
        mvc.perform(get("/api/v1/requests/{id}", id)
                .header("Authorization", bearer("requests:read")))
                .andExpect(status().isOk());
    }

    @Test
    void shouldForbidCreationWithReadOnlyScope() throws Exception {
        mvc.perform(post("/api/v1/requests")
                .header("Authorization", bearer("requests:read"))
                .contentType("application/json").content(payload()))
                .andExpect(status().isForbidden());
        assertThat(repository.count()).isZero();
    }

    @Test
    void shouldForbidConsultationWithWriteOnlyScope() throws Exception {
        mvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID())
                .header("Authorization", bearer("requests:write")))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldForbidRequestsWithoutScopes() throws Exception {
        String token = bearer("");
        mvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()).header("Authorization", token))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/requests").header("Authorization", token)
                .contentType("application/json").content(payload()))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAcceptScopeArrayInScpClaim() throws Exception {
        var claims = validClaims("ignored").claim("scope", null)
                .claim("scp", java.util.List.of("requests:read"));
        mvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID())
                .header("Authorization", sign(claims, properties.jwt().secret())))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "future", "issuer", "audience", "no-issuer", "no-audience",
            "no-expiration", "signature", "malformed", "unsigned"})
    void shouldRejectInvalidTokens(String scenario) throws Exception {
        var claims = validClaims("requests:write");
        String secret = properties.jwt().secret();
        switch (scenario) {
            case "expired" -> claims.expirationTime(Date.from(Instant.now().minusSeconds(180)));
            case "future" -> claims.notBeforeTime(Date.from(Instant.now().plusSeconds(180)));
            case "issuer" -> claims.issuer("https://untrusted.example");
            case "audience" -> claims.audience("another-api");
            case "no-issuer" -> claims.issuer(null);
            case "no-audience" -> claims.audience((String) null);
            case "no-expiration" -> claims.expirationTime(null);
            case "signature" -> secret = "invalid-signing-key-at-least-32-bytes-long";
            default -> { }
        }
        String authorization = switch (scenario) {
            case "malformed" -> "Bearer not-a-jwt";
            case "unsigned" -> "Bearer " + new com.nimbusds.jwt.PlainJWT(claims.build()).serialize();
            default -> sign(claims, secret);
        };
        mvc.perform(post("/api/v1/requests").header("Authorization", authorization)
                .contentType("application/json").content(payload()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")));
        assertThat(repository.count()).isZero();
    }

    @Test
    void shouldKeepDocumentationAndHealthPublic() throws Exception {
        for (String path : new String[]{"/v3/api-docs", "/actuator/health"}) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/requests'].post.responses['403']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/requests/{id}'].get.responses['403']").exists());
    }

    private String bearer(String scope) throws Exception {
        return sign(validClaims(scope), properties.jwt().secret());
    }

    private JWTClaimsSet.Builder validClaims(String scope) {
        return new JWTClaimsSet.Builder()
                .subject("consumer-test")
                .issuer(properties.jwt().issuer())
                .audience(properties.jwt().audience())
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("scope", scope);
    }

    private String sign(JWTClaimsSet.Builder claims, String secret) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return "Bearer " + jwt.serialize();
    }

    private String payload() {
        return """
                {"externalId":"EXT-SECURITY","customerId":"CUST-1","productCode":"INTERNET"}
                """;
    }
}
