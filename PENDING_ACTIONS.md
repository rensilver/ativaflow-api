# Ações pendentes — AtivaFlow API

Revisão de `CHECKLIST.md` confrontada com código, testes, configurações, README e SPECs. O checklist usado na revisão original não está disponível neste checkout; as referências numéricas abaixo preservam sua rastreabilidade e agrupam tarefas repetidas. Um checkbox antigo, marcado ou vazio, não foi tratado como prova de implementação.

**Prioridades:** P0 = requisito obrigatório segundo as SPECs ou validação do fluxo principal; P1 = diferencial previsto no checklist; P2 = evolução apenas documental. O HTML original não está no repositório: a conferência do enunciado foi limitada às transcrições em `specs/`.

## 1. Pendências para fechar o fluxo principal

### A00 — Corrigir a suíte que atualmente falha

- [x] **[P0] Corrigir a montagem da cadeia de filtros em `ResourceServerSecurityConfigurationUnitTests`.** A execução completa encontrou **15 erros nessa classe**, todos com `NullPointerException` em `SecurityContextHolderAwareRequestFilter`, pois `requestFactory` está nulo. O `ObjectPostProcessor` dessa fixture devolve os objetos sem inicializá-los; fixtures semelhantes que passam, como `StatelessResourceServerUnitTests`, usam `initializeBean`. Revisar o ciclo de inicialização e manter as verificações de segurança, sem desabilitar testes.
- [x] **[P0] Reexecutar a classe isolada e depois a suíte completa.** Comando direcionado: `./mvnw -B --no-transfer-progress -Dtest=ResourceServerSecurityConfigurationUnitTests test`.

**Aceite:** os 19 casos da classe e o `verify` completo passam. A evidência atual aponta para problema na fixture; não comprova uma falha equivalente na cadeia gerenciada pelo Spring na aplicação.

**Validação da correção (29/10/2026):** os 15 erros foram reproduzidos antes da alteração. O `ObjectPostProcessor` passou a usar `initializeBean`, preservando todos os casos e asserções. A classe isolada passou com 19 testes; `./mvnw -B --no-transfer-progress verify` terminou com `BUILD SUCCESS`: 949 testes em 58 relatórios, 0 falhas, 0 erros e 0 ignorados, incluindo integração com PostgreSQL via Testcontainers.

**Origem:** 00.12, 04.20, 05.18, 05.23, 07.27. Autenticação já foi implementada; a pendência imediata é corrigir a evidência quebrada.

### A01 — Empacotamento e execução reproduzível

