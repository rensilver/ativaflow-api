# SPEC-07 — CI, documentação, ADRs e entrega

**Prioridade:** P0 (estrutura, README, instruções de execução, análise e produção); P1 (Compose, GitHub Actions, ADRs, commits pequenos e uso de IA transparente).  
**Dependências:** todas as SPECs anteriores.

## 1. Estrutura esperada do repositório

```text
ativaflow-api/
├── src/main/...
├── src/test/...
├── infra/wiremock/mappings/
├── infra/wiremock/__files/
├── specs/
│   ├── 00-visao-geral.md
│   ├── 01-api-e-dominio.md
│   ├── 02-duplicidade-e-concorrencia.md
│   ├── 03-integracao-provedor.md
│   ├── 04-erros-e-seguranca.md
│   ├── 05-testes-automatizados.md
│   ├── 06-observabilidade-e-producao.md
│   └── 07-ci-e-entrega.md
├── docs/adr/
│   ├── 0001-processamento-assincrono.md
│   ├── 0002-duplicidade-e-idempotencia.md
│   ├── 0003-integracao-wiremock.md
│   └── 0004-resiliencia-sem-circuit-breaker.md
├── .github/workflows/ci.yaml
├── AGENTS.md
├── README.md
├── Dockerfile
├── .dockerignore
├── docker-compose.yaml
├── pom.xml
└── mvnw / .mvn/
```

O HTML exige `/src`, README, Maven/Gradle e `Dockerfile`; `docker-compose.yaml` e `/docs/adr` são diferenciais. `specs/`, `AGENTS.md` e `infra/wiremock` são escolhas do projeto.

## 2. GitHub Actions — diferencial CI (sem CD obrigatório)

Criar `.github/workflows/ci.yaml` com:

1. `push` e `pull_request` (restrição a branches apenas se for documentada).
2. Checkout e `actions/setup-java` Java 21 com cache Maven.
3. Permissões mínimas para token do workflow; sem secrets AWS/GitHub desnecessários.
4. `./mvnw -B verify` executando **todos** os testes de unidade e integração; executor com Docker disponível ao Testcontainers.
5. Build de imagem Docker somente após testes aprovados, sem necessidade de publicar em registry.
6. Falhar pipeline em qualquer quebra relevante; opcionalmente arquivar relatórios de testes.
7. Usar versões estáveis de actions e fixar versões/SHAs quando justificável pela política de segurança.

Não criar deploy automático só porque o enunciado pede CI. Se desejar, **descrever** CD cloud como evolução no README.

## 3. README — conteúdo de entrega obrigatório

- **Sobre o projeto:** problema de negócio, tecnologias e diagrama simples de comunicação API ↔ PostgreSQL/Provider.
- **Como executar:** pré-requisitos, `docker compose up --build`, URL da API, observabilidade, token local, execução dos testes com `./mvnw verify`, `docker compose down`, recuperação de ambiente.
- **API:** `POST /api/v1/requests`, `GET /api/v1/requests/{id}`, listagem/filtros/paginação, exemplos JSON, respostas e validações.
- **Estados:** quatro estados e transições autorizadas; tratamento de retries, falhas definitivas e resultado indeterminado.
- **Duplicidade:** constraint global + semântica 201/200/409 + teste concorrente PostgreSQL; BOLA sem vazamento.
- **Matriz do provedor:** 2xx, 400, 500, timeout, 429/503 com `Retry-After`: status, retry e justificativa; limitações de idempotência externa.
- **Segurança:** fluxo OAuth Client Credentials; escopos, JWT validado, proteção por consumidor, rate limit, credenciais do provedor e rotação; como gerar token local sem serviço real.
- **Erros:** RFC 9457, `ProblemDetail`, exemplo com `correlationId`.
- **Estratégia de testes:** unitários vs integração, WireMock e Testcontainers, execução na CI, testes de concorrência/segurança/arquitetura.
- **Observabilidade:** probes, logs JSON, métricas, Prometheus, tracing configurável.
- **Análise de desempenho (item 10):** **responder às seis perguntas**, com números exatos do HTML e hipótese verificável.
- **Produção:** Dockerfile, shutdown, configuração, escalabilidade, segredos, notificação por evento e eventual CI/CD cloud.
- **Pontos pendentes:** o que não foi implementado e como seria feito, sem alegar entregas inexistentes.
- **Melhorias futuras:** riscos reais e prioridades; incluir Circuit Breaker apenas se houver justificativa de operação.
- **Uso de IA — diferencial:** indicar planejamento, implementação, testes, documentação/review assistidos pelo Codex, SDD em `specs/`, revisão humana e responsabilidade técnica — somente atividades realmente realizadas.
- **ADRs:** índice/link para decisões registradas; explicar trade-offs sem duplicar README em excesso.

README em **português brasileiro**, com comandos que realmente funcionam em clone limpo. Nunca publicar tokens/segredos em exemplos.

## 4. ADRs — diferencial

Em `docs/adr/` usar arquivos curtos, numerados e consistentes com o código **real**:

- `0001-processamento-assincrono.md`: por que API não espera provedor; worker persistente, recovery e limitações.
- `0002-duplicidade-e-idempotencia.md`: constraint, transações, concorrência entre instâncias e limites de idempotência do provedor.
- `0003-integracao-wiremock.md`: mock por contrato, separação infraestrutura/domínio, tipos sealed, testes isolados.
- `0004-resiliencia-sem-circuit-breaker.md`: timeouts/retries/backoff e por que não iniciar com Resilience4j; critérios para mudança em produção.

Template de ADR: **Status**, **Contexto**, **Decisão**, **Alternativas consideradas**, **Consequências/limitações**. Não gerar decisões fictícias ou contraditórias com a implementação.

## 5. Git e transparência do uso de IA

- Commits pequenos e intencionais (p.ex. `feat(domain): ...`, `test(concurrency): ...`, `docs(adr): ...`) com teste/regressão a cada fase; não gerar um commit final monolítico.
- SDD: `AGENTS.md` orienta o Codex; `specs/` define tarefas e aceite; o desenvolvedor revisa as mudanças e valida criticamente concorrência, segurança e domínio.
- Documentar uso de IA como ferramenta efetiva no processo, **sem esconder contribuição e sem atribuir a si revisões que não ocorreram**.
- Não modificar arquivos não relacionados, não incluir prompts com dados sensíveis e não declarar sucesso sem executar os comandos.

## 6. Checklist final de entrega

- [ ] O projeto compila/roda com `./mvnw verify`; CI verde.
- [ ] `docker compose up --build` sobe três serviços e a API responde com autenticação configurada.
- [ ] Endpoints, regras de status, duplicidade, integração, erros e segurança demonstráveis.
- [ ] Diferenciais: Java moderno, Flyway, paginação, locks, retry, 429/503, BOLA, rate limit, testes concorrentes, ArchUnit, métricas/logs/tracing, Docker seguro, ADRs, commits e IA registrados.
- [ ] README atende **todas** as seções obrigatórias do HTML, incluindo cenário de lentidão e o que ficou de fora.
- [ ] `docs/adr/` descreve decisões reais; `specs/` tem critérios de aceite revisados, sem declarações falsas de implementação.
- [ ] Sem segredos, tokens ou chaves privadas em commits; exemplos limpos.
- [ ] Revisão final por pessoa capaz de explicar classes, transações, testes e escolhas em entrevista técnica.