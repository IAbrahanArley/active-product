package io.github.iabrahanarley.active.clients;

import io.github.iabrahanarley.active.dto.request.ProviderActivationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import java.net.SocketTimeoutException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@ExtendWith(OutputCaptureExtension.class)
class ActivationProviderClientTest {
    @ParameterizedTest
    @ValueSource(ints = {204, 400, 500})
    void shouldLogHttpStatusAndDurationWithoutPayload(int status, CapturedOutput output) {
        var builder = RestClient.builder().baseUrl("http://provider.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://provider.test/provider/v1/activate"))
                .andRespond(withStatus(HttpStatus.valueOf(status)));
        new ActivationProviderClient(builder.build()).activate("request-123", payload());
        server.verify();
        assertThat(output.getOut()).contains("requestId=request-123", "httpStatus=" + status, "durationMs=")
                .doesNotContain("private-customer", "private-external", "private-product");
    }

    @Test
    void shouldLogTimeoutCategoryWithoutExceptionMessage(CapturedOutput output) {
        var builder = RestClient.builder().baseUrl("http://provider.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://provider.test/provider/v1/activate"))
                .andRespond(request -> { throw new ResourceAccessException(
                        "token=private-token", new SocketTimeoutException("private-host")); });
        new ActivationProviderClient(builder.build()).activate("request-123", payload());
        server.verify();
        assertThat(output.getOut()).contains("category=TIMEOUT", "requestId=request-123", "durationMs=")
                .doesNotContain("private-token", "private-host", "private-customer");
    }

    private ProviderActivationRequest payload() {
        return new ProviderActivationRequest("private-external", "private-customer", "private-product");
    }
}
