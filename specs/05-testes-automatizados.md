# SPEC-05 — Testes automatizados e qualidade de código

**Prioridade:** P0 (criação/validação, duplicidade, consultas e integração), P1 (concorrência real, timeout, domínio, segurança, arquitetura e estratégia documentada).  
**Dependências:** SPECs [01](01-api-e-dominio.md), [02](02-duplicidade-e-concorrencia.md), [03](03-integracao-provedor.md), [04](04-erros-e-seguranca.md).

## 1. Objetivo de qualidade

Os testes devem permitir **alterar o sistema com confiança**, não inflar cobertura. Provar invariantes de negócio e integração; não testar getters, construtores triviais ou detalhes internos sem efeito observável. Testes acompanham a implementação de cada etapa.

## 2. Matriz de testes (HTML + diferenciais)

| Camada                                 | Cenários                                                                                                          | Estratégia                                            |
| -------------------------------------- | ----------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| Domínio (obrigatório no comportamento) | Criar modelo válido, estados `PENDING → PROCESSING → COMPLETED/FAILED`, proibir transições inválidas/terminais    | JUnit 5 (Jupiter) puro, sem Spring                    |
| API                                    | POST válido 201/Location; payload inválido 400; GET existente 200; GET inexistente 404; listagem paginada/filtros | MockMvc / slice MVC compatível com Boot 3.5.x             |
| Duplicidade                            | Mesmo `externalId` mesmo conteúdo e conteúdo divergente; 200/409 conforme SPEC-02                                 | Integração real + assertions de count                 |
| **Concorrência — diferencial**         | Múltiplas criações simultâneas mesmo `externalId` → **exatamente 1 linha**                                        | Testcontainers PostgreSQL, barreira de início; sem H2 |
| Persistência                           | Migrações Flyway, constraints, índices relevantes, `@Version` e claim de worker                                   | Testcontainers PostgreSQL                             |
| Integração externa                     | HTTP 2xx, 400, 500, 429/503 `Retry-After`, número de tentativas                                                   | WireMock dinâmico por teste                           |
| **Timeout — diferencial**              | Atraso maior que timeout, resposta indeterminada, sem repetição insegura                                          | WireMock + Awaitility                                 |
| Assíncrono                             | POST não espera provedor; PENDING persistido; worker conclui; restart/lease expirado                              | Integração com polling/Awaitility, sem `sleep`        |
| Segurança — diferencial                | 401 sem JWT; 403 sem escopo; leitura/escrita; isolamento entre consumidores                                       | Spring Security Test + integração JWT real            |
| Erros                                  | Problem Details, ausência de dados sensíveis, correlação                                                          | MockMvc                                               |
| **Arquitetura — diferencial**          | Controller não usa repository; domínio não depende de infraestrutura; Client HTTP isolado                         | ArchUnit                                              |
| Operação                               | health/probes, métricas HTTP/provedor, limites 413/429                                                            | Testes de integração direcionados                     |

## 3. Regras para teste concorrente (mais importante)

1. Iniciar PostgreSQL com Testcontainers e aplicar **Flyway real**.
2. Criar threads/executores com transações independentes e barreira (`CountDownLatch`/`CyclicBarrier`) para começar ao mesmo tempo.
3. Fazer as requisições realmente disputarem a restrição `UNIQUE(external_id)`.
4. Verificar **exatamente uma** linha persistida; diferenciar resposta de primeira criação vs repetição/conflicto.
5. Testar disputa de claim no worker em cenários separados: no máximo um worker processa por lease.
6. Não escrever teste que “passa” porque mocks serializam os inserts ou porque as threads rodam sequencialmente.

## 4. Integração e async

- WireMock dos testes deve ter porta dinâmica/instância isolada, sem depender do container Compose.
- Simular delays superiores ao timeout de leitura e contabilizar as chamadas recebidas; verificar estado e indicador de reconciliação, sem assumir que timeout implica falha.
- Testar `Retry-After` como delta e data HTTP; testar backoff sem tempos rígidos que tornem suite flaky.
- Usar `Awaitility` com prazos razoáveis e predicados observáveis (banco/status); **não usar** `Thread.sleep` para sincronização.
- Testar recuperação de pendências após interrupção/worker reiniciado, mantendo política de idempotência.
- Testar que o caminho de criação não fica bloqueado pela latência do provedor e que transação não permanece aberta durante chamada externa (verificação de desenho e/ou teste direcionado).

## 5. Security e arquitetura

- Usar JWT simulado para autorização + ao menos testes de decoder com token assinado e chaves locais em tempo de teste.
- Testar `401` vs `403`, token expirado, consumidor A/B e escopos distintos.
- ArchUnit reflete **camadas existentes**, sem forçar package architecture artificial; regras devem falhar com violação real.
- Não iniciar contexto completo do Spring para testes unitários de domínio.

## 6. Maven e CI

- `./mvnw verify` deve executar unitários e integração. Configurar Surefire/Failsafe conforme nomenclatura (`*Test` vs `*IT`) e verificar nos relatórios que ambos realmente rodaram.
- CI GitHub Actions deve disponibilizar Docker para Testcontainers; testes independentes de AWS, serviços SaaS e execução manual do Compose.
- Separar fixtures reusáveis, usar nomes de teste que descrevam o comportamento; garantir isolamento e limpeza dos dados.
- Coverage (JaCoCo) opcional como sinal auxiliar, **não objetivo isolado**. Nunca mascarar falhas ou desabilitar testes para “verde”.
- Registre no README qual cenário é teste unitário, MVC, persistência ou integração externa — e por quê.

## 7. Critérios de aceite

- [ ] Testes obrigatórios do HTML passam e verificam saídas/efeitos reais.
- [ ] Concorrência Testcontainers demonstra 1 registro por `externalId` e nenhum bug de corrida oculto.
- [ ] Timeout/429/503 e backoff cobertos sem flakiness e sem chamadas HTTP desnecessárias.
- [ ] Domínio rejeita transições inválidas em teste puro sem Spring.
- [ ] Testes 401/403, BOLA, Problem Details e ArchUnit implementados.
- [ ] `./mvnw verify` roda tudo e falha em qualquer regressão; pipeline reproduz o mesmo comportamento.
- [ ] README explica estratégia e comandos de execução.
