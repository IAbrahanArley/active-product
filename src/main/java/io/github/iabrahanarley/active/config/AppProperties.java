package io.github.iabrahanarley.active.config;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
@Validated
public record AppProperties(@NotNull Cors cors, @NotNull @Valid Jwt jwt) {

	public record Cors(List<String> allowedOrigins) {
	}

	public record Jwt(@NotBlank String secret, @NotBlank String issuer, @NotBlank String audience) {
	}

}
