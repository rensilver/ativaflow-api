# SPEC-06 — Observabilidade, análise de produção e Docker

**Prioridade:** P0 (cenário de análise e Dockerfile), P1 (Actuator, correlação, Micrometer, logs JSON, OpenTelemetry, Compose, cloud descrita).  
**Dependências:** SPECs [01](01-api-e-dominio.md)–[05](05-testes-automatizados.md).

## 1. Observabilidade básica — diferencial

- Incluir Spring Boot Actuator com `/actuator/health/liveness` e `/actuator/health/readiness`; política de exposição mínima. **Readiness** deve representar capacidade de atender tráfego, não depender de o provedor externo estar saudável para criar/consultar solicitações assíncronas, salvo requisito contrário documentado.
- Logs SLF4J/Logback concentrados em eventos relevantes: criação, transições, claim, retry, sucesso/falha, chamadas e exceções. Sem log em toda classe/método.
- Identificador de correlação da entrada até a saída para provedor; propagação explícita no trabalho assíncrono, limpeza do MDC ao final da execução; não confiar que MDC é propagado automaticamente entre threads.
- Jamais registrar JWT, credenciais, corpos sensíveis, segredos ou dados pessoais desnecessários.

## 2. Observabilidade avançada — todos os diferenciais

- **Micrometer + Prometheus:** disponibilizar `/actuator/prometheus` com latência de API, quantidade/erros por rota/status, latência do provedor, erros/timeouts e retries por categoria, solicitações por status (`PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`), HikariCP e JVM.
- Métricas de status podem ser coletadas do banco periodicamente ou derivadas de indicadores consistentes; documentar latência/consistência da medição e evitar queries caras a cada scrape.
- **Logs estruturados JSON:** usar suporte nativo compatível com Spring Boot 3.5.x; campos estáveis `correlationId`, `traceId`, `requestId` quando aplicável, `attempt`, `httpStatus`, `durationMs`, sem alta cardinalidade nas **tags de métricas**.
- **OpenTelemetry (tracing distribuído):** instrumentar via Micrometer Tracing e bridge OpenTelemetry; trace HTTP de entrada, chamada RestClient e fluxo assíncrono com propagação W3C `traceparent` quando apropriado. Exportação OTLP **configurável e desabilitada por padrão** na demo; não exigir collector/Jaeger/Tempo para o app inicializar. Documentar como conectar collector no futuro e diferença entre traces e logs.
- Tratar endpoint Prometheus como operacional, proteger acesso; não expor endpoints de Actuator indiscriminadamente.

## 3. Cenário obrigatório de análise no README

**Indicadores do HTML:** API 300 ms → 2,5 s; CPU 45%; memória 60%; algumas consultas 50 ms → 1,2 s; provedor externo 400 ms → 3 s.

Responder **brevemente e com hipóteses verificáveis**:

1. **Investigar primeiro:** estabelecer início/escopo da regressão, tráfego e mudanças recentes; olhar percentis p50/p95/p99, fila de trabalho, taxa de erros/retries, saturação dos pools e dependências.
2. **Distinguir aplicação/banco/provedor:** comparar tempo de requisição HTTP, espera por conexão Hikari, duração real da query no PostgreSQL (`pg_stat_statements`/`EXPLAIN (ANALYZE, BUFFERS)` se disponível) e duração do HTTP externo. Verificar se criação é verdadeiramente assíncrona.
3. **Logs/métricas úteis:** correlação e traceId, tempo por etapa, pool Hikari (`active`, `pending`, `timeout`), locks, slow queries, chamadas/erros do provedor, tentativas, execução de workers, CPU/memória/threads, p95/p99.
4. **Melhorias potenciais:** eliminar HTTP dentro de transações; corrigir consultas/índices comprovadamente lentos; dimensionar pool e concorrência por evidências; limitar retries e controlar backpressure; calibrar timeouts.
5. **Hipótese de causa comum — diferencial:** latência externa aumenta retenção de workers e, se houver transações abertas/bloqueios ou contenção em recursos compartilhados, causa espera por conexões e aumenta latência de consulta/API. Confirmar com métricas correlacionadas, traces e perfil de transação. **Não afirmar que é a causa sem prova.**
6. **Validação pós-correção — diferencial:** comparar p95/p99, taxa de erro, latência da query e provedor, uso do pool e backlog sob carga equivalente; realizar testes de regressão/carga e monitorar após publicação com possibilidade de rollback.

**Observação crítica:** se `POST` apenas persiste e agenda, latência do provedor **não deve por si só** impor +3 s à resposta de criação. Investigar gargalos compartilhados, acoplamento indevido, filas ou outras rotas. CPU 45%/memória 60% não são evidência suficiente de CPU/RAM saturada.

## 4. Dockerfile e ambiente — obrigatório + diferencial

- Criar `Dockerfile` para execução em produção, **multi-stage build**, runtime JRE enxuto, versão Java 21, usuário não-root, `.dockerignore`, sem secrets ou arquivos de build extras.
- Container-friendly: configurar JVM consciente do limite de memória (ex.: `MaxRAMPercentage` quando necessário), healthcheck, sinais/`SIGTERM` e **graceful shutdown** de requisições/workers. Não encerrar trabalho em andamento sem persistir recuperação/lease.
- `docker-compose.yaml` com **exatamente os serviços necessários** à demo: `app`, `postgres` (volume persistente) e `activation-provider-mock` (imagem oficial WireMock).
- API aponta `postgres:5432` e `http://activation-provider-mock:8080`, **nunca** `localhost` para comunicação entre containers.
- Healthcheck PostgreSQL; app depende de `service_healthy` onde aplicável, mas implementa retry de conexão/robustez para indisponibilidades transitórias. Mock estático com mappings em `infra/wiremock/`.
- Variáveis de ambiente e profiles mínimos; segredos reais não versionados; bind do WireMock só a localhost se porta externa for necessária.
- Comando validado: `docker compose up --build`; orientar encerramento seguro e limpeza de volumes sem perda acidental de dados.

## 5. Microsserviços e cloud — diferencial documental

**Não implementar infraestrutura AWS/terraform/Kubernetes sem exigência.** Incluir no README uma seção sucinta:

- **Múltiplas instâncias:** constraint global PostgreSQL + claim atômico/leases; não confiar em mutex local; idempotência externa requer suporte do provedor.
- **Configurações e segredos:** variáveis de ambiente e secret store gerenciado (p.ex. AWS Secrets Manager), permissões mínimas e rotação.
- **Eventos de ativação:** quando necessário, publicar evento em fila/tópico; para consistência entre commit do DB e publicação, considerar **Transactional Outbox** e consumo idempotente, sem implementar mensageria no take-home.
- **CI/CD futuro:** build e testes → imagem Docker → registry → deploy progressivo/rollback e monitoramento; a CI atual só testa e empacota.
- **Produção:** banco gerenciado, rede privada, TLS, métricas, secrets, escalabilidade e read/write probes; listar riscos e passos, sem declarar que deploy foi realizado.

## 6. Critérios de aceite

- [ ] Dockerfile compila/executa com multi-stage, usuário não-root e imagem runtime enxuta.
- [ ] Compose com app + PostgreSQL + mock sobe de clone limpo; healthchecks e desligamento corretos.
- [ ] Actuator liveness/readiness, correlação, logs JSON e métricas Prometheus operacionais.
- [ ] OpenTelemetry instrumentado com exportação opcional, sem exigir quarto container.
- [ ] README responde às seis perguntas do cenário real com distinção entre hipótese e evidência.
- [ ] README contém discussão cloud/microsserviços (escala, segredos, eventos, CI/CD) sem prometer deploy.