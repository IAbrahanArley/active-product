# Product Activation Service

API de solicitações de ativação, em Java 21 e Spring Boot.

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

## Decisões técnicas

Foi adotada uma estrutura MVC em camadas, com contratos HTTP e documentação na interface do controller, regras de negócio nos services e acesso ao banco nos repositories. Os DTOs são separados da entidade para manter o contrato da API independente da persistência. As seções abaixo detalham a estratégia de duplicidade, as transições de estado, o processamento assíncrono e as decisões de segurança.

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

### Credencial de acesso ao provedor

O mock não exige autenticação e o cliente HTTP atual ainda não envia credenciais ao provedor. Em um ambiente real, armazenaria a credencial em um gerenciador de segredos, com acesso restrito à identidade da aplicação, sem incluí-la na imagem, no repositório ou nos logs.

Se o provedor suportar OAuth 2.0, usaria `client_credentials` para obter um token com os escopos mínimos, mantido em memória até pouco antes de expirar. O segredo do cliente seria obtido do gerenciador de segredos. A rotação criaria uma nova versão, atualizaria as instâncias por recarga controlada ou substituição gradual e validaria uma chamada autenticada antes de revogar a versão antiga. Um período de sobreposição dependeria do suporte do provedor; sem esse suporte, a troca exigiria uma janela coordenada. Se o contrato usar API key, aplicaria o mesmo processo à chave enviada no header definido pelo provedor.

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

## Cenário de análise: lentidão em produção

O cenário informa aumento do tempo médio da API de 300 ms para 2,5 s, de algumas consultas de 50 ms para 1,2 s e do provedor de 400 ms para 3 s, com CPU em 45% e memória em 60%. Esses percentuais, isoladamente, não comprovam que a aplicação está saudável nem indicam saturação de CPU ou memória. Começaria delimitando o horário da regressão e comparando volume de chamadas, endpoints afetados, taxa de erros e mudanças recentes de código, infraestrutura ou configuração.

### O que investigaria primeiro

Separaria o tempo de espera do tempo efetivo de execução. Na API, verificaria percentis de latência (p50, p95 e p99), threads ocupadas e espera por conexão no pool do banco. No PostgreSQL, verificaria consultas lentas, planos de execução, locks, sessões ativas, I/O e índices. No provedor, separaria conexão de leitura e mediria timeouts e códigos de resposta.

Nesta implementação, a ativação é assíncrona e a chamada externa não mantém uma transação de banco aberta. Portanto, três segundos no provedor não deveriam ser somados diretamente ao tempo do POST. O efeito esperado seria aumento do tempo até a conclusão e da quantidade de pendências. Se o POST também ficar lento, investigaria o acesso ao banco e a concorrência por recursos compartilhados, em vez de atribuir automaticamente toda a regressão ao provedor.

### Como isolaria aplicação, banco e integração

| Área | Evidência que procuraria | Como verificaria |
|---|---|---|
| Aplicação | Espera por threads, conexões ou pausas de GC | Comparar requisições em andamento, thread dumps, métricas do pool e pausas da JVM durante a regressão |
| Banco | Consultas mais caras, bloqueios ou armazenamento lento | Consultas e sessões ativas, duração de locks e plano com EXPLAIN; usar ANALYZE apenas em ambiente e consultas controlados, pois executa a operação |
| Provedor | Aumento do tempo de conexão/leitura, 429, 5xx ou timeout | Medir cada tentativa e comparar com indicadores do provedor e uma chamada controlada a partir da mesma rede da aplicação |
| Rede/infraestrutura | Degradação comum ao banco e ao provedor | Correlacionar latência de rede, retransmissões, resolução DNS e eventos da plataforma no mesmo intervalo |

### Logs e métricas úteis

Usaria latência e taxa de erro por endpoint, conexões ativas/ociosas/pendentes do pool, duração e bloqueios das consultas, tempo e resultado das tentativas no provedor e contagem de solicitações por status. Para o processamento assíncrono, acompanharia também a idade da pendência mais antiga, o tempo entre criação e conclusão e a quantidade de registros parados em PROCESSING.

