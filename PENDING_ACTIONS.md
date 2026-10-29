# Ações pendentes — AtivaFlow API

Revisão de `CHECKLIST.md` confrontada com código, testes, configurações, README e SPECs. O checklist original foi preservado. As referências numéricas abaixo agrupam tarefas repetidas; um checkbox antigo, marcado ou vazio, não foi tratado como prova de implementação.

**Prioridades:** P0 = requisito obrigatório segundo as SPECs ou validação do fluxo principal; P1 = diferencial previsto no checklist; P2 = evolução apenas documental. O HTML original não está no repositório: a conferência do enunciado foi limitada às transcrições em `specs/`.

## 1. Pendências para fechar o fluxo principal

### A00 — Corrigir a suíte que atualmente falha

- [x] **[P0] Corrigir a montagem da cadeia de filtros em `ResourceServerSecurityConfigurationUnitTests`.** A execução completa encontrou **15 erros nessa classe**, todos com `NullPointerException` em `SecurityContextHolderAwareRequestFilter`, pois `requestFactory` está nulo. O `ObjectPostProcessor` dessa fixture devolve os objetos sem inicializá-los; fixtures semelhantes que passam, como `StatelessResourceServerUnitTests`, usam `initializeBean`. Revisar o ciclo de inicialização e manter as verificações de segurança, sem desabilitar testes.
- [x] **[P0] Reexecutar a classe isolada e depois a suíte completa.** Comando direcionado: `./mvnw -B --no-transfer-progress -Dtest=ResourceServerSecurityConfigurationUnitTests test`.

**Aceite:** os 19 casos da classe e o `verify` completo passam. A evidência atual aponta para problema na fixture; não comprova uma falha equivalente na cadeia gerenciada pelo Spring na aplicação.

**Validação da correção (29/10/2026):** os 15 erros foram reproduzidos antes da alteração. O `ObjectPostProcessor` passou a usar `initializeBean`, preservando todos os casos e asserções. A classe isolada passou com 19 testes; `./mvnw -B --no-transfer-progress verify` terminou com `BUILD SUCCESS`: 949 testes em 58 relatórios, 0 falhas, 0 erros e 0 ignorados, incluindo integração com PostgreSQL via Testcontainers.

**Origem:** 00.12, 04.20, 05.18, 05.23, 07.27. Autenticação já foi implementada; a pendência imediata é corrigir a evidência quebrada.

### A01 — Empacotamento e execução reproduzível

- [ ] **[P0] Criar `Dockerfile` Java 21 e validar a imagem.** Não há Dockerfile. Adotar build multi-stage, runtime não-root e `.dockerignore` para completar os diferenciais associados sem uma segunda implementação.
- [ ] **[P1] Completar `docker-compose.yaml` com `app` e PostgreSQL.** Hoje existe somente `activation-provider-mock`. Adicionar volume do banco, healthchecks, dependências e URLs internas; manter a publicação do mock restrita ao loopback.
- [ ] **[P0] Documentar e demonstrar a execução completa.** Integrar o roteiro de chave/token local existente em `docs/local-jwt.md` ao container da API, incluindo montagem da chave pública e configuração de issuer/audience. Comprovar criação autenticada, consulta e ativação pelo mock. Verificar shutdown, restart e preservação dos dados/pendências sem apagar volumes inadvertidamente.

**Aceite:** imagem executável e procedimento reproduzível; com o Compose completo, `docker compose up --build` inicia os três serviços e permite demonstrar o fluxo autenticado.

**Origem:** 06.13–06.20, 07.09, 07.28. O graceful shutdown da aplicação já está configurado em `application.yaml`; falta validar seu comportamento no ambiente completo.

### A02 — Análise de desempenho no README

