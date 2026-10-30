# ADR-0003 — Processamento assíncrono com trabalho persistido

- **Status:** Aceita e aplicada ao processamento atual.
- **Data:** 2026-10-28; revisada em 2026-10-30.
- **Referências:** [ADR-0001](0001-metadados-de-processamento.md), [ADR-0002](0002-contrato-minimo-provedor.md) e [criação de solicitações no README](../../README.md#criação-de-solicitações).

## Contexto e relação com as decisões existentes

A ativação depende de `POST /provider/v1/activate`, sujeito a lentidão, timeout e
queda do processo. A API precisa confirmar a persistência sem esperar a ativação
e permitir retomada após reinício.

Esta ADR registra a escolha do mecanismo assíncrono já descrita na
[ADR-0001](0001-metadados-de-processamento.md#decisão-de-processamento-assíncrono-persistido).
Ela a complementa, sem substituir seu protocolo de metadados, elegibilidade,
posse e recuperação. A classificação do resultado externo continua definida na
ADR-0002.

## Decisão

Usar o PostgreSQL como fonte durável de trabalho pendente e um worker com polling:

1. Para um `externalId` novo, `ActivationRequestCreationTransaction` grava a
   solicitação em `PENDING`, com seus metadados iniciais, em `REQUIRES_NEW`.
   Após o commit, o POST retorna `201 Created`, `Location` e a fotografia da
   criação. Repetições seguem a [ADR-0004](0004-duplicidade-e-idempotencia.md).
2. `ActivationRequestWorker`, habilitado por padrão, usa `@Scheduled` com
   `app.worker.poll-interval` de `250ms` por padrão. Cada execução processa
   sequencialmente o trabalho elegível até não adquirir outro claim. Não há
   disparo imediato por evento após o commit nem fila em memória.
3. `ActivationRequestClaimService` seleciona com `FOR UPDATE SKIP LOCKED` e
   adquire posse em transação curta própria. A atualização verifica estado,
   versão e elegibilidade, atribui worker/lease e passa para `PROCESSING`.
   Adquirir claim ainda não autoriza HTTP nem incrementa a tentativa.
4. `ActivationRequestAttemptService` confirma outra transação antes do envio:
   incrementa o contador, remove o agendamento e ativa preventivamente
   `reconciliation_required`. Sem preparação confirmada, não há chamada.
5. O worker suspende transações chamadoras com `NOT_SUPPORTED`; o HTTP ocorre
   fora de transação de banco. O handler encaminha o resultado a serviços que
   o persistem em `REQUIRES_NEW`, condicionados à posse, versão e lease válidos.
6. Após reinício, o mesmo polling encontra `PENDING` e pode retomar diretamente
   claims vencidos comprovadamente seguros. O serviço explícito de recuperação
   de leases não possui scheduler próprio. Resultado desconhecido permanece
   bloqueado em `PROCESSING`, sem reenvio automático.

O lease padrão é `30s`, configurável em `app.worker.lease-duration`. Não há
renovação durante o HTTP: uma resposta tardia não autoriza sobrescrever o estado.
Não existe endpoint ou rotina implementada de reconciliação. O protocolo completo
e as condições de recuperação permanecem na ADR-0001.

## Alternativas consideradas

| Alternativa | Motivo para não adotar nesta etapa |
| --- | --- |
| Chamada síncrona durante a criação | Acopla a resposta da API à latência e disponibilidade do provedor; dentro da transação também retém recursos do banco. |
| Apenas `@Async`, executor ou evento em memória | Não preserva o trabalho após queda do processo. |
| Broker e publicação de mensagens | Acrescenta infraestrutura e coordenação entre persistência e publicação, desnecessárias para o escopo atual. |

## Consequências e limitações

Criação e conclusão têm tempos distintos. O consumidor acompanha o estado por
GET, que pode observar avanço em relação à fotografia retornada pelo POST.
Polling acrescenta consultas e espera até a próxima execução; o worker sequencial
também fica ocupado durante a chamada externa. Crescimento do backlog exige
avaliação de capacidade e concorrência.

Persistência permite retomada segura, mas não garante efeito externo exatamente
uma vez. Queda após a preparação pode reter a solicitação mesmo sem envio efetivo;
a proteção conservadora exige intervenção operacional quando falta evidência do
resultado. Aplicam-se os limites de idempotência da ADR-0004 e de resiliência da
[ADR-0006](0006-resiliencia-sem-circuit-breaker.md).

## Evidências no repositório

- `ActivationRequestProcessingIntegrationTests`: criação confirmada antes do HTTP, scheduler, ausência de transação durante a chamada e consulta do resultado.
- `ActivationRequestSchedulerRestartTests`: retomada após reinício com scheduler ativo e preservação do bloqueio de reconciliação.
- `ActivationRequestClaimTests` e `ActivationRequestAttemptTests`: concorrência, posse e fronteira de preparação em PostgreSQL real.
- `ActivationRequestAmbiguousOutcomeTests`: resultado indeterminado sem novo envio, inclusive após expiração do lease.

Essas referências identificam a cobertura existente; não são um registro de
execução da suíte nesta revisão documental.

## Quando reavaliar

Reconsiderar broker e, se houver publicação de eventos, Transactional Outbox
quando volume, custo de polling ou requisitos de entrega justificarem a mudança.
Essa evolução deve preservar a proteção contra efeitos externos desconhecidos.
