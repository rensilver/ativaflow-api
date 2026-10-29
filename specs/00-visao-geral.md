# SPEC-00 — Visão geral e contrato de implementação

**Projeto:** AtivaFlow API  
**Referência:** HTML “Desafio Técnico Java Developer Pleno/Sênior”, fornecido no processo seletivo.  
**Natureza:** especificação de trabalho; itens de aceite **não** significam que o código já foi entregue.

## 1. Objetivo e ordem de prioridade

Desenvolver uma API REST que receba pedidos de ativação de produtos, persista solicitações, permita consultar seus estados e integre com um provedor externo simulado. A avaliação valoriza funcionamento, simplicidade, correção concorrente, testes, segurança e capacidade de explicar decisões.

- **P0 — Obrigatório:** todos os comportamentos classificados como *obrigatório* no HTML. Nenhum diferencial pode comprometer um P0.
- **P1 — Diferenciais implementáveis:** incluir os diferenciais descritos nas SPECs 01–07, com testes e documentação.
- **P2 — Diferenciais que o enunciado aceita descrever:** quando a implementação exigir serviços externos não fornecidos (por exemplo, deploy cloud, mensageria distribuída, collector de tracing), elaborar proposta técnica concreta no README, sem fingir que foi implementada.

**Regra de honestidade:** distinguir claramente `implementado`, `testado`, `documentado` e `pendente` no README; jamais afirmar um diferencial sem evidência. Adotar Spring Boot 3.5.x, alinhado ao diferencial **Spring Boot 3.x** citado no HTML; registrar no README a versão utilizada e as evidências de implementação e testes.

## 2. Stack e convenções adotadas

| Camada              | Decisão                                                                                                                                                 |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Linguagem           | Java 21; `record` para DTOs e `sealed` + pattern-matching `switch` em resultados de integração **quando agregarem clareza**                             |
| Framework           | Spring Boot 3.5.16, Spring MVC, Spring Data JPA / Hibernate                                                                                              |
| Build               | Maven + Maven Wrapper (`./mvnw verify`)                                                                                                                 |
| Persistência        | PostgreSQL; Flyway com SQL versionado; JPA com `ddl-auto=validate`                                                                                      |
| Mapeamento          | MapStruct em conversões objetivas; política rigorosa para campos não mapeados; nunca delegar decisões de domínio ao mapper                              |
| Lombok              | Uso seletivo: `@RequiredArgsConstructor`, `@Slf4j`, opcional `@Getter`; **evitar `@Data` em entidades JPA**, `@SneakyThrows` e setters livres de estado |
| Comunicação externa | `RestClient` com interface `ActivationProviderClient` e timeouts configuráveis                                                                          |
| Provedor de teste   | WireMock standalone, serviço Docker `activation-provider-mock`, diretório `infra/wiremock/`                                                             |
| Segurança           | Spring Security OAuth 2.0 Resource Server / JWT; fluxo M2M Client Credentials documentado; validação local sem servidor externo                         |
| Testes              | JUnit 5 (Jupiter), Mockito, AssertJ, MockMvc, Testcontainers/PostgreSQL, WireMock, Awaitility, Spring Security Test, ArchUnit                           |
| Operação            | Dockerfile multi-stage, Docker Compose, Actuator, Micrometer/Prometheus, logs JSON, correlação; tracing OpenTelemetry configurável                      |
| CI                  | GitHub Actions: build, testes e validação da imagem; **não** inferir obrigação de deploy AWS                                                            |
| Configuração        | `application.yaml` + profiles mínimos + `@ConfigurationProperties`; segredos por ambiente                                                                |

Validar nomes corretos de starters e versões transitivas para **Spring Boot 3.5.x** antes de editar o `pom.xml`. Configurar annotation processors de Lombok/MapStruct (incluindo `lombok-mapstruct-binding` quando necessário).

Adotar JUnit 5 (Jupiter), compatível com Spring Framework 6.2 utilizado no Spring Boot 3.5.x, mantendo as versões de Jupiter e Platform gerenciadas pelo BOM do Spring Boot. Usar `spring-boot-starter-test` e `spring-security-test`, sem sobrescrever as versões gerenciadas de JUnit ou Spring Security.

## 3. Contrato do desafio (fonte de verdade)