Os logs deveriam conter identificador de correlação, ID interno da solicitação, etapa, duração, número da tentativa e categoria do resultado, propagando o identificador até o provedor. Tokens, credenciais e corpos completos não seriam registrados. Um trace por fluxo ajudaria a atribuir o tempo a cada componente.

Atualmente, o projeto expõe Actuator/Prometheus e possui logs básicos de exceção. Correlação persistida para o worker, métricas próprias do provedor e das solicitações e tracing distribuído ainda não estão implementados. A disponibilidade do endpoint Prometheus, por si só, não significa que todas essas medições já existam.

### Melhorias que consideraria

As mudanças dependeriam da evidência: corrigir uma consulta ou índice se o plano estiver ruim; reduzir transações longas se houver locks; ajustar concorrência e pool dentro da capacidade do banco; ou negociar capacidade e limites do provedor. Aumentar o pool sem medir pode apenas transferir a saturação para o PostgreSQL.

No worker atual, cada instância processa uma solicitação por ciclo. Se as chamadas demorarem mais, sua capacidade diminui e a fila cresce. Consideraria paralelismo limitado ou mais instâncias após medir a capacidade do banco e do provedor, mantendo a reserva concorrente e a chave de idempotência. Para falhas transitórias, evoluiria o tratamento de Retry-After, jitter e, se justificável, circuit breaker. Também revisaria a frequência de consulta dos consumidores para evitar polling excessivo do GET.

### Hipótese de causa comum e confirmação

Uma hipótese é que a lentidão do provedor prolongue o processamento, levando os consumidores a consultar o status com maior frequência. Esse aumento de GETs pode pressionar o banco e o pool de conexões, elevando também a latência da API. Confirmaria a hipótese correlacionando a ordem dos eventos: primeiro aumento da latência externa, depois crescimento da idade das pendências e das consultas por solicitação, seguido de espera no pool e maior duração no banco. Uma comparação controlada com polling menos frequente ajudaria a avaliar o efeito. Se essa sequência não aparecer, descartaria a hipótese e verificaria outras causas, como degradação de rede ou I/O compartilhado.

### Como validaria a correção

Compararia períodos ou testes com volume e composição de tráfego semelhantes, usando p95/p99 da API, latência das consultas e do provedor, taxa de erro, espera no pool, vazão de ativações e idade das pendências. Publicaria a mudança gradualmente, com possibilidade de reversão, e acompanharia o comportamento por tempo suficiente para incluir um pico de carga. Além de reduzir a latência, verificaria que não houve crescimento contínuo da fila, perda de solicitações ou ativações duplicadas.

## Produção

O Compose deste repositório é destinado ao desenvolvimento: utiliza credenciais locais e um provedor simulado. Para executar a imagem em um ambiente de produção, usaria PostgreSQL externo com backups, um endpoint real do provedor e configurações fornecidas pela plataforma.

Um procedimento de build e execução, em PowerShell, seria:

```powershell
# Executar com Java 21 e Docker disponíveis.
.\mvnw.cmd verify
docker build -t product-activation-service:1.0.0 .

# O arquivo de ambiente deve existir fora do repositório e ter acesso restrito.
docker run --detach --name product-activation-service --env-file C:\deploy\product-activation.env --publish 127.0.0.1:8080:8080 --memory 512m --cpus 1 --env JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70.0 --stop-timeout 30 product-activation-service:1.0.0
```

Os limites de CPU, memória e heap do exemplo são um ponto de partida e devem ser dimensionados por teste de carga. A imagem atual não executa os testes no build Docker, por isso o verify aparece como etapa anterior. Em CI, publicaria uma imagem identificada pelo commit validado, em vez de reutilizar uma tag mutável.

O arquivo de ambiente externo deve conter os valores reais de:

