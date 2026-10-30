# ADR-0006 — Resiliência proporcional sem Circuit Breaker inicial

- **Status:** Aceita e aplicada à integração e ao worker atuais.
- **Data:** 2026-10-28; revisada em 2026-10-30.
- **Referências:** [ADR-0001](0001-metadados-de-processamento.md), [ADR-0002](0002-contrato-minimo-provedor.md) e [matriz HTTP no README](../../README.md#matriz-http--status--retry--justificativa).

## Contexto e relação com as decisões existentes

O provedor pode ficar lento, indisponível ou responder sem confirmar o efeito da
ativação. O projeto já possui processamento assíncrono persistido, timeouts e
retry seguro. Não há evidência operacional ou SLO definido que justifique, nesta
etapa, acrescentar os estados e parâmetros de um Circuit Breaker.

Esta ADR complementa a
[decisão de não usar Circuit Breaker da ADR-0002](0002-contrato-minimo-provedor.md#decisão-de-não-usar-circuit-breaker).
A classificação HTTP continua naquela decisão; preparação, posse, agendamento e
bloqueio de reconciliação seguem a ADR-0001. Não se introduz outra política de
retry.

## Decisão

Manter a integração sem Circuit Breaker e sem dependência de Resilience4j,
usando os mecanismos implementados:

1. **Espera limitada:** conexão `2s` e leitura `5s` por padrão, configuráveis por
   `ACTIVATION_PROVIDER_CONNECT_TIMEOUT` e `ACTIVATION_PROVIDER_READ_TIMEOUT`.
   A chamada ocorre fora de transação de banco; timeout não comprova cancelamento
   da ativação remota.
2. **Tentativas persistidas:** no máximo três tentativas totais, incluindo a
   primeira. `app.worker.retry.max-attempts` aceita de 1 a 3, padrão 3. Claim,
   preparação e resultado respeitam o limite configurado. O contador registra
   autorizações confirmadas antes do HTTP, não recebimentos exatos no provedor.
3. **Retry somente com garantia de não ativação:** `429/503` são indeterminados
   no contrato padrão `none`. Apenas o contrato explícito
   `rejected-before-activation` permite classificá-los como `TransientFailure`,
   assumindo rejeição sem ativar nem enfileirar trabalho remoto. `500` permanece
   indeterminado em ambos os modos. Status ou `Retry-After` isolados não
   autorizam retry.
4. **Backoff durável:** `ActivationRetryPolicy` calcula base exponencial de `1s`,
   teto da base de `1m` e jitter adicional de até 25%, por padrão, configuráveis
   em `app.worker.retry`. O SQL persiste o maior instante entre relógio do banco
   mais atraso e `Retry-After` válido. Não há espera com `Thread.sleep` nem loop
   local de retries da mesma chamada; o polling respeita `next_attempt_at`.
5. **Prazo do provedor:** no contrato seguro, aceitar segundos inteiros não
   negativos ou data HTTP. Header ausente ou malformado, zero ou data passada
   não elimina o backoff positivo. Header repetido, overflow ou prazo além do
   limite operacional da ADR-0002 gera resultado indeterminado, sem antecipar
   um prazo que não possa ser representado com segurança.
6. **Conclusão conforme evidência:** apenas `200`, `201` e `204` confirmam sucesso;
   `400` confirma rejeição permanente sob a suposição do projeto. `202`, demais
   respostas não contratadas e falhas de comunicação permanecem indeterminadas.
   Sucesso gera `COMPLETED`; falha permanente ou última rejeição transitória
   segura gera `FAILED`. Resultado indeterminado mantém `PROCESSING` com
   `reconciliation_required=true`, inclusive na última tentativa.

A proteção de reconciliação é ativada antes da chamada. Lease vencido, resposta
tardia ou reinício não autorizam reenvio de uma tentativa com efeito desconhecido.
Não há renovação de lease durante HTTP nem rotina automática de reconciliação.

Logs correlacionados e métricas Micrometer registram duração e resultado das
tentativas, falhas/timeouts, retries e contagem persistida por estado. A contagem
é um snapshot periódico com timestamp e contador de falhas de atualização;
não representa medição de idade da fila ou ocupação do worker.

## Alternativas consideradas

| Alternativa | Motivo para não adotar nesta etapa |
| --- | --- |
| Sem timeout ou sem limite de tentativas | Permite ocupação prolongada do worker e repetição excessiva. |
| Retry imediato, ilimitado ou por status isolado | Amplifica indisponibilidade e pode repetir uma ativação já realizada. |
| Circuit Breaker desde o início | Exige definir janela, limiares, abertura, sondagens e integração com a tentativa persistida, sem evidência operacional suficiente. |
| Fallback que presume sucesso ou falha | Afirma um resultado externo que a aplicação não conhece. |

## Consequências e limitações

A política mantém o resultado desconhecido protegido e os retries recuperáveis
após reinício, com menos estados operacionais. Uma indisponibilidade prolongada
ainda recebe chamadas de solicitações novas; o limite por solicitação não limita
tráfego agregado nem substitui Circuit Breaker ou controle de capacidade.

O worker processa sequencialmente e pode acumular backlog enquanto aguarda HTTP.
Backoff e jitter distribuem retries seguros, mas não garantem ausência de
sobrecarga. O bloqueio conservador pode exigir intervenção operacional sem prazo
de liberação, pois não existe reconciliação implementada.

## Evidências no repositório

- `HttpActivationProviderWireMockTests`: sucesso contratado, rejeição, falhas HTTP, timeout e ausência de retry implícito.
- `HttpActivationProviderRetryTests` e `ActivationRequestHttpRetryTests`: contrato seguro, `Retry-After`, chamadas efetivas e limite de três tentativas.
- `ActivationRetryPolicyTests` e `ActivationRequestOutcomeTests`: backoff, jitter, persistência do prazo e resultados condicionados à posse.
- `ActivationRequestRetryLimitTests`: limite configurável menor e preservação da ambiguidade na última tentativa.
- `ActivationRequestAmbiguousOutcomeTests` e `ActivationRequestSchedulerRestartTests`: bloqueio de reenvio e retomada segura após reinício.
- `ActivationMetricsIntegrationTests` e `ActivationRequestStateMetricsTests`: métricas de tentativas e snapshot de estados.

Essas referências identificam a cobertura existente; não são um registro de
execução da suíte nesta revisão documental.

## Quando reavaliar

Reavaliar Circuit Breaker quando métricas de falhas/latência e investigação de
capacidade mostrarem chamadas repetidamente inúteis, ocupação excessiva do worker
ou crescimento do backlog. Medição de idade da fila e de ocupação pode precisar
ser acrescentada para apoiar essa avaliação.

A adoção deverá definir janela, limiares, duração da abertura, sondagens seguras,
coordenação entre instâncias e testes de transição. A rejeição por circuito aberto
deve preservar a pendência sem consumir tentativa de envio nem criar ambiguidade
apenas por impedir uma chamada; isso exige integrar a decisão à preparação
persistida. Circuit Breaker não resolve idempotência externa ou reconciliação.
