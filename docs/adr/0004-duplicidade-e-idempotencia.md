# ADR-0004 — Unicidade, concorrência e limites da idempotência

- **Status:** Aceita e aplicada à criação e ao processamento atuais.
- **Data:** 2026-10-28; revisada em 2026-10-30.
- **Referências:** [ADR-0001](0001-metadados-de-processamento.md), [ADR-0002](0002-contrato-minimo-provedor.md) e [idempotência no README](../../README.md#idempotência-da-api-e-idempotência-da-operação-externa).

## Contexto e relação com as decisões existentes

O `externalId` identifica a solicitação do sistema de origem e é único
globalmente. Requisições concorrentes podem ultrapassar uma consulta prévia e
disputar a inserção. Além disso, recuperar a resposta de um POST repetido precisa
respeitar a propriedade do recurso pelo consumidor autenticado.

Esta ADR complementa a
[decisão de duplicidade da ADR-0001](0001-metadados-de-processamento.md#decisão-de-duplicidade-e-idempotência-local).
O protocolo de posse e versão continua naquela decisão; o contrato externo e a
ausência de idempotência garantida pelo provedor continuam na ADR-0002.

## Decisão

A constraint `uk_activation_requests_external_id`, criada na migração V1,
garante a unicidade global de `external_id` no PostgreSQL. A consulta prévia por
`externalId` e consumidor é uma otimização, sem substituir essa garantia.

`ActivationRequestService` coordena a criação sem transação envolvente
(`NOT_SUPPORTED`). A inserção em `ActivationRequestCreationTransaction` usa
`REQUIRES_NEW` e `saveAndFlush`. Se perder a disputa pela constraint nomeada, a
inserção termina com rollback antes da releitura do vencedor em nova transação.
Outras violações de integridade não são tratadas como duplicidade.

O contrato HTTP implementado é:

| Situação | Resposta e efeito local |
| --- | --- |
| `externalId` novo | `201 Created`, `Location` e uma solicitação persistida. |
| Mesmo `externalId`, consumidor, `customerId` e `productCode` | `200 OK` com a representação atual, em qualquer dos quatro estados, sem `Location` nem nova linha. |
| Mesmo `externalId` com conteúdo divergente ou pertencente a outro consumidor | `409 Conflict` com `ProblemDetail` genérico, sem alterar o original nem devolver dados ou identidade do proprietário. |

A comparação é exata, preservando caixa e espaços. A identidade do consumidor
vem da autenticação, sem aceitar proprietário no payload. Uma repetição preserva
ID, datas, correlação e metadados e não reinicia o processamento nem causa outra
tentativa. O worker pode avançar o trabalho original independentemente do POST
repetido. Outro `externalId` cria outra solicitação, mesmo com cliente e produto
iguais. A indisponibilidade global do identificador pode ser inferida pelo conflito;
a resposta genérica evita revelar a representação e o proprietário alheios.

Na execução, `@Version` e atualizações condicionais com claim/lease protegem
as escritas locais conforme a ADR-0001. Métodos básicos de transição da entidade
não substituem esse protocolo. Estados terminais não são elegíveis para envio.

Não há `Idempotency-Key` contratado ou enviado, nem consulta externa de
reconciliação. `externalId` e `X-Correlation-ID` não comprovam deduplicação no
provedor. Resultado ambíguo mantém `PROCESSING` com bloqueio de reconciliação;
expiração de lease, reinício ou limite de tentativas não autorizam novo envio.

## Alternativas consideradas

| Alternativa | Motivo para não adotar |
| --- | --- |
| Apenas consultar antes de inserir | Deixa uma janela de corrida entre requisições. |
| `synchronized` ou lock em memória | Protege somente uma JVM. |
| Apenas `@Version` | Protege atualizações, sem impedir inserções com UUIDs distintos. |
| Unicidade por consumidor | Contraria a unicidade global adotada. |
| Rejeitar toda repetição ou sobrescrever o payload | Impede recuperar a representação após perder a resposta ou altera uma solicitação já iniciada. |

## Consequências e limitações

A constraint mantém uma linha por `externalId`, inclusive entre instâncias, e
a API reutiliza o efeito local de pedidos idênticos. O tratamento da corrida
depende da identificação correta da constraint e da fronteira transacional.

Essa garantia local não assegura uma única ativação externa. Retry seguro depende
do contrato da ADR-0002 e do protocolo persistido da ADR-0001; a unicidade da
linha, isoladamente, não resolve um timeout ou queda após envio.

## Evidências no repositório

- `FlywayMigrationTests` e `ActivationRequestPersistenceTests`: schema, unicidade e persistência em PostgreSQL real.
- `CreateActivationRequestEndpointTests`: `201`/`200`/`409`, repetição nos quatro estados, corrida pela constraint após rollback e isolamento por consumidor, sem nova ativação causada pela repetição.
- `ActivationRequestClaimTests`, `ActivationRequestOutcomeTests` e `ActivationRequestTests`: posse concorrente, escritas condicionais e proteção de estados terminais.
- `ActivationRequestAmbiguousOutcomeTests`: bloqueio de reenvio diante de efeito externo desconhecido.

Essas referências identificam a cobertura existente; não são um registro de
execução da suíte nesta revisão documental.

## Quando reavaliar

Rever a unicidade global somente mediante mudança explícita do contrato de
negócio. Se o provedor oferecer idempotência ou consulta confiável, documentar
escopo, retenção, dados divergentes e reconciliação na ADR-0002 antes de liberar
reenvio de resultados desconhecidos.
