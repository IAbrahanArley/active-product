package io.github.iabrahanarley.active;

import com.sun.net.httpserver.HttpServer;
import io.github.iabrahanarley.active.clients.ActivationProviderClient;
import io.github.iabrahanarley.active.dto.request.CreateActivationRequest;
import io.github.iabrahanarley.active.enums.ActivationStatus;
import io.github.iabrahanarley.active.repositories.ActivationRequestRepository;
import io.github.iabrahanarley.active.services.ActivationProcessingService;
import io.github.iabrahanarley.active.services.ActivationRequestService;
import io.github.iabrahanarley.active.services.ActivationWorker;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "app.processing.enabled=false",
        "app.provider.connect-timeout=1s",
        "app.provider.read-timeout=300ms",
        "app.provider.max-attempts=2",
        "app.provider.retry-delay=20ms",
        "app.provider.idempotency-supported=true"
})
class ActivationProviderIntegrationTests {
    private static final AtomicInteger RESPONSE_STATUS = new AtomicInteger(204);
    private static final AtomicLong RESPONSE_DELAY = new AtomicLong();
    private static final CopyOnWriteArrayList<String> KEYS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<String> BODIES = new CopyOnWriteArrayList<>();
    private static final HttpServer SERVER = startServer();

    @Autowired ActivationRequestRepository repository;
    @Autowired ActivationRequestService requestService;
    @Autowired ActivationProcessingService processingService;
    @MockitoSpyBean ActivationProviderClient providerClient;
    private ActivationWorker worker;

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        registry.add("app.provider.base-url", () -> "http://localhost:" + SERVER.getAddress().getPort());
    }

    @BeforeEach
    void prepare() {
        repository.deleteAll();
        RESPONSE_STATUS.set(204);
        RESPONSE_DELAY.set(0);
        KEYS.clear();
        BODIES.clear();
        worker = new ActivationWorker(processingService, providerClient);
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var id = UUID.fromString(invocation.getArgument(0));
            assertThat(repository.findById(id).orElseThrow().getStatus())
                    .isEqualTo(ActivationStatus.PROCESSING);
            return invocation.callRealMethod();
        }).when(providerClient).activate(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @AfterAll
    static void shutdown() {
        SERVER.stop(0);
    }

    @Test
    void shouldCompleteActivationWithRealHttpCall() {
        UUID id = createRequest();
        worker.processNext();
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(KEYS).containsExactly(id.toString());
        assertThat(BODIES.getFirst()).contains("\"externalId\":\"EXT-PROVIDER\"",
                "\"customerId\":\"CUST-1\"", "\"productCode\":\"INTERNET\"");
        worker.processNext();
        assertThat(KEYS).hasSize(1);
    }

    @Test
    void shouldFailWithoutRetryForBadRequest() {
        RESPONSE_STATUS.set(400);
        UUID id = createRequest();
        worker.processNext();
        var request = repository.findById(id).orElseThrow();
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getAttempts()).isEqualTo(1);
        worker.processNext();
        assertThat(KEYS).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 503, 429})
    void shouldRetryServerErrorAndCompleteUsingSameIdempotencyKey(int status) {
        RESPONSE_STATUS.set(status);
        UUID id = createRequest();
        worker.processNext();
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(ActivationStatus.PENDING);
        RESPONSE_STATUS.set(200);
        awaitRetry(id);
        worker.processNext();
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(KEYS).containsExactly(id.toString(), id.toString());
    }

    @Test
    void shouldStopAfterMaximumAttempts() {
        RESPONSE_STATUS.set(503);
        UUID id = createRequest();
        worker.processNext();
        awaitRetry(id);
        worker.processNext();
        var request = repository.findById(id).orElseThrow();
        assertThat(request.getStatus()).isEqualTo(ActivationStatus.FAILED);
        assertThat(request.getAttempts()).isEqualTo(2);
        worker.processNext();
        assertThat(KEYS).hasSize(2);
    }

    @Test
    void shouldRetryTimeoutAndKeepTheSameKey() {
        RESPONSE_DELAY.set(1000);
        UUID id = createRequest();
        worker.processNext();
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(ActivationStatus.PENDING);
        RESPONSE_DELAY.set(0);
        awaitRetry(id);
        worker.processNext();
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(ActivationStatus.COMPLETED);
        assertThat(KEYS).containsExactly(id.toString(), id.toString());
    }

    @Test
    void shouldNotClaimARequestTwice() {
        UUID id = createRequest();
        assertThat(processingService.claimNext()).isPresent();
        assertThat(processingService.claimNext()).isEmpty();
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(ActivationStatus.PROCESSING);
    }

    private UUID createRequest() {
        return requestService.create(new CreateActivationRequest("EXT-PROVIDER", "CUST-1", "INTERNET")).id();
    }

    private void awaitRetry(UUID id) {
        await().atMost(Duration.ofSeconds(3)).until(() ->
                !repository.findById(id).orElseThrow().getNextAttemptAt().isAfter(java.time.Instant.now()));
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/provider/v1/activate", exchange -> {
                KEYS.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                long delay = RESPONSE_DELAY.get();
                int status = RESPONSE_STATUS.get();
                try {
                    Thread.sleep(delay);
                    exchange.sendResponseHeaders(status, -1);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
