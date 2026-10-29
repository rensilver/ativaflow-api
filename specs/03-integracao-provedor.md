# SPEC-03 — Integração HTTP com o provedor externo

**Prioridade:** P0 (chamar e tratar cenários), P1 (assíncrono confiável, timeout ambíguo, 429/503, retries, reconciliação).  
**Dependências:** [SPEC-01](01-api-e-dominio.md), [SPEC-02](02-duplicidade-e-concorrencia.md).

## 1. Limite entre o domínio e a dependência externa

- Chamar **`POST /provider/v1/activate`** depois da criação da solicitação.
- O HTML **não fornece schema completo** de request/response do provedor; manter o payload de integração mínimo, explícito e documentado como suposição, sem inventar funcionalidades contratuais.
- Criar `ActivationProviderClient` (porta/interface de saída) e `HttpActivationProviderClient` (`RestClient`, Spring Boot 3.5.x), sem vazar detalhes do WireMock para o domínio.
- Configuração tipada com `@ConfigurationProperties`: `ACTIVATION_PROVIDER_BASE_URL`, connection/read timeouts, número máximo de tentativas (3 totais), backoff e jitter; validar configuração.
- Preferir resultados **tipados** via `sealed interface ProviderOutcome permits Success, PermanentFailure, TransientFailure, Indeterminate` (ou nomes equivalentes). Usar `switch` com pattern matching Java 21 quando melhorar a clareza; separar mapeamento HTTP de decisões de transição.
- Não usar `@Transactional` durante a chamada remota.

## 2. Matriz de comportamento

| Resposta do provedor           | Estado após tratamento                                                                            | Novo envio?                                         | Fundamentação                                                                         |
| ------------------------------ | ------------------------------------------------------------------------------------------------- | --------------------------------------------------- | ------------------------------------------------------------------------------------- |
| HTTP 2xx                       | `COMPLETED`                                                                                       | Não                                                 | Ativação confirmada                                                                   |
| HTTP 400                       | `FAILED`                                                                                          | Não                                                 | Erro permanente de entrada/contrato                                                   |
| HTTP 500                       | `PROCESSING`, `reconciliation_required=true`                                                | **Não automaticamente**                            | O produto pode ter sido ativado antes da resposta 500                                  |
| HTTP 429 / 503 + `Retry-After` | `PROCESSING` aguardando próxima tentativa                                                         | Sim, se seguro, respeitando `Retry-After`           | Backpressure/indisponibilidade temporária                                             |
| Timeout de conexão/leitura     | `PROCESSING`, `reconciliation_required=true` (metadado interno)                                   | **Não automaticamente** sem idempotência confirmada | Provedor pode ter ativado o produto antes da perda da resposta                        |

- Tratar HTTP 500 como potencialmente ambíguo; o status não assegura que nada foi ativado. Não transformar suposições em garantias.
- Respostas 429/503 só autorizam retry quando o contrato do provedor garantir que a ativação não ocorreu; `Retry-After` sozinho não comprova isso.
- `Retry-After`: aceitar `delta-seconds` e data HTTP quando existirem; aplicar limites razoáveis e documentados, sem descartar orientação do provedor arbitrariamente.
- Backoff exponencial + jitter, tentativa 1 incluída no limite total; persistir `attempt_count` e `next_attempt_at`; nada de `Thread.sleep` no caminho de requisição/worker.
- Ao atingir limite de tentativas **seguras** e falhas confirmadas, marcar `FAILED` com justificativa interna; resultados desconhecidos permanecem `PROCESSING` para reconciliação.

## 3. Execução confiável e recuperação

1. `POST /api/v1/requests` valida, persiste `PENDING` e confirma transação.
2. Scheduler/worker encontra pendências persistidas, toma posse com claim atômico e muda para `PROCESSING`.
3. Fora de transação aberta, invoca `ActivationProviderClient`.
4. Em outra transação curta, salva resultado, contadores e próxima tentativa, com proteção contra concorrência.
5. No restart, worker retoma `PENDING` e apenas `PROCESSING` recuperáveis de modo seguro; os ambíguos permanecem identificados para reconciliação.

Processamento assíncrono garante resposta rápida de criação, mas **não** significa entrega exatamente uma vez do efeito externo. Não prometer garantia que o provedor não oferece. Se houver suporte real a `Idempotency-Key`, usar chave estável derivada de `requestId` e testar; WireMock sozinho não garante idempotência.

## 4. WireMock no repositório

```text
infra/wiremock/
├── mappings/
│   └── activation-success.json  # padrão: 2xx (ex.: 204)
└── __files/                    # apenas se necessário
```

- Container `activation-provider-mock`, imagem `wiremock/wiremock`, acessível no Compose por `http://activation-provider-mock:8080`.
- `ACTIVATION_PROVIDER_BASE_URL` permite apontar para qualquer provedor compatível, sem alteração de código.
- Mapping estático **padrão de sucesso** para demonstração manual; não criar seis mappings conflitantes para o mesmo método/path.
- Testes unitários/de integração iniciam seu **próprio WireMock** e configuram dinamicamente 2xx, 400, 500, 429, 503, delays/timeouts; testes não dependem do serviço Compose rodando.
- Não criar um segundo microsserviço Spring Boot ou endpoint de negócio artificial para escolher cenário.
- Não publicar a API administrativa do mock na Internet; porta local exposta apenas para localhost se necessária.

## 5. Observabilidade e segurança

- Propagar `X-Correlation-ID` (ou W3C `traceparent` se tracing habilitado) até a chamada HTTP; não vazar JWT de consumidores ao provedor.
- Registrar latência, status, tentativa, identificador interno e causa categorizada; jamais payload sensível, credenciais ou token.
- Credencial de integração, se prevista pelo contrato real, por variável de ambiente/secret store, com rotação descrita, nunca no Git.
- Sem Circuit Breaker por padrão: timeouts/retry limitados e execução assíncrona atendem o escopo; registrar no ADR o trade-off e condição para adoção futura.

## 6. Critérios de aceite

- [ ] `POST /provider/v1/activate` executado com RestClient e URL configurável.
- [ ] 2xx, 400, 500, 429, 503 e timeout tratados de forma determinística, com matriz documentada no README.
- [ ] Criação retorna após persistência, sem esperar HTTP externo; chamadas não ocorrem em transação aberta.
- [ ] Até 3 tentativas totais, backoff+jitter, `Retry-After` e próximo agendamento persistidos.
- [ ] Timeouts ambíguos não geram reenvios cegos nem sucesso/falha falsamente confirmados.
- [ ] Pending/recovery testados após reinício simulado; concorrência de workers controlada.
- [ ] WireMock Compose independente do WireMock de teste; sem mappings conflitantes.
- [ ] Tipos `sealed` + `switch` utilizados quando úteis e testados em Java 21.