| Configuração | Finalidade |
|---|---|
| DB_URL, DB_USERNAME, DB_PASSWORD | Conectar ao PostgreSQL do ambiente com usuário restrito às permissões necessárias |
| JWT_SECRET, JWT_ISSUER, JWT_AUDIENCE | Validar os tokens aceitos pela implementação HS256 atual, sem reutilizar o segredo de desenvolvimento |
| PROVIDER_BASE_URL | Endereço HTTPS do provedor real |
| PROVIDER_CONNECT_TIMEOUT, PROVIDER_READ_TIMEOUT | Limitar espera conforme o contrato e a latência observada |
| PROVIDER_IDEMPOTENCY_SUPPORTED | Habilitar retry apenas após confirmar a deduplicação por chave no contrato |
| PROVIDER_MAX_ATTEMPTS, PROVIDER_RETRY_DELAY | Definir a política de tentativas |
| CORS_ALLOWED_ORIGINS | Restringir origens se houver um consumidor em navegador |
| SPRINGDOC_API_DOCS_ENABLED=false, SPRINGDOC_SWAGGER_UI_ENABLED=false | Desabilitar documentação pública nesse ambiente |

O exemplo descreve a execução da imagem atual. A configuração de uma credencial para o provedor e a validação JWT por JWKS exigem implementação adicional; apenas criar variáveis de ambiente não habilita essas funcionalidades.

O Flyway aplica as migrations na inicialização e o Hibernate valida o schema. Planejaria mudanças compatíveis com versões coexistentes da aplicação, com backup e execução controlada das migrations. Se as permissões de alteração de schema forem separadas das permissões de execução, a plataforma deverá executar o Flyway como etapa de deploy.

Colocaria a aplicação atrás de um proxy ou gateway com HTTPS e limitaria o acesso às métricas à rede operacional. A publicação em 127.0.0.1 no exemplo pressupõe um proxy no mesmo host; em uma plataforma de containers, o roteamento deve ser configurado pela própria plataforma. Os endpoints `/actuator/health/liveness` e `/actuator/health/readiness` estão habilitados para integração com as sondas do ambiente. O Dockerfile atual ainda não inclui HEALTHCHECK nem usuário sem privilégios.

No desligamento, configuraria explicitamente a drenagem de requisições e a política para o worker antes de substituir instâncias. O stop-timeout do comando limita a espera do Docker, mas não implementa sozinho essa coordenação. Solicitações interrompidas em PROCESSING exigem reconciliação com o provedor antes de qualquer reenvio.

## Pontos pendentes

- Isolamento entre consumidores (BOLA): atualmente, um token de leitura pode consultar solicitações de outros consumidores se souber o ID. A evolução deve persistir o identificador do consumidor e restringir as consultas por esse identificador.
- Migração da chave HS256 de desenvolvimento para chaves públicas JWKS do servidor OAuth 2.0.
- Reconciliação de solicitações presas em PROCESSING, Retry-After e jitter.
- Autenticação do cliente do provedor e carregamento/renovação da credencial conforme a estratégia descrita acima.
- Logs de diagnóstico das falhas do provedor, com categoria e duração sem exposição de credenciais.
- Correlação propagada da API ao worker/provedor e métricas específicas de processamento.
- Teste específico de erro inesperado para verificar a resposta 500 sem detalhes internos.
- CI, permissão de execução do mvnw em Linux e validação a partir de um clone limpo.
- Usuário sem privilégios, health check da API e coordenação explícita de desligamento.
- Listagem paginada com filtros por status e customerId; definir índices a partir das consultas previstas.
- Limites de corpo e taxa de chamadas, testes de arquitetura e registros de decisão (ADRs).

## Melhorias futuras

Para operar como microsserviço em cloud, manteria a aplicação sem sessão local e escalaria as instâncias conforme a vazão e a idade das pendências. A unicidade global de externalId e a reserva no PostgreSQL permaneceriam como garantias compartilhadas. Segredos ficariam no serviço de segredos da plataforma, com acesso pela identidade da aplicação.

Se outros serviços precisarem ser avisados da conclusão, gravaria um evento em uma outbox na mesma transação que altera o status, com publicação posterior em fila ou tópico. Os consumidores tratariam entregas repetidas de forma idempotente. Essa publicação de eventos ainda não faz parte da implementação.

## Uso de IA

Codex auxiliou no planejamento, na estrutura MVC, implementação inicial e documentação.
