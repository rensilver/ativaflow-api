# ADR-0001 — Contrato dos metadados persistidos de processamento

Data: 2026-10-29. Decisão: aceita para orientar a implementação do processamento.

## Contexto e alcance

A tabela `activation_requests` já contém os metadados internos na migração V2,
mapeados por `model.ActivationRequest`. Esta decisão define sua semântica e o
protocolo que o worker deverá cumprir. O estado de implementação do claim e as
etapas ainda pendentes estão descritos ao final desta decisão. A persistência
existente ainda não garante o protocolo completo de envio.

Os estados públicos continuam `PENDING`, `PROCESSING`, `COMPLETED` e `FAILED`.
Espera por retry e resultado indeterminado permanecem em `PROCESSING`. Os campos
abaixo não entram nos DTOs públicos e não podem ser recebidos do consumidor.

## Dicionário persistido

| Coluna / propriedade Java | Tipo SQL / Java | Nulidade e padrão | Contrato |
| --- | --- | --- | --- |
| `attempt_count` / `attemptCount` | `INTEGER` / `int` | Obrigatório; `0`; não negativo. | Número de tentativas autorizadas para envio, incluindo a primeira. Incrementado e confirmado antes de cada chamada HTTP, nunca apenas por adquirir um claim. Não é uma contagem exata de chamadas recebidas pelo provedor. |
| `next_attempt_at` / `nextAttemptAt` | `TIMESTAMP WITH TIME ZONE` / `Instant` | Opcional; `NULL`. | Primeiro instante permitido para um retry comprovadamente seguro. `NULL` significa ausência de retry agendado; em `PENDING`, a primeira tentativa pode ser selecionada imediatamente. Em `PROCESSING`, `NULL` sozinho nunca autoriza reenvio. |
| `lease_until` / `leaseUntil` | `TIMESTAMP WITH TIME ZONE` / `Instant` | Opcional; `NULL`. | Limite exclusivo da posse temporária: lease válido enquanto `agora < lease_until`; vencido em `agora >= lease_until`. Expiração permite avaliar recuperação, sem provar ausência de efeito externo. |
| `claimed_by` / `claimedBy` | `VARCHAR(100)` / `String` | Opcional; `NULL`. | Identificador não vazio do worker/instância que adquiriu o claim. Deve ser preenchido e removido junto com `lease_until`. Não funciona sozinho como token de proteção contra um worker antigo. |
| `last_error_code` / `lastErrorCode` | `VARCHAR(100)` / `String` | Opcional; `NULL`. | Código categorizado da falha mais recente, por exemplo `PROVIDER_TIMEOUT`, sem payload, token, credencial ou resposta bruta. Preservado durante retry; substituído na próxima falha e limpo no sucesso confirmado. |
| `reconciliation_required` / `reconciliationRequired` | `BOOLEAN` / `boolean` | Obrigatório; `false`. | Bloqueia novos envios enquanto não há resultado externo conhecido. É ativado preventivamente antes do HTTP e permanece ativo em resultado indeterminado. Não é um quinto estado público nem confirmação de falha. |

`version` (`BIGINT` / `Long`, obrigatório no banco, padrão `0`, não negativo)
complementa o claim com controle de concorrência. É nulo na entidade nova,
antes da persistência, para que o Spring Data reconheça a inserção.
`updated_at` registra a última alteração persistida; não substitui o vencimento
do lease nem o próximo agendamento. Datas usam `Instant` e representam UTC.

## Protocolo de escrita e envio

Cada passo de banco usa transação curta. A chamada HTTP acontece fora dessas
transações e somente depois do commit que autoriza a tentativa.

1. **Criação:** persistir `PENDING`, contador `0`, sem agendamento, claim ou erro,
   com `reconciliation_required=false`, na mesma transação da solicitação.
2. **Claim:** selecionar e atualizar atomicamente uma linha elegível, atribuir
   `claimed_by` e `lease_until`, passar para `PROCESSING` e retornar a versão
   resultante. Não incrementar o contador. Se houver retry agendado, preservar
   `next_attempt_at` até a preparação do envio, permitindo recuperar um claim
   abandonado antes dessa preparação.
3. **Preparação do envio:** com posse e versão válidas, exigir novamente contador
   abaixo de 3, `reconciliation_required=false` e, quando presente, agendamento
   já vencido. Incrementar
   `attempt_count`, limpar `next_attempt_at` e marcar
   `reconciliation_required=true` na mesma atualização. Confirmar a transação
   antes de chamar o provedor. Falha no commit ou zero linhas atualizadas impede
   o HTTP. Não limpar o último erro apenas por iniciar outra tentativa.
4. **Resultado:** gravar o resultado com a posse e a versão esperadas, conforme a
   tabela abaixo. Remover os dois campos do claim na mesma atualização.

A sinalização preventiva fecha a janela entre enviar HTTP e salvar seu resultado.
Se o processo cair depois da preparação, inclusive antes de abrir a conexão, a
tentativa conta e o resultado exige reconciliação. Essa possível retenção sem
envio é uma escolha conservadora: o banco não consegue comprovar o efeito de uma
chamada remota após uma falha do processo.

| Resultado conhecido pelo worker | Status | Agendamento | Reconciliação | Último erro |
| --- | --- | --- | --- | --- |
| Sucesso confirmado | `COMPLETED` | `NULL` | `false` | `NULL` |
| Falha permanente confirmada | `FAILED` | `NULL` | `false` | Código categorizado da falha. |
| Falha transitória com garantia de não ativação e tentativas disponíveis | `PROCESSING` | Instante futuro calculado. | `false` | Código categorizado da falha. |
| Falha confirmada esgotando o limite de tentativas | `FAILED` | `NULL` | `false` | Código categorizado da última falha. |
| Timeout ou outra resposta sem certeza sobre o efeito externo | `PROCESSING` | `NULL` | `true` | Código categorizado, como `PROVIDER_TIMEOUT`. |