- [ ] **[P0] Responder às perguntas do cenário de lentidão.** O conteúdo está previsto na SPEC-06, mas não foi incorporado ao README. Usar API **300 ms → 2,5 s**, consultas **50 ms → 1,2 s**, provedor **400 ms → 3 s**, CPU **45%**, memória **60%**. Explicar investigação inicial, medição separada de aplicação/banco/provedor, logs e métricas úteis e melhorias condicionadas às evidências.
- [ ] **[P1] Completar hipótese de causa comum e validação pós-correção.** Diferenciar hipótese de diagnóstico confirmado, comparar carga equivalente e prever rollback. Explicar por que o provedor lento, sozinho, não deveria atrasar o POST assíncrono.

**Aceite:** seis respostas localizáveis no README, sem apresentar CPU/memória como saturadas nem atribuir causa sem evidência.

**Origem:** 06.21–06.28, 07.15. Trata-se de uma única seção, não de tarefas independentes por SPEC.

### A03 — Corrigir documentação que contradiz o código

- [ ] **[P0] Atualizar README e ADRs existentes.** Há afirmações de que worker, integração HTTP e escopos continuam pendentes, embora já existam. Exemplos: introdução do README, seção de criação, tabela de idempotência, seção do mock e final dos testes; introdução do ADR-0002 e trechos do ADR-0001. Revisar também as orientações de demonstração do mock para refletir o worker ativo.
- [ ] **[P0] Consolidar um roteiro curto de uso.** Reaproveitar exemplos de API, erros e JWT existentes; acrescentar arquitetura simples e conectar configuração, inicialização, token, POST e GET em uma sequência reproduzível. Distinguir funcionalidades implementadas, evidências de teste, limitações e evoluções.
- [ ] **[P1] Corrigir referências obsoletas do planejamento.** O POM e a SPEC-00 usam **Spring Boot 3.5.16**; o checklist ainda menciona 4.1.2 em 00.05, 00.09 e 05.01. As referências a `AGENTS.md` apontam para arquivo ausente; corrigir os textos conforme o processo real, sem criar esse arquivo apenas para satisfazer o plano.

**Aceite:** README e ADRs descrevem o comportamento atual e seus limites de maneira consistente. Limitações reais, como ausência de reconciliação automática, continuam explícitas.

**Origem:** 00.05, 00.09, 05.01, 07.08–07.14, 07.19, 07.24, FINAL.04. Os contratos, a matriz do provedor e os guias JWT já existem; precisam de consolidação e correção, não de reescrita integral.

### A04 — Completar a evidência do processamento integrado

- [ ] **[P0] Acrescentar testes do caminho integrado ainda não coberto em conjunto:** criação/commit → worker → HTTP real no WireMock → estado persistido/GET, para sucesso, HTTP 500 e read timeout. Comprovar que o POST responde sem aguardar o provedor, que a chamada externa ocorre fora de transação e que resultado ambíguo não autoriza reenvio. Reaproveitar fixtures e usar Awaitility onde houver polling.
- [ ] **[P1] Demonstrar retomada com o scheduler habilitado.** A configuração de teste desliga o worker; há testes de claim/lease no PostgreSQL e de orquestração com mocks, mas falta a evidência conjunta de retomada automática de trabalho persistido após nova instância/restart. Não é necessário criar outro mecanismo de recuperação: a query de claim já aceita leases vencidos seguros.

**Evidência da lacuna:** `ActivationRequestProviderRejectionTests` já combina PostgreSQL, WireMock e worker para 400 e 503; os testes de sucesso, 500 e timeout estão distribuídos entre cliente HTTP, persistência e orquestração. Não recriar as suítes existentes.

**Origem:** complemento de 03.20, 03.24, 05.10, 05.12, 05.14, 05.16. A política conservadora para resultados indeterminados já está implementada.

### A05 — Validação final das alterações

- [ ] **[P0] Executar `./mvnw -B --no-transfer-progress verify` após as mudanças** e conferir os relatórios, incluindo integração com PostgreSQL. Repetir cenários apenas quando alterações ou sinais de instabilidade justificarem.
- [ ] **[P0] Conferir o contrato com o HTML original disponível ao responsável**, além das SPECs, e realizar revisão humana dos fluxos críticos. Não é possível declarar essa conferência concluída apenas com os arquivos atuais.
- [ ] **[P0] Revisar o conteúdo versionado e o pacote final** para evitar inclusão de material JWT efêmero, `.env` e outros segredos. O utilitário local e o `.gitignore` já oferecem a base; isso é conferência final, não nova funcionalidade.

**Origem:** 05.23–05.25, 07.26–07.30 e FINAL.01–FINAL.05. O resultado da execução feita nesta revisão está na seção 4.

## 2. Diferenciais ainda incompletos

Estes itens não devem ser confundidos com ausência do núcleo funcional. Se algum diferencial sair do escopo, registrar a limitação e ajustar a alegação correspondente no checklist/README.

### A06 — Retry HTTP seguro e `Retry-After`

- [ ] **[P1] Resolver a lacuna contratual de 429/503.** `HttpActivationProviderClient` não produz `TransientFailure` para nenhuma resposta HTTP e não interpreta `Retry-After`: 429/503 permanecem indeterminados. Backoff, jitter, limite de tentativas e persistência de um prazo `Instant` já existem.
- [ ] **[P1] Se o diferencial de retry HTTP for mantido**, definir explicitamente um cenário do contrato/mock que garanta ausência de ativação, implementar sua classificação e interpretar `Retry-After` em segundos e data HTTP. Testar chamadas efetivas, prazo mínimo e limite de três tentativas. Preservar o bloqueio de timeout e respostas ambíguas.

**Alternativa de escopo:** manter a política conservadora atual e declarar o diferencial parcial. Não habilitar reenvio apenas porque houve 429/503 ou porque veio `Retry-After`.

**Origem:** 03.15–03.17, 03.24, 05.13. Os checks de conclusão atuais exageram a cobertura da integração HTTP. O caso 2xx também segue contrato explícito: 200/201/204 confirmam conclusão; 202 e demais respostas não contratadas permanecem indeterminadas.

### A07 — Limites efetivos de body e de tráfego

- [ ] **[P1] Implementar limite configurável de body com 413**, sem depender somente de `Content-Length` ou carregar um corpo excessivo inteiro antes de rejeitar. Limites dos campos via Bean Validation já existem e não substituem esse controle.
- [ ] **[P1] Implementar rate limiting configurável por consumidor/IP com 429 e `Retry-After`.** Documentar que o estado em memória é por instância e testar isolamento/concorrência sem contaminar os demais testes.
- [ ] **[P1] Cobrir os dois limites com Problem Details e testes de fronteira.** Os testes atuais de respostas 429 genéricas do handler não demonstram um rate limiter ativo.

**Origem:** 04.15–04.17, 04.21, 05.21. Esse 429 é da API; o tratamento do 429 do provedor pertence a A06.

### A08 — Probes, Prometheus e métricas de negócio

- [ ] **[P1] Configurar e testar liveness/readiness no ambiente local**, com exposição mínima e readiness sem depender da saúde do provedor. `/actuator/health` e sua liberação na segurança já existem; faltam configuração explícita das probes e testes dos grupos.
- [ ] **[P1] Expor `/actuator/prometheus` com política operacional definida.** Actuator e o registry Prometheus estão no POM; o endpoint não está habilitado na configuração de exposição. A segurança já exige autenticação nas demais rotas, mas ainda é preciso definir acesso de monitoramento e validar sua restrição.
- [ ] **[P1] Verificar métricas automáticas de HTTP/JVM/Hikari e completar as específicas:** latência/categoria/timeout/retry do provedor e quantidade de solicitações por estado, com tags de baixa cardinalidade e coleta sem query cara a cada scrape. Documentar o acesso.

**Origem:** parte restante de 04.18, 05.20, 06.01–06.02, 06.07–06.10, 07.16. CORS/CSRF já estão configurados e justificados no README; não precisam ser refeitos.

