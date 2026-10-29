# Product Activation Service

API de solicitações de ativação, em Java 21 e Spring Boot.

## Estado atual

Criação, consulta e integração HTTP assíncrona implementadas. O POST registra em `PENDING`;
um worker busca pendências no PostgreSQL e chama o provedor. O Compose inclui um mock que responde com sucesso.

## Arquitetura

- `model`: entidade JPA com transições de estado controladas e lock otimista.
- `enums`: estados da solicitação.
- `repositories`: acesso ao PostgreSQL via Spring Data JPA.
- `services`: casos de uso e limites de transação.
- `clients`: comunicação HTTP com o provedor.
- `controllers`: interface `ActivationRequestApi` com contrato HTTP e documentação Swagger; implementação `ActivationRequestController` com `@Override`.
- `dto/request` e `dto/response`: contratos separados da entidade.
- `exceptions`: exceções em arquivos próprios e `GlobalExceptionHandler`, com Problem Details.
- `config`: segurança e OpenAPI.

Organização inspirada no backend https://github.com/IAbrahanArley/project-rh/tree/main/backend.

## Como executar

Requisitos: Java 21 e Docker em execução. O Maven Wrapper está incluído.

```powershell
docker compose up -d postgres provider
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

### Segurança e acesso local

| Operação | Escopo exigido |
|---|---|
| POST /api/v1/requests | `requests:write` |
| GET /api/v1/requests/{id} | `requests:read` |

Os escopos são independentes: escrita não concede leitura automaticamente. O token pode conter ambos no claim `scope`, separados por espaço, ou no array `scp`. Um token válido sem o escopo necessário retorna `403`. Token ausente, malformado, sem assinatura válida ou com claims inválidos retorna `401`, com header `WWW-Authenticate`.

O decoder aceita somente HS256 e valida assinatura, emissor `iss`, audiência `aud`, presença e validade de `exp` e `nbf` quando informado. As validações temporais usam a tolerância padrão do Spring Security, de 60 segundos. Claims `iss`, `aud` e `exp` são obrigatórios.

| Variável | Valor local padrão |
|---|---|
| `JWT_SECRET` | Segredo de desenvolvimento do Compose, compartilhado com a execução local |
| `JWT_ISSUER` | `https://auth.local.test` |
| `JWT_AUDIENCE` | `product-activation-service` |

O endereço de emissor local identifica o token; não é um servidor de autenticação executado pelo projeto. A expiração vem no token emitido, por isso a antiga configuração `JWT_EXPIRATION_MINUTES` foi removida do serviço.

Para experimentar localmente, o auxiliar abaixo gera um token de desenvolvimento válido por cinco minutos:

```powershell
$token = .\scripts\New-DevelopmentToken.ps1
# Para criar um consumidor com acesso somente de leitura:
$readToken = .\scripts\New-DevelopmentToken.ps1 -Scope 'requests:read'
```

No Swagger, cole o valor de `$token` em Authorize, sem o prefixo Bearer. O auxiliar usa as mesmas variáveis de ambiente; se passar configuração diferente ao container, use os parâmetros `-Secret`, `-Issuer` e `-Audience` correspondentes. O script é uma ferramenta local e não implementa um fluxo OAuth nem deve ser usado para emitir tokens de produção.

Em comunicação entre sistemas, o consumidor deve obter o token de um servidor OAuth 2.0 pelo fluxo `client_credentials`, com escopos concedidos por cliente. Em produção, substituir o segredo compartilhado de desenvolvimento por validação assimétrica usando as chaves públicas JWKS do servidor de autorização, preservando a verificação de emissor, audiência e tempos. A rotação de chaves fica sob responsabilidade do servidor de autorização; a migração para JWKS ainda não está implementada.

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

`PENDING -> PROCESSING -> COMPLETED` ou `FAILED`. Uma falha temporária elegível para retry retorna a `PENDING`, com contador de tentativas e data da próxima tentativa persistidos. Não há setters livres para status.

### Integração e transações

O POST retorna após o commit, sem aguardar a chamada externa. O worker busca uma pendência por ciclo e a reserva com `FOR UPDATE SKIP LOCKED`, permitindo múltiplas instâncias sem reservar o mesmo registro. A reserva e a atualização final têm transações curtas; a chamada HTTP ocorre entre elas, sem transação de banco aberta. O índice parcial da migration V2 atende a busca por pendências elegíveis.

Como a pendência está no banco, uma queda após o POST e antes da reserva não perde o trabalho. Uma queda depois da reserva deixa o registro em `PROCESSING`: a recuperação automática desses registros ainda não está implementada. Em produção, reconciliar com o provedor antes de reenviar, usando a mesma chave de idempotência.

