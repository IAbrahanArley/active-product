package io.github.iabrahanarley.active.controllers;

import io.github.iabrahanarley.active.dto.request.CreateActivationRequest;
import io.github.iabrahanarley.active.dto.response.ActivationRequestResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@Tag(name = "Solicitações", description = "Criação e consulta de solicitações de ativação.")
@SecurityRequirement(name = "bearerAuth")
public interface ActivationRequestApi {
    @Operation(summary = "Criar solicitação", description = """
            Registra uma solicitação em PENDING. Campos obrigatórios com até 100 caracteres.
            externalId é único e sensível a maiúsculas; repetições retornam 409,
            inclusive quando o conteúdo é idêntico. Datas são retornadas em UTC.
            A ativação ocorre de forma assíncrona; consulte o GET para acompanhar o resultado.
            Requer o escopo requests:write no token.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Solicitação criada.",
            headers = @Header(name = "Location", description = "Endereço da solicitação."),
            content = @Content(schema = @Schema(implementation = ActivationRequestResponse.class))),
        @ApiResponse(responseCode = "400", description = "Dados inválidos.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "401", description = "Token ausente ou inválido.", content = @Content),
        @ApiResponse(responseCode = "403", description = "Escopo requests:write ausente.", content = @Content),
        @ApiResponse(responseCode = "409", description = "externalId já cadastrado.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Erro inesperado.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping
    ResponseEntity<ActivationRequestResponse> create(@Valid @RequestBody CreateActivationRequest request);

    @Operation(summary = "Consultar solicitação", description = "Retorna os dados e o status atual da solicitação. Requer o escopo requests:read no token.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Solicitação encontrada.",
            content = @Content(schema = @Schema(implementation = ActivationRequestResponse.class))),
        @ApiResponse(responseCode = "400", description = "Identificador inválido.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "401", description = "Token ausente ou inválido.", content = @Content),
        @ApiResponse(responseCode = "404", description = "Solicitação não encontrada.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Escopo requests:read ausente.", content = @Content),
        @ApiResponse(responseCode = "500", description = "Erro inesperado.",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{id}")
    ResponseEntity<ActivationRequestResponse> findById(
        @Parameter(description = "Identificador UUID da solicitação.", required = true)
        @PathVariable("id") UUID id);
}