- [x] **[P0] Criar `Dockerfile` Java 21 e validar a imagem.** Implementado build multi-stage com Maven Wrapper/JDK 21, runtime JRE 21 não-root (`10001:10001`) e `.dockerignore` com contexto restrito. Build e execução documentados em [Imagem Docker Java 21](docs/container-image.md).
- [x] **[P1] Completar `docker-compose.yaml` com `app` e PostgreSQL.** Implementados volume persistente do banco, healthchecks dos três serviços, espera por dependências saudáveis e URLs internas. API e mock publicam somente em loopback; PostgreSQL não publica porta. Chave pública JWT montada somente para leitura; preparo documentado em [Ambiente completo com Docker Compose](docs/container-image.md#ambiente-completo-com-docker-compose).
- [x] **[P0] Documentar e demonstrar a execução completa.** Roteiro integrado ao utilitário JWT em [Demonstração autenticada e retomada de pendências](docs/container-image.md#demonstração-autenticada-e-retomada-de-pendências), com chave pública somente leitura, issuer/audience, POST/GET autenticados e confirmação no WireMock. Shutdown, restart e retomada de `PENDING` após recriação dos três serviços validados mantendo o volume.

**Validação da imagem (29/10/2026):** `clean verify` passou com 949 testes, 0 falhas, 0 erros e 0 ignorados. BuildKit validou o Dockerfile sem avisos e construiu `ativaflow-api:local`. O smoke test em Linux/amd64 confirmou Java 21.0.13, usuário não-root, JAR somente leitura, ausência de Maven/JDK no runtime, health `UP`, HTTP 401 sem token e GET 200 com JWT RS256. Com PostgreSQL e WireMock isolados, o POST retornou 201 e o worker concluiu a solicitação em `COMPLETED`. A API executou com filesystem somente leitura, `cap-drop ALL` e 512 MiB; SIGTERM concluiu o shutdown gracioso (exit 143, sem OOM). Containers, rede, volumes de teste e material JWT temporário foram removidos. Compose completo e validação de restart/preservação continuam nos demais itens de A01.

**Aceite:** imagem executável e procedimento reproduzível; com o Compose completo, `docker compose up --build` inicia os três serviços e permite demonstrar o fluxo autenticado.

**Validação do Compose (29/10/2026):** `docker compose config --quiet` e verificações de senha obrigatória, usuário compartilhado, porta externa customizada e URLs internas passaram. Em projeto isolado, `up --build -d --wait` iniciou os três serviços com health `healthy`; API não-root, chave pública somente leitura, health HTTP 200 e rota protegida sem token HTTP 401. POST autenticado retornou 201; GET confirmou `COMPLETED` e o WireMock registrou a chamada. Após `down` sem remoção do volume e nova subida, o registro permaneceu disponível. `./mvnw -B --no-transfer-progress clean verify` passou com 949 testes, 0 falhas, erros ou ignorados. Recursos e material JWT de teste foram removidos. O próximo item permanece pendente para consolidar a demonstração completa e verificar retomada de trabalho ainda pendente após restart.

**Validação complementar do roteiro completo (29/10/2026):** executados os blocos Bash documentados em projeto Compose isolado, com API em `127.0.0.1:18080` e mock em `127.0.0.1:8081`. Build/subida com três serviços saudáveis; health 200, acesso sem token 401, criação autenticada 201 e conclusão pelo mock. Com worker temporariamente desabilitado, uma segunda criação permaneceu `PENDING`, sem chamada ao provedor. Após shutdown gracioso, `down` sem remover o volume e nova subida sem override, o scheduler concluiu essa pendência automaticamente, preservando o registro anterior. Novo restart da API manteve ambos em `COMPLETED`, sem reenvio; PostgreSQL confirmou uma tentativa por solicitação. Shutdown com log `Graceful shutdown complete`, exit 143 e `OOMKilled=false`. O override usa arquivo `.env.*` ignorado, acessível ao Docker via Snap. `clean verify` passou novamente: 949 testes em 58 relatórios, sem falhas, erros ou ignorados. Recursos exclusivamente de teste e material JWT foram removidos após a comprovação. A demonstração cobre pendência sem tentativa externa; recuperação de resultado ambíguo continua dependente de reconciliação.

**Origem:** 06.13–06.20, 07.09, 07.28. O graceful shutdown da aplicação já está configurado em `application.yaml`; falta validar seu comportamento no ambiente completo.

### A02 — Análise de desempenho no README

- [x] **[P0] Responder às perguntas do cenário de lentidão.** Incorporadas as seis respostas à seção [Análise de desempenho: cenário de lentidão](README.md#análise-de-desempenho-cenário-de-lentidão), com API **300 ms → 2,5 s**, consultas **50 ms → 1,2 s**, provedor **400 ms → 3 s**, CPU **45%**, memória **60%**. Inclui investigação inicial, medição separada de aplicação/banco/provedor, logs e métricas úteis e melhorias condicionadas às evidências.
- [x] **[P1] Completar hipótese de causa comum e validação pós-correção.** Documentadas hipóteses verificáveis de contenção compartilhada, distinção entre hipótese e diagnóstico, comparação sob carga equivalente e gatilhos de rollback. A seção explica por que o provedor lento, sozinho, não deveria atrasar o POST assíncrono.

**Aceite:** seis respostas localizáveis no README, sem apresentar CPU/memória como saturadas nem atribuir causa sem evidência.

**Validação documental (29/10/2026):** conferidas as seis respostas contra a SPEC-06, as fronteiras transacionais de criação/worker, os campos dos logs e a configuração atual de observabilidade. Verificação automatizada confirmou numeração, indicadores exatos, tópicos de aceite, link local/âncora e blocos de código balanceados; `git diff --check` sem erros. As recomendações têm referências oficiais do Spring Boot 3.5 e PostgreSQL 17. Alterações somente em Markdown; suíte Maven e testes de carga não executados nesta tarefa, sem alegar reprodução ou correção de incidente.

**Origem:** 06.21–06.28, 07.15. Trata-se de uma única seção, não de tarefas independentes por SPEC.

### A03 — Corrigir documentação que contradiz o código

- [x] **[P0] Atualizar README e ADRs existentes.** Corrigidas as afirmações antigas sobre worker, integração HTTP e escopos; os documentos descrevem o scheduler ativo, as fronteiras transacionais, a classificação conservadora e as limitações reais. A demonstração distingue chamada direta ao mock de processamento pela API e explica o override necessário para desligar o worker no Compose.
- [x] **[P0] Consolidar um roteiro curto de uso.** Incluídos arquitetura simples, quadro de implementação/evidências/limites/evoluções e [roteiro curto](README.md#roteiro-curto-de-uso) com configuração, inicialização, JWT, POST/GET e limpeza, reutilizando os contratos e guias existentes.
- [x] **[P1] Corrigir referências obsoletas do planejamento.** Confirmado **Spring Boot 3.5.16** no POM, README e SPEC-00. A SPEC-07 usa os nomes reais dos dois ADRs e o processo com `specs/` e `PENDING_ACTIONS.md`, sem depender de arquivo de instruções ausente. `CHECKLIST.md` não existe neste checkout; suas referências históricas 00.05, 00.09 e 05.01 não puderam ser editadas e o arquivo não foi recriado.

**Aceite:** README e ADRs descrevem o comportamento atual e seus limites de maneira consistente. Limitações reais, como ausência de reconciliação automática, continuam explícitas.

**Validação documental (29/10/2026):** conferidos worker, handler, cliente HTTP, configuração de segurança, Compose, migrações e testes referenciados. Verificação automatizada dos cinco documentos alterados: 40 links locais/âncoras válidos, 30 blocos Bash aprovados por `bash -n`, fences balanceados e 35 referências a classes de teste existentes; versão 3.5.16 consistente com POM e SPEC-00. `git diff --check` sem erros. Alterações somente em Markdown; suíte Maven e demonstração completa não executadas nesta tarefa. A tentativa adicional de executar geração JWT foi impedida pela proteção contra Git ao detectar `.git` de infraestrutura em `/tmp`; o Docker via Snap também recusou iniciar no sandbox. Não há nova evidência de execução do roteiro ou do Compose; permanece a validação anterior de A01.

**Origem:** 00.05, 00.09, 05.01, 07.08–07.14, 07.19, 07.24, FINAL.04. Os contratos, a matriz do provedor e os guias JWT já existem; precisam de consolidação e correção, não de reescrita integral.

### A04 — Completar a evidência do processamento integrado

- [x] **[P0] Acrescentar testes do caminho integrado ainda não coberto em conjunto:** `ActivationRequestProcessingIntegrationTests` combina criação/commit, scheduler/worker ativos, HTTP real no WireMock e estado persistido/GET para sucesso (`204`), HTTP 500 e read timeout. Uma barreira no transporte comprova resposta do POST sem aguardar o envio; outra conexão confirma os commits e o interceptor verifica ausência de transação na chamada externa. Awaitility acompanha a persistência e verifica ausência de reenvio, payload e correlação.
- [x] **[P1] Demonstrar retomada com o scheduler habilitado.** `ActivationRequestSchedulerRestartTests` fecha e recria contextos completos da aplicação mantendo o PostgreSQL. A nova instância retoma automaticamente um `PENDING` e um claim vencido sem tentativa preparada; o registro preparado para envio permanece bloqueado para reconciliação. Uma terceira instância preserva os estados e não faz novas chamadas. Reutilizada a fixture PostgreSQL existente, sem outro mecanismo de recuperação.

**Cobertura complementar:** `ActivationRequestProviderRejectionTests` continua cobrindo 400 e 503, sem duplicação. As novas suítes habilitam explicitamente o scheduler, mantendo o padrão desligado do profile `test` nas demais classes. Identidades HTTP são simuladas pelo Spring Security; a validação criptográfica de JWT permanece nas suítes de segurança.

**Validação (29/10/2026):** execução direcionada das duas novas classes passou com 4 casos. `./mvnw -B --no-transfer-progress verify` terminou com `BUILD SUCCESS`: 953 testes em 60 relatórios, 0 falhas, 0 erros e 0 ignorados, incluindo integração com PostgreSQL real. README atualizado com cobertura e comando direcionado; `git diff --check` sem erros. Nenhum código de produção alterado. A retomada automatizada recria contextos da aplicação na mesma JVM; a demonstração com recriação de containers continua registrada em A01.

**Origem:** complemento de 03.20, 03.24, 05.10, 05.12, 05.14, 05.16. A política conservadora para resultados indeterminados já está implementada.

### A05 — Validação final das alterações

- [x] **[P0] Executar `./mvnw -B --no-transfer-progress verify` após as mudanças** e conferir os relatórios, incluindo integração com PostgreSQL. Execução de A05 aprovada com 953 testes em 60 relatórios, sem falhas, erros ou ignorados; evidências na seção 4. Repetir cenários apenas quando alterações ou sinais de instabilidade justificarem.
- [ ] **[P0] Conferir o contrato com o HTML original disponível ao responsável**, além das SPECs, e realizar revisão humana dos fluxos críticos. Não é possível declarar essa conferência concluída apenas com os arquivos atuais.
- [x] **[P0] Revisar o conteúdo versionado e o pacote final** para evitar inclusão de material JWT efêmero, `.env` e outros segredos. Conferidos os 177 arquivos versionados e o JAR gerado pelo `verify`, além das regras de ignore e da configuração por ambiente; escopo e limites da inspeção na seção 4.

**Dependência externa:** A05 permanece parcialmente concluída. Nenhum HTML original foi localizado no checkout e não há confirmação de revisão humana de domínio, concorrência e segurança. O responsável deve confrontar o enunciado original com as SPECs e revisar os fluxos críticos antes de marcar o segundo item. A validação automatizada não substitui essas duas conferências.

**Origem:** 05.23–05.25, 07.26–07.30 e FINAL.01–FINAL.05. O resultado mais recente e o histórico da revisão estão na seção 4.

## 2. Diferenciais ainda incompletos

Estes itens não devem ser confundidos com ausência do núcleo funcional. Se algum diferencial sair do escopo, registrar a limitação e ajustar a alegação correspondente no checklist/README.

### A06 — Retry HTTP seguro e `Retry-After`

- [x] **[P1] Resolver a lacuna contratual de 429/503.** Adicionado contrato tipado opt-in `rejected-before-activation`, selecionado por `ACTIVATION_PROVIDER_RETRY_CONTRACT`; exige rejeição antes de ativar ou enfileirar trabalho remoto. O padrão `none` mantém `429/503` indeterminados. URL, status e `Retry-After` não selecionam a garantia automaticamente; configuração desconhecida impede a inicialização.
- [x] **[P1] Manter o diferencial de retry HTTP com contrato seguro e testes.** Cliente produz `TransientFailure` somente no contrato selecionado e interpreta `Retry-After` em segundos/data HTTP. Reutilizados backoff, jitter, limite total e persistência existentes. Cenário opcional em `infra/wiremock/scenarios/`, fora dos mappings padrão; carga e restauração documentadas no README e sem alegar garantia de provedor real. Timeout, `500` e demais respostas ambíguas continuam bloqueados.

**Limites:** header ausente, malformado, zero ou data passada preserva o backoff positivo. Prazo numérico com overflow, instante posterior a `9999-12-31T23:59:59Z` ou headers repetidos exigem análise operacional (`PROVIDER_RETRY_AFTER_UNSUPPORTED`), sem truncamento nem reenvio antecipado. O contrato seguro só deve ser selecionado para um provedor real após confirmar a garantia de não ativação. Reconciliação automática continua fora desta tarefa.

**Validação (29/10/2026):** os testes novos reproduziram a ausência da classificação/interpretação antes da implementação; o teste da variável de ambiente também falhou antes de incluir seu vínculo no YAML. `HttpActivationProviderRetryTests` passou com 24 casos de contrato, parsing, limites e preservação das demais classificações. `ActivationRequestHttpRetryTests` passou com 13 casos usando PostgreSQL e HTTP reais: cenário JSON reutilizado, prazo mínimo persistido, bloqueio de outro worker antes do prazo, backoff positivo, três chamadas efetivas até `FAILED`, sucesso após retry e bloqueio em timeout/overflow. Scheduler desligado nessa suíte; Awaitility conduz o worker após os prazos reais, sem adiantar agendamentos por SQL. `./mvnw -B --no-transfer-progress clean verify` terminou com `BUILD SUCCESS`: 990 testes em 62 relatórios XML conferidos, 0 falhas, erros ou ignorados. Compose validado nos dois modos e variável propagada corretamente; Markdown, links locais, blocos Bash e JSON verificados, com `git diff --check` sem erros. Não houve nova demonstração completa em containers nem validação de um provedor real.

**Origem:** 03.15–03.17, 03.24, 05.13. A cobertura HTTP agora inclui o contrato seguro opt-in e o comportamento conservador padrão. O caso 2xx também segue contrato explícito: 200/201/204 confirmam conclusão; 202 e demais respostas não contratadas permanecem indeterminadas.

### A07 — Limites efetivos de body e de tráfego

- [x] **[P1] Implementar limite configurável de body com 413.** `ApiBodyLimitFilter` rejeita tamanho declarado excessivo sem leitura; nos demais casos, lê no máximo o teto mais um byte antes do MVC, cobrindo chunked, tamanho ausente/subdeclarado, UTF-8 e sufixo após o JSON. O corpo aceito fica em buffer limitado. Padrão de 16 KiB, configurável até 1 MiB.
- [x] **[P1] Implementar rate limiting configurável por consumidor/IP com 429 e `Retry-After`.** Janela fixa, relógio monotônico e admissão atômica; identidade autenticada vem do JWT validado, com fallback para IP da conexão. Estado limitado por quantidade de identidades, sem expulsar cotas ativas; headers de identidade/forwarding não selecionam a chave. Estado por instância e limitações de proxy/escala documentados no [README](README.md#limites-de-body-e-tráfego).
- [x] **[P1] Cobrir os dois limites com Problem Details e testes de fronteira.** Cinco suítes com 34 casos, incluindo HTTP real no Tomcat e JWT RS256 assinado, fronteiras 255/256/257 bytes, leitura limitada de fluxo sem fim, concorrência, isolamento por consumidor/IP/instância, expiração, capacidade, desativação e validação da configuração. Caminhos codificados usam a interpretação decodificada do servlet e não escapam dos controles.

**Configuração e limites:** cinco variáveis `API_MAX_BODY_BYTES` e `API_RATE_LIMIT_*` vinculadas ao YAML e propagadas pelo Compose; valores inválidos impedem a inicialização. Filtros aplicados às rotas `/api/`, preservando health, Actuator e documentação. Body é verificado antes da autenticação; o rate limiter executa depois da autenticação Bearer e antes da autorização, sem contabilizar JWT inválido rejeitado antes dele. Restart renova cotas; múltiplas instâncias exigem gateway ou estado distribuído. O profile `test` desabilita rate limiting nos demais fluxos, enquanto as novas suítes usam estado próprio e habilitação explícita.

**Validação (29/10/2026):** reproduzida a ausência dos controles antes de implementar; teste de variáveis também falhou antes do vínculo no YAML. Quatro regressões HTTP reproduziram o desvio por caminhos codificados antes de corrigi-lo. As cinco suítes passaram com 34 casos. A execução final de `MAVEN_OPTS=-Xmx256m ./mvnw -B --no-transfer-progress -Djava.io.tmpdir=/var/tmp -DargLine=-Xmx512m -Dspring.test.context.cache.maxSize=2 clean verify` terminou com **`BUILD SUCCESS`: 1024 testes em 67 relatórios XML, 0 falhas, erros ou ignorados**, incluindo integração com PostgreSQL real. O diretório temporário evita o `.git` de infraestrutura em `/tmp`; os limites de heap/cache evitam a pressão de memória que levou à interrupção da tentativa anterior. A execução final recompilou tudo sem edição de fontes Java durante os testes, evitando a interferência de bytecode da IDE observada em uma tentativa inicial. Compose conferido com valores padrão e personalizados, Markdown/comando Bash e padrões de segredos nos arquivos alterados verificados, com `git diff --check` sem erros. Sem nova subida de serviços Compose ou build da imagem nesta tarefa.

**Origem:** 04.15–04.17, 04.21, 05.21. Esse 429 é da API; o tratamento do 429 do provedor pertence a A06.

### A08 — Probes, Prometheus e métricas de negócio

- [x] **[P1] Configurar e testar liveness/readiness no ambiente local.** Probes habilitadas explicitamente: liveness usa somente `livenessState`; readiness usa `readinessState,db`, sem consultar o provedor. Apenas `health` exposto pelo Actuator, sem componentes ou detalhes internos. Healthcheck do Compose usa readiness; critérios e comandos no [README](README.md#probes-de-saúde).
- [ ] **[P1] Expor `/actuator/prometheus` com política operacional definida.** Actuator e o registry Prometheus estão no POM; o endpoint não está habilitado na configuração de exposição. A segurança já exige autenticação nas demais rotas, mas ainda é preciso definir acesso de monitoramento e validar sua restrição.
- [ ] **[P1] Verificar métricas automáticas de HTTP/JVM/Hikari e completar as específicas:** latência/categoria/timeout/retry do provedor e quantidade de solicitações por estado, com tags de baixa cardinalidade e coleta sem query cara a cada scrape. Documentar o acesso.

**Validação das probes (29/10/2026):** reproduzidos cinco casos com HTTP 404 antes da configuração. `HealthProbesIntegrationTests` passou com 11 casos nos perfis `local,test`, PostgreSQL real e cadeia de segurança: estados 200/503, independência do provedor, falha de conexão injetada no DataSource, respostas sem detalhes e demais endpoints do Actuator protegidos/não expostos. A fixture HTTP sem banco usa somente `readinessState` em seu grupo de teste. `MAVEN_OPTS=-Xmx256m ./mvnw -B --no-transfer-progress -Djava.io.tmpdir=/var/tmp -DargLine=-Xmx512m -Dspring.test.context.cache.maxSize=2 clean verify` terminou com **`BUILD SUCCESS`: 1035 testes em 68 relatórios XML, sem falhas, erros ou ignorados**. O JAR gerado foi executado no perfil `local`, com PostgreSQL temporário e provedor inacessível: liveness/readiness 200 `UP`; após parar o banco, readiness 503 `DOWN` e liveness 200 `UP`. Env/Prometheus sem token retornaram 401. Processo, container e volume temporários removidos. Compose validado com o check de readiness; links locais, blocos Bash e `git diff --check` conferidos. Sem novo build de imagem ou subida do Compose completo.

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
| 05.01–05.02 | `pom.xml`, `model/ActivationRequestTests`, testes da política e dos serviços | Ferramentas e testes unitários de domínio já existem; a versão adotada é Spring Boot 3.5.16. |
| 05.04 | `ListActivationRequestsEndpointTests`, `ActivationRequestQueryTests` | Filtros, paginação, ordem e isolamento já cobertos. |
| 05.06–05.09 | `TestcontainersConfiguration`, `FlywayMigrationTests`, `CreateActivationRequestEndpointTests`, testes de persistência/claim | PostgreSQL real, migrações, duplicidade, corrida, versão e claim já cobertos. |
| Parte de 05.10–05.16 | Testes de lease/attempt/outcome, WireMock do cliente, testes do worker e `ActivationRequestProviderRejectionTests` | Base de processamento e testes já existe; complementar apenas A04/A06. |
| 05.11, 05.15 | WireMock em portas dinâmicas e uso de Awaitility nas suítes existentes | Não falta criar essas infraestruturas de teste. |
| 05.22; 07.01–07.04 | POM com lifecycle Maven padrão e `.github/workflows/ci.yaml` | Execução de testes já configurada; CI precisa dos complementos de A12. |
| 05.26; parte de 07.08–07.14 | README e guias em `docs/` | API, estados, duplicidade, matriz do provedor, testes e JWT já documentados; restam lacunas/correções de A01–A03. |
| 06.05–06.06 | `CorrelationIdFilter`, V4, worker/cliente e testes de correlação/logs | Propagação entrada → persistência → worker → provedor e limpeza de MDC já existem. |
| Parte de 04.18, 06.03 e 06.15 | `SecurityConfiguration`, README, logs de worker/cliente e `server.shutdown: graceful` | Segurança básica do Actuator, justificativa CORS/CSRF, logs técnicos e configuração de shutdown já existem; completar A01/A08/A09. |

## 4. Evidência de execução desta revisão

### Validação mais recente — A05 (29/10/2026)

Base de código validada: `4891717` (`testes do processamento integrado`). Nesta tarefa, somente este documento foi alterado.

**Comando:** `./mvnw -B --no-transfer-progress verify`. A primeira tentativa no sandbox foi interrompida antes dos testes por falta de acesso de escrita ao lock do cache Maven em `~/.m2`. A execução seguinte, com acesso ao cache e ao Docker, terminou com **`BUILD SUCCESS`: 953 testes, 0 falhas, 0 erros e 0 ignorados**. As 60 classes concluídas no log correspondem exatamente aos 60 relatórios XML em `target/surefire-reports/`, cujos totais foram somados e conferidos.

**Integração:** confirmada a inicialização de PostgreSQL 17 via Testcontainers. Entre os relatórios aprovados estão `ActivationRequestProcessingIntegrationTests` (3 casos), `ActivationRequestSchedulerRestartTests` (1), `FlywayMigrationTests` (18) e `ResourceServerSecurityConfigurationUnitTests` (19). Não houve necessidade de repetir a suíte nem os cenários de containers já demonstrados em A01.

**Conteúdo versionado:** inspecionados os 177 arquivos listados por `git ls-files`. Nenhum arquivo `.env` real, chave/certificado em arquivo, `authorization.header`, `env.sh` ou saída de `target/` está versionado. A busca por padrões de chave privada PEM, JWT assinado, access key AWS e token GitHub não encontrou ocorrências. Os campos de senha e API key de `.env.example` estão vazios; os YAMLs da aplicação e do Compose recebem os segredos por variáveis de ambiente. Os exemplos e fixtures consultados usam placeholders ou material gerado durante os testes. `git check-ignore --no-index` confirmou sete caminhos representativos de `.env`, `.env.*` e `target/`, mantendo `.env.example` versionável.

**Pacote final:** inspecionado `target/ativaflow-api-0.0.1-SNAPSHOT.jar`, produzido nesta execução, com 78 entradas de aplicação e 97 dependências. Nenhum arquivo de chave, `.env`, header Bearer ou JWKS local foi encontrado na listagem; a mesma busca por padrões de credenciais nas entradas da aplicação não encontrou ocorrências. Os oito recursos de `src/main/resources/` correspondem byte a byte aos empacotados. Manifesto executável confirmado; classes de teste, dependências de teste verificadas (JUnit, Mockito, Testcontainers, WireMock e ArchUnit) e DevTools ausentes. JAR e relatórios permanecem ignorados, sem inclusão no commit.

**Limites:** esta inspeção cobre os arquivos versionados atuais e as entradas da aplicação no JAR; não é auditoria de todo o histórico Git nem do conteúdo interno das dependências de terceiros. Não houve nova execução de imagem/Compose ou consulta à CI remota nesta tarefa; a evidência anterior de containers está em A01 e a execução remota continua em A12. A conferência com o HTML original e a revisão humana continuam pendentes em A05.

### Histórico — revisão original anterior à correção de A00

Os resultados abaixo registram a falha original, já corrigida em A00; não representam o estado da validação mais recente.

Comando executado: `./mvnw -B --no-transfer-progress verify`, com acesso ao cache Maven e Docker para Testcontainers.

**Resultado: `BUILD FAILURE`, 949 testes, 0 falhas de asserção, 15 erros, 0 ignorados.** Os 58 relatórios em `target/surefire-reports/` totalizam 934 casos sem falha/erro e os 15 erros de `ResourceServerSecurityConfigurationUnitTests` descritos em A00. Houve execução de integração com PostgreSQL real; não se trata de suíte inteira pendente de configuração. Os relatórios são artefatos locais, não devem ser versionados.

**Reprodução isolada:** `./mvnw -B --no-transfer-progress -Dtest=ResourceServerSecurityConfigurationUnitTests test` também terminou com `BUILD FAILURE`: 19 casos, os mesmos 15 erros e nenhum ignorado. Nenhum código da aplicação ou dos testes foi alterado nesta revisão.

Na revisão original, não foram executados build de imagem, subida do Compose completo nem demonstração da API em containers, pois Dockerfile e os serviços `app`/PostgreSQL ainda não existiam. Esses itens foram implementados e validados posteriormente em A01.