### A09 — Logs JSON e eventos faltantes

- [ ] **[P1] Habilitar saída JSON no profile apropriado e validar os campos.** Existem logs SLF4J com pares chave/valor, mas não há configuração de formato JSON. Reaproveitar a correlação persistida e o gerenciamento de MDC existentes.
- [ ] **[P1] Completar somente os eventos ausentes**, como criação, claim/recuperação e agendamento de retry, com identificação técnica e sem payloads/credenciais. Logs de tentativa do worker, chamada externa e erros já existem e têm testes.

**Origem:** parte restante de 06.03–06.04, 07.16. 06.05–06.06 já têm implementação e cobertura; não duplicar a correlação.

### A10 — Tracing opcional

- [ ] **[P1] Se mantido no escopo, adicionar Micrometer Tracing/OpenTelemetry**, instrumentando entrada HTTP, RestClient e trabalho assíncrono com propagação apropriada. Exportação OTLP configurável e desligada por padrão; inicialização sem collector.
- [ ] **[P1] Documentar e validar a configuração opcional.** `X-Correlation-ID` e MDC atuais não equivalem à implementação de tracing distribuído.

**Origem:** 06.11–06.12, 07.16. Não há bridge/exporter de tracing no POM nem configuração correspondente.

### A11 — Completar regras ArchUnit

- [ ] **[P1] Acrescentar as regras restantes previstas na SPEC-05**, especialmente Controller sem dependência de Repository e domínio sem dependência de infraestrutura, respeitando as camadas existentes.

**Evidência:** `ActivationProviderClientArchitectureTests` já protege o contrato do provedor; `AsyncProviderFailureIsolationUnitTests` já proíbe dependência de criação/erros em provedor/worker. Portanto, 05.19 está parcial, não ausente.

### A12 — Completar e comprovar CI

- [ ] **[P1] Acrescentar build da imagem depois de `verify`**, quando o Dockerfile existir. Arquivamento dos relatórios é opcional.
- [ ] **[P1] Conferir uma execução real no GitHub para a revisão final**, incluindo integração com Docker e build da imagem. A existência do YAML e testes locais não comprovam execução remota; o histórico do Actions não foi consultado nesta revisão.

**Origem:** 07.05–07.07. `.github/workflows/ci.yaml` já contém checkout, Java 21, cache Maven, `contents: read` e `verify`, para pushes/PRs em `main` e `develop`. Não criar outro workflow nem exigir Failsafe só por haver testes de integração: as classes atuais usam nomes `*Tests`, abrangidos pelo Surefire.

### A13 — Decisões e evoluções documentais restantes

- [ ] **[P1] Completar decisões reais nos ADRs existentes:** processamento assíncrono, duplicidade/idempotência e uso do WireMock já têm material distribuído entre README e dois ADRs. Consolidar contexto, alternativas e consequências; acrescentar a justificativa para não usar Circuit Breaker. Preservar a numeração e os links existentes, sem criar quatro documentos redundantes apenas para copiar os nomes do checklist.
- [ ] **[P1] Acrescentar seção “Uso de IA”**, descrevendo somente ferramentas e atividades efetivamente utilizadas; não alegar revisão humana já realizada sem confirmação.
- [ ] **[P2] Acrescentar seção curta de evolução operacional:** escala horizontal com constraint/claim/lease, configuração e segredos, eventos com Transactional Outbox e futuro fluxo imagem → registry → deploy/rollback/monitoramento. Reaproveitar `docs/resource-server.md` para origem/rotação de segredos. Sem necessidade de implementar cloud, broker ou CD.

**Origem:** 06.29–06.32, 07.17–07.18, 07.20–07.24. O histórico já contém commits por assunto; 07.25 é uma prática a manter, não funcionalidade pendente nem motivo para reescrever o histórico.

## 3. Itens desmarcados que já têm implementação e testes