- `POST /api/v1/requests` cria solicitação com `externalId`, `customerId`, `productCode`.
- `GET /api/v1/requests/{id}` consulta solicitação por UUID.
- Diferencial: `GET /api/v1/requests?status=&customerId=&page=&size=` com paginação e filtros.
- Dados persistidos: `id`, `externalId`, `customerId`, `productCode`, `status`, `createdAt`, `updatedAt` (além de metadados internos devidamente justificados).
- Estados **exclusivos**: `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`.
- Uma única solicitação por `externalId`, inclusive sob concorrência; o contrato para repetição está na SPEC-02.
- Integração externa: `POST /provider/v1/activate`, com matriz de sucesso, 400, 500, timeout e diferenciais 429/503 + `Retry-After`.
- Erros padronizados, segurança entre sistemas, testes e cenário de lentidão descrito no HTML são parte do escopo.

Não inventar campos obrigatórios no payload público, endpoints ou contrato de resposta do provedor. Qualquer suposição deve ser documentada antes da implementação e isolada na integração.

## 4. Decisões transversais

1. **Assíncrono:** `POST` persiste a solicitação em `PENDING` e responde **201 Created + Location** sem aguardar o provedor; capturar a resposta no instante da criação (não prometer que `GET` ainda mostrará `PENDING`).
2. **Entrega confiável:** persistir trabalho pendente na mesma transação da criação e buscar pendências com worker/scheduler após o commit. Não depender apenas de um `@Async` em memória.
3. **Estados:** `PENDING → PROCESSING → COMPLETED|FAILED`; tentativas aguardando backoff continuam em `PROCESSING`. Estados terminais não retornam ao processamento.
4. **Concorrência:** constraint `UNIQUE(external_id)` no PostgreSQL; transições protegidas por lock otimista e/ou atualização condicional; claim de worker atômico entre instâncias.
5. **Ambiguidade:** timeout **não confirma** falha externa. Guardar o caráter indeterminado em metadados internos; não marcar `FAILED` como se soubesse que não houve ativação e não repetir operação insegura sem suporte comprovado a idempotência/reconciliação.
6. **Retry:** até **3 tentativas totais** onde comprovadamente seguro, com backoff exponencial, jitter e `Retry-After`. Sem threads dormindo nem transação de banco aberta durante HTTP.
7. **Resiliência proporcional:** sem Circuit Breaker inicialmente; justificar em ADR. Nenhuma dependência extra de Kafka/Redis/RabbitMQ/Keycloak/AWS para cumprir o take-home.
8. **Segurança do consumidor:** JWT com escopos de leitura e escrita, identidade do chamador e proteção BOLA; o mock do provedor não é um emissor JWT.
9. **Ambiente local:** três serviços no Compose (`app`, `postgres`, `activation-provider-mock`). Testes automatizados independem do Compose manual.

## 5. Plano incremental

| Etapa | Arquivo                                                              | Resultado verificável                        |
| ----- | -------------------------------------------------------------------- | -------------------------------------------- |
| 1     | [01-api-e-dominio.md](01-api-e-dominio.md)                           | Endpoints, DTOs, domínio e Flyway            |
| 2     | [02-duplicidade-e-concorrencia.md](02-duplicidade-e-concorrencia.md) | Duplicidade e escrita concorrente corretas   |
| 3     | [03-integracao-provedor.md](03-integracao-provedor.md)               | Processamento e simulações HTTP              |
| 4     | [04-erros-e-seguranca.md](04-erros-e-seguranca.md)                   | Erros RFC 9457 + JWT + escopos               |
| 5     | [05-testes-automatizados.md](05-testes-automatizados.md)             | Evidências de qualidade e regressão          |
| 6     | [06-observabilidade-e-producao.md](06-observabilidade-e-producao.md) | Observabilidade, Docker e resposta à análise |
| 7     | [07-ci-e-entrega.md](07-ci-e-entrega.md)                             | CI, README, ADRs e pacote para avaliação     |

Testes devem acompanhar cada etapa; a SPEC-05 consolida e complementa as verificações.

## 6. Definição de pronto global

- [ ] Todos os requisitos obrigatórios do HTML demonstráveis por API, testes ou documentação (quando o próprio HTML permitir descrição).
- [ ] Diferenciais selecionados implementados ou explicitamente documentados com status honesto.
- [ ] `./mvnw verify` executa testes unitários e de integração, não apenas compila.
- [ ] `docker compose up --build` executa API, PostgreSQL e mock, sem passos manuais ocultos.
- [ ] README permite reprodução após clone limpo, demonstra cenários do provedor e responde ao incidente de desempenho.
- [ ] `docs/adr/` registra decisões **efetivas**, alternativas e trade-offs.
- [ ] Sem segredos no Git, sem dados sensíveis nos logs, sem dependências de cloud no CI.
- [ ] Code review humano final: comportamento, concorrência, transações, SQL, segurança e testes.