O limite é de **3 tentativas totais**, incluindo a primeira. A política do futuro
processador calcula backoff exponencial com jitter e respeita `Retry-After`
válido; persiste o instante escolhido, sem espera com `Thread.sleep`. Nem um
HTTP 500/503 nem o esgotamento do contador confirmam ausência de ativação.
Resultado indeterminado permanece em `PROCESSING`, mesmo na terceira tentativa.
Classificação HTTP e parâmetros do backoff serão definidos na integração.

## Elegibilidade, posse e recuperação

O worker de envio deve exigir ausência de lease válido, contador abaixo do
limite e `reconciliation_required=false`, além de uma destas condições:

- `PENDING` com contador `0` e sem agendamento: primeiro envio.
- `PROCESSING` com `next_attempt_at <= agora`: retry seguro já registrado.
- `PROCESSING` com contador `0` e sem agendamento: claim inicial abandonado
  antes da preparação do primeiro envio, comprovado pelo protocolo acima.

Durante o claim de retry, o agendamento preservado identifica a mesma condição
segura se o worker cair antes da preparação. A preparação remove esse agendamento
e ativa a sinalização de reconciliação atomicamente. Portanto, um lease expirado
após a preparação nunca torna a linha elegível para envio automático.

Seleção e claim devem ser atômicos entre instâncias, por exemplo com
`FOR UPDATE SKIP LOCKED` e atualização na mesma transação. O worker só prossegue
se uma linha for efetivamente adquirida. Usar o relógio do banco para as
comparações e atribuições de lease no SQL; duração e tamanho de lote serão
configurações do worker. Não manter locks durante o HTTP.

Toda preparação, renovação e gravação de resultado deve verificar `id`, status
`PROCESSING`, `claimed_by`, versão esperada e lease ainda válido. Cada mutação
incrementa `version` e atualiza `updated_at`; o worker passa a usar a versão
retornada. SQL direto precisa fazê-lo explicitamente, pois não executa auditoria
JPA nem o incremento automático de `@Version`. Zero linhas atualizadas significa
perda de posse; o worker antigo não pode sobrescrever dados nem iniciar novo HTTP.
Uma resposta recebida após perder a posse também não autoriza gravação irrestrita.

O recuperador pode retirar um claim vencido em atualização condicional pela
versão e pelo vencimento, preservando contador, agendamento, erro e sinalização.
Se a sinalização estiver ativa, a linha continua bloqueada para envio. Renovar
lease não autoriza repetir uma tentativa; se uma chamada ainda estiver em curso,
a sinalização preventiva impede outro worker de enviar novamente.

Reconciliação exige evidência confiável do resultado externo: ativação confirmada
permite `COMPLETED`; falha permanente confirmada permite `FAILED`; não ativação
confirmada permite agendar retry se houver tentativas disponíveis. Essas decisões
também usam atualização condicional e limpam a sinalização atomicamente. Sem
consulta ou idempotência efetivamente garantida pelo provedor, manter a pendência
para intervenção operacional. Não existe endpoint ou rotina de reconciliação
implementado nesta etapa. Encerramento gracioso não pode limpar preventivamente
a sinalização de uma tentativa autorizada cujo resultado ainda é desconhecido.

Uma rotina de reconciliação não deve disputar uma tentativa com lease válido;
precisa adquirir sua própria posse condicional após a liberação ou expiração.
Não ativação confirmada com limite esgotado permite encerrar em `FAILED`.
Estados terminais não são elegíveis para claim nem para novas tentativas.
O protocolo protege as escritas locais, mas não promete efeito externo
exatamente uma vez: essa garantia depende do contrato real do provedor.

## Garantias existentes e trabalho pendente

A V2 garante tipos, nulidade, limites de texto e contadores não negativos. A
entidade mapeia os campos e usa `@Version`; mapper e DTOs omitem os metadados.
Não é necessária nova migração para definir este contrato.

O claim atômico está implementado em `ActivationRequestClaimService`, com seleção
`FOR UPDATE SKIP LOCKED`, atualização condicional e conferência das linhas
afetadas na mesma transação `REQUIRES_NEW`. Ele aplica a elegibilidade acima,
atribui posse usando o relógio do banco, incrementa versão/auditoria e preserva
contador, agendamento e último erro. Retorna uma fotografia imutável da posse
após o commit. `ActivationRequestClaimTests` cobre a disputa entre workers,
linhas bloqueadas, recuperação segura, igualdade no vencimento e isolamento
da transação; `ActivationRequestClaimServiceTests` cobre o tratamento da contagem
de linhas e a validação dos parâmetros.

Preparação do envio, renovação, persistência de resultados, scheduler e
reconciliação continuam pendentes. As demais relações entre colunas e o limite
total de 3 tentativas na preparação do envio ainda não possuem enforcement completo no banco ou
nos métodos da entidade. Os métodos atuais
`startProcessing()`, `complete()` e `fail()` cuidam das transições básicas;
não implementam o protocolo de posse e envio desta decisão.

Os testes existentes `FlywayMigrationTests`, `ActivationRequestPersistenceTests`,
`ActivationRequestTests` e `ActivationRequestMapperTests` verificam schema,
mapeamento, defaults, auditoria, lock otimista e isolamento dos campos públicos.
Esses testes não comprovam o fluxo completo do worker. As próximas etapas
deverão testar rejeição de worker antigo na preparação e no resultado,
recuperação antes/depois da preparação, limite total, agendamento e ausência de
reenvio automático em resultado indeterminado, com PostgreSQL real.
