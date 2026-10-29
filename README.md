# Product Activation Service

API de solicitações de ativação, em Java 21 e Spring Boot.

## Estado atual

Criação e consulta implementadas. As solicitações são registradas em `PENDING`.
A integração com o provedor ainda não foi implementada: nenhum produto é ativado nesta etapa.

## Arquitetura

- `model`: entidade JPA com transições de estado controladas e lock otimista.
- `enums`: estados da solicitação.
- `repositories`: acesso ao PostgreSQL via Spring Data JPA.
- `services`: casos de uso e limites de transação.
- `controllers`: interface `ActivationRequestApi` com contrato HTTP e documentação Swagger; implementação `ActivationRequestController` com `@Override`.
- `dto/request` e `dto/response`: contratos separados da entidade.
- `exceptions`: exceções em arquivos próprios e `GlobalExceptionHandler`, com Problem Details.
- `config`: segurança e OpenAPI.

Organização inspirada no backend https://github.com/IAbrahanArley/project-rh/tree/main/backend.

## Como executar

Requisitos: Java 21 e Docker em execução. O Maven Wrapper está incluído.

```powershell
docker compose up -d postgres
.\mvnw.cmd spring-boot:run
```

Se a aplicação do Compose já ocupar a porta 8080, pare apenas esse serviço antes de executar localmente: `docker compose stop api`.

Para executar aplicação e banco em containers:

```powershell
docker compose up -d --build
```

O Docker exige arquivos acessíveis no contexto de build. Se ocorrer `invalid file request pom.xml` em pasta sincronizada pelo OneDrive, disponibilize os arquivos localmente ou use uma cópia fora da pasta sincronizada.

Swagger UI: http://localhost:8080/swagger-ui/index.html
OpenAPI: http://localhost:8080/v3/api-docs
Métricas: http://localhost:8080/actuator/prometheus

A documentação e as métricas estão liberadas na configuração de desenvolvimento. Os endpoints da API exigem JWT Bearer HS256 assinado com `JWT_SECRET`. Não existe formulário de login nem emissão de tokens neste serviço. O botão Authorize do Swagger recebe um token emitido externamente.

### API

`POST /api/v1/requests` recebe:

```json
{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}
```

Os três campos são obrigatórios, não podem conter somente espaços e aceitam até 100 caracteres. Os valores são preservados, sem normalização; `externalId` diferencia maiúsculas de minúsculas.

Uma criação retorna `201` com `Location`; `GET /api/v1/requests/{id}` retorna `200` ou `404`. Datas são representadas em UTC. JSON malformado, UUID inválido e violações de validação retornam `400`.

### Duplicidade

Qualquer repetição de `externalId` retorna `409`, mesmo com conteúdo idêntico. O consumidor pode consultar a solicitação pelo ID obtido na primeira criação. A consulta prévia melhora a mensagem, mas a garantia contra concorrência é a constraint UNIQUE no PostgreSQL. Uma colisão concorrente causa rollback e é convertida em `409` pelo handler, sem continuar a transação invalidada.

### Estados

`PENDING -> PROCESSING -> COMPLETED` ou `FAILED`. Não há setters livres para status. A política de retry será definida junto da integração; nesta etapa não há processamento automático.

## Testes

```powershell
.\mvnw.cmd verify
```

Os testes de domínio executam sem Spring. Os testes de integração usam MockMvc, a aplicação completa e PostgreSQL isolado via Testcontainers, com a migration real. Cobrem criação, validação, consulta, duplicidade, chamadas concorrentes, ausência de autenticação e geração OpenAPI a partir da interface. O JWT simulado nesses testes verifica as regras HTTP de autenticação, não a validação criptográfica de tokens.

## Pontos pendentes e próximos incrementos

- Cliente HTTP do provedor, timeouts, política de retry e chave de idempotência.
- Testes dos retornos do provedor e timeout.
- Escopos de leitura/escrita, validação de emissor e audiência, e isolamento entre consumidores.
- Mock do provedor no Compose, CI e melhorias do container.
- Correlação de logs e resposta ao cenário de análise de lentidão do desafio.

## Produção e melhorias futuras

A imagem é construída em múltiplos estágios. Banco e credenciais são configurados por variáveis de ambiente. Antes de produção, remover os segredos padrão de desenvolvimento, usar um provedor OAuth 2.0 com JWKS e fluxo client_credentials, restringir métricas à rede operacional, executar como usuário sem privilégios e configurar memória e desligamento gracioso. A estratégia de recuperação após falhas será definida com o processamento do provedor.

## Uso de IA

Codex auxiliou na análise do enunciado, estrutura MVC, implementação inicial, documentação e testes.