Esta tabela explica o que foi retirado da lista de desenvolvimento pendente. Ela não substitui a validação após futuras alterações.

| Grupo do checklist | Evidência existente | Conclusão |
| --- | --- | --- |
| 04.19; 05.03, 05.05 | `ApiExceptionHandlerUnitTests`, `SafeErrorResponsesUnitTests`, `ErrorDisclosureProtectionUnitTests` e testes dos endpoints | 400/404/409/500 e proteção contra divulgação já cobertos. |
| 04.20; 05.17–05.18 | Testes em `src/test/java/com/rensilver/ativaflow_api/security/`, incluindo JWT realmente assinado, escopos, BOLA e contratos MVC | Não falta implementar novamente autenticação/autorização; corrigir a fixture que falha em A00. |
| 05.01–05.02 | `pom.xml`, `model/ActivationRequestTests`, testes da política e dos serviços | Ferramentas e testes unitários de domínio já existem; referência a Boot 4.1.2 é obsoleta. |
| 05.04 | `ListActivationRequestsEndpointTests`, `ActivationRequestQueryTests` | Filtros, paginação, ordem e isolamento já cobertos. |
| 05.06–05.09 | `TestcontainersConfiguration`, `FlywayMigrationTests`, `CreateActivationRequestEndpointTests`, testes de persistência/claim | PostgreSQL real, migrações, duplicidade, corrida, versão e claim já cobertos. |
| Parte de 05.10–05.16 | Testes de lease/attempt/outcome, WireMock do cliente, testes do worker e `ActivationRequestProviderRejectionTests` | Base de processamento e testes já existe; complementar apenas A04/A06. |
| 05.11, 05.15 | WireMock em portas dinâmicas e uso de Awaitility nas suítes existentes | Não falta criar essas infraestruturas de teste. |
| 05.22; 07.01–07.04 | POM com lifecycle Maven padrão e `.github/workflows/ci.yaml` | Execução de testes já configurada; CI precisa dos complementos de A12. |
| 05.26; parte de 07.08–07.14 | README e guias em `docs/` | API, estados, duplicidade, matriz do provedor, testes e JWT já documentados; restam lacunas/correções de A01–A03. |
| 06.05–06.06 | `CorrelationIdFilter`, V4, worker/cliente e testes de correlação/logs | Propagação entrada → persistência → worker → provedor e limpeza de MDC já existem. |
| Parte de 04.18, 06.03 e 06.15 | `SecurityConfiguration`, README, logs de worker/cliente e `server.shutdown: graceful` | Segurança básica do Actuator, justificativa CORS/CSRF, logs técnicos e configuração de shutdown já existem; completar A01/A08/A09. |

## 4. Evidência de execução desta revisão

Comando executado: `./mvnw -B --no-transfer-progress verify`, com acesso ao cache Maven e Docker para Testcontainers.

**Resultado: `BUILD FAILURE`, 949 testes, 0 falhas de asserção, 15 erros, 0 ignorados.** Os 58 relatórios em `target/surefire-reports/` totalizam 934 casos sem falha/erro e os 15 erros de `ResourceServerSecurityConfigurationUnitTests` descritos em A00. Houve execução de integração com PostgreSQL real; não se trata de suíte inteira pendente de configuração. Os relatórios são artefatos locais, não devem ser versionados.

**Reprodução isolada:** `./mvnw -B --no-transfer-progress -Dtest=ResourceServerSecurityConfigurationUnitTests test` também terminou com `BUILD FAILURE`: 19 casos, os mesmos 15 erros e nenhum ignorado. Nenhum código da aplicação ou dos testes foi alterado nesta revisão.

Não foram executados build de imagem, subida do Compose completo nem demonstração da API em containers: Dockerfile e os serviços `app`/PostgreSQL ainda não existem. Isso permanece como trabalho de A01, sem impedir a avaliação dos testes independentes do Compose.