| Retorno do provedor | Resultado |
|---|---|
| 2xx | `COMPLETED`, sem retry |
| 400 e demais 4xx, exceto 408/429 | `FAILED`, sem retry |
| 408, 429, 5xx, timeout ou falha de transporte | Retry somente se a idempotência estiver habilitada; caso contrário, `FAILED` |
| Limite de tentativas atingido | `FAILED`, sem nova tentativa automática |

A chave `Idempotency-Key` é o UUID interno da solicitação e permanece igual em todas as tentativas. Esse header depende de suporte contratual do provedor; enviá-lo sozinho não garante idempotência. Por isso, `PROVIDER_IDEMPOTENCY_SUPPORTED` é `false` por padrão. Para um provedor que garanta deduplicação por essa chave, habilite a opção para permitir retries. O mock local apenas responde 204; ele não simula efeitos reais nem implementa deduplicação.

Timeout é um resultado incerto: `FAILED` significa que o serviço não confirmou a conclusão, não que o produto deixou de ser ativado. Antes de qualquer reenvio manual, consultar/reconciliar o resultado no provedor.

Configurações por variáveis de ambiente:

| Variável | Padrão |
|---|---|
| `PROVIDER_BASE_URL` | `http://localhost:8081` (no Compose: `http://provider:8080`) |
| `PROVIDER_CONNECT_TIMEOUT` | `2s` |
| `PROVIDER_READ_TIMEOUT` | `3s` |
| `PROVIDER_MAX_ATTEMPTS` | `3` tentativas totais |
| `PROVIDER_RETRY_DELAY` | `2s`, dobrando a cada falha elegível |
| `PROVIDER_IDEMPOTENCY_SUPPORTED` | `false` |
| `PROCESSING_ENABLED` | `true` |
| `PROCESSING_POLL_DELAY` | `1000` milissegundos entre ciclos |

Neste incremento, 429/503 seguem o backoff configurado; `Retry-After`, jitter e circuit breaker ficam como melhorias futuras.

O mock usa a [imagem oficial do WireMock](https://wiremock.org/docs/standalone/docker/), com configuração em `docker/wiremock/mappings/activate.json`. Ele exige POST em `/provider/v1/activate` com `Idempotency-Key` e retorna 204.

O cliente usa HTTP/1.1 explicitamente, evitando negociação de upgrade HTTP/2 na comunicação com o mock.

## Testes

```powershell
.\mvnw.cmd verify
```

Os testes de domínio e política de retry executam sem Spring. Os testes de integração usam MockMvc, a aplicação completa e PostgreSQL isolado via Testcontainers, com as migrations reais. Cobrem criação, validação, consulta, duplicidade, chamadas concorrentes, ausência de autenticação e geração OpenAPI a partir da interface. Para o provedor, um servidor HTTP local controlado pelo teste responde com sucesso, 400, 429, 500, 503 e atraso superior ao timeout. Os testes verificam status persistido, limite de tentativas, chave estável e ausência de transação durante a chamada HTTP. O worker agendado é desabilitado nos testes para que cada ciclo seja acionado de maneira determinística. Os testes funcionais da API simulam um JWT autorizado; `SecurityIntegrationTests` envia tokens realmente assinados pelo filtro Bearer e pelo decoder, cobrindo escopos, assinatura incorreta, emissor/audiência incorretos ou ausentes, expiração, ausência de expiração, nbf futuro, token malformado e token sem assinatura.

## Pontos pendentes e próximos incrementos

- Isolamento entre consumidores (BOLA): atualmente, um token de leitura pode consultar solicitações de outros consumidores se souber o ID. A evolução deve persistir o identificador do consumidor e restringir as consultas por esse identificador.
- Migração da chave HS256 de desenvolvimento para chaves públicas JWKS do servidor OAuth 2.0.
- Reconciliação de solicitações presas em PROCESSING, Retry-After e jitter.
- Credencial do provedor externo por secret manager, com rotação; o mock atual não exige autenticação.
- CI e melhorias do container.
- Correlação de logs e resposta ao cenário de análise de lentidão do desafio.

## Produção e melhorias futuras

A imagem é construída em múltiplos estágios. Banco e credenciais são configurados por variáveis de ambiente. Antes de produção, remover os segredos padrão de desenvolvimento, usar um provedor OAuth 2.0 com JWKS e fluxo client_credentials, restringir métricas à rede operacional, executar como usuário sem privilégios e configurar memória e desligamento gracioso. A recuperação de registros PROCESSING depende de reconciliação com o provedor; não reprocessar registros ambíguos sem essa confirmação.

## Uso de IA

Codex auxiliou na análise do enunciado, estrutura MVC, implementação inicial, documentação e testes.
