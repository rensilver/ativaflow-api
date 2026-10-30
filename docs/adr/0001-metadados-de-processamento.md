# ADR-0001 — Contrato dos metadados persistidos de processamento

Data: 2026-10-29. Decisão: aceita e aplicada ao processamento implementado.

## Contexto e alcance

A tabela `activation_requests` já contém os metadados internos na migração V2,
mapeados por `model.ActivationRequest`. Esta decisão define sua semântica e o
protocolo cumprido pelo worker e pelos serviços de claim, preparação, resultado
e recuperação. Scheduler, cliente HTTP e gravação dos resultados estão implementados;
as evidências e limitações estão descritas ao final desta decisão. A proteção
persistida impede reenvios ambíguos, mas não garante idempotência no provedor.

Os estados públicos continuam `PENDING`, `PROCESSING`, `COMPLETED` e `FAILED`.
Espera por retry e resultado indeterminado permanecem em `PROCESSING`. Os campos
abaixo não entram nos DTOs públicos e não podem ser recebidos do consumidor.

## Decisão de processamento assíncrono persistido

**Contexto:** a ativação depende de HTTP externo sujeito a lentidão, timeout e
queda do processo. A criação precisa confirmar o trabalho local sem esperar essa
dependência e permitir retomada após reinício.

**Decisão:** persistir a solicitação em `PENDING` e confirmar sua transação antes
do processamento. O POST retorna `201 Created`, `Location` e a fotografia da
criação; isso confirma persistência, sem prometer ativação concluída. O worker
com `@Scheduled`, habilitado por padrão, consulta o PostgreSQL, adquire claim e
prepara a tentativa em transações curtas próprias. O HTTP ocorre fora de transação;
o resultado é gravado em outra transação condicional à posse e versão válidas.
O protocolo detalhado nas próximas seções é parte desta decisão.

**Alternativas consideradas:**

| Alternativa | Motivo para não adotar nesta etapa |
| --- | --- |
| Chamar o provedor durante o POST | Acopla o tempo de resposta à dependência externa; manter a transação aberta durante o HTTP também reteria recursos do banco. |
| Usar somente `@Async`, executor ou evento em memória | O trabalho pode desaparecer na queda do processo; o commit precisa deixar uma pendência recuperável sem depender de uma notificação em memória. |
| Introduzir broker e publicação de mensagens | Acrescenta infraestrutura e coordenação entre commit e publicação. O polling do trabalho já persistido atende o escopo atual sem essa segunda escrita. |

**Consequências:** criação e conclusão têm tempos distintos; o consumidor consulta
o estado por GET. O polling acrescenta consultas ao banco e espera até a próxima
busca. Persistir a pendência permite retomada após restart, mas exige claim,
lease, versão e proteção preventiva para não reenviar resultados desconhecidos.
Uma falha depois da preparação pode reter trabalho mesmo sem envio efetivo;
sem reconciliação automática, essa retenção exige intervenção operacional.
Não há garantia de efeito externo exatamente uma vez.

Rastreabilidade: [SPEC-03](../../specs/03-integracao-provedor.md),
[criação no README](../../README.md#criação-de-solicitações),
`ActivationRequestProcessingIntegrationTests` (commit, scheduler, HTTP fora de
transação e GET) e `ActivationRequestSchedulerRestartTests` (retomada segura e
preservação do bloqueio de reconciliação).

## Decisão de duplicidade e idempotência local

**Contexto:** repetir um POST após perder sua resposta não deve criar outra
solicitação. Consultas prévias podem disputar com outras inserções, inclusive em
instâncias distintas. A unicidade de `externalId` é global, enquanto o acesso à
representação pertence ao consumidor autenticado.

**Decisão:** usar a constraint `uk_activation_requests_external_id`, criada na
V1, como garantia de unicidade. A consulta prévia é uma otimização. A inserção
ocorre em `ActivationRequestCreationTransaction` com `REQUIRES_NEW`; se perder
a disputa por essa constraint, termina com rollback antes de reler o vencedor
em nova transação. Outras violações de integridade não são tratadas como repetição.

| Situação | Resposta e efeito local |
| --- | --- |
| `externalId` novo | `201 Created` e `Location`; uma nova solicitação persistida. |
| Mesmo `externalId`, consumidor, `customerId` e `productCode` | `200 OK` com a representação atual, em qualquer dos quatro estados; sem nova linha, reinício ou tentativa adicional causada pela repetição. |
| Mesmo `externalId` com conteúdo divergente ou outro consumidor | `409 Conflict` genérico; sem alterar o registro nem revelar a representação ou o proprietário alheio. |

A comparação preserva caixa e espaços, sem normalização. Uma repetição mantém
ID, datas, correlação e metadados; o worker pode avançar o trabalho original
independentemente dela. O `Location` acompanha somente a criação.

**Alternativas consideradas:** confiar apenas em consulta seguida de inserção
deixa uma janela de corrida; `synchronized` protege somente a mesma JVM;
`@Version` protege atualizações, sem impedir inserções com UUIDs diferentes.
Tornar a chave única por consumidor contraria a unicidade global prevista na
[SPEC-02](../../specs/02-duplicidade-e-concorrencia.md). Rejeitar toda repetição
com `409` impediria recuperar a representação de um pedido idêntico após perda
da resposta; sobrescrever o payload ou reiniciar o processamento poderia mudar
uma ativação já iniciada.

**Consequências:** a API reutiliza o efeito local mesmo em criações concorrentes,
mas depende do PostgreSQL e do tratamento correto da fronteira transacional.
O conflito genérico informa indisponibilidade do identificador sem expor dados
do outro consumidor. Outro `externalId` cria outra solicitação, mesmo com cliente
e produto iguais. Essa idempotência local não deduplica ativações no provedor:
não há `Idempotency-Key` contratado ou enviado. Claim e lease coordenam posse
local, mas não comprovam ausência de efeito remoto após uma falha; aplica-se o
bloqueio conservador descrito nesta decisão e na [ADR-0002](0002-contrato-minimo-provedor.md).

Rastreabilidade: [duplicidade no README](../../README.md#idempotência-da-api-e-idempotência-da-operação-externa),
`CreateActivationRequestEndpointTests` (repetição, divergência, isolamento por
consumidor e corrida pela constraint), `ActivationRequestClaimTests` e
`ActivationRequestAmbiguousOutcomeTests` (posse e proteção contra reenvio ambíguo).

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

O limite é de **3 tentativas totais**, incluindo a primeira. A política de retry
calcula backoff exponencial com jitter e respeita `Retry-After`
válido quando a resposta comprova não ativação; persiste o instante escolhido,
sem espera com `Thread.sleep`. HTTP 429/500/503, timeout e esgotamento do
contador não confirmam ausência de ativação por si sós.
Resultado indeterminado permanece em `PROCESSING`, mesmo na terceira tentativa.
O backoff inicia em 1 s, dobra por tentativa e limita a base a 1 min, com jitter
adicional de até 25%, configuráveis em `app.worker.retry`. O jitter não reduz a
base; o atraso deve ter pelo menos 1 ms. `Retry-After` é recebido como `Instant`
opcional e funciona como limite mínimo, sem reduzir o backoff. O cliente HTTP
classifica as respostas conforme a [ADR-0002](0002-contrato-minimo-provedor.md).
Por padrão, `429/503` permanecem indeterminados. Somente o contrato opt-in
`rejected-before-activation` autoriza `TransientFailure` e interpretação de
`Retry-After`; exige garantia de que não houve ativação nem trabalho remoto pendente.

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
comparações e atribuições de lease no SQL. A duração é configurada por
`app.worker.lease-duration`; o serviço interno de recuperação recebe o limite
do lote como argumento. Não manter locks durante o HTTP.

Toda preparação, renovação e gravação de resultado deve verificar `id`, status
`PROCESSING`, `claimed_by`, versão esperada e lease ainda válido. Cada mutação
incrementa `version` e atualiza `updated_at`; o worker passa a usar a versão
retornada. SQL direto precisa fazê-lo explicitamente, pois não executa auditoria
JPA nem o incremento automático de `@Version`. Zero linhas atualizadas significa
perda de posse; o worker antigo não pode sobrescrever dados nem iniciar novo HTTP.
Uma resposta recebida após perder a posse também não autoriza gravação irrestrita.

O recuperador retira um claim vencido em atualização condicional pela
versão e pelo vencimento, preservando contador, agendamento, erro e sinalização.
Se a sinalização estiver ativa, a linha continua bloqueada para envio. Renovar
lease não autoriza repetir uma tentativa; se uma chamada ainda estiver em curso,
a sinalização preventiva impede outro worker de enviar novamente.

A liberação automática abrange somente `PROCESSING` com lease não nulo e vencido,
incluindo igualdade ao relógio do banco, contador abaixo do limite, sinalização
de reconciliação inativa e uma autorização segura: tentativa inicial ainda não
preparada (`attempt_count=0`, sem agendamento) ou retry já agendado e vencido.
Remove os dois campos de posse juntos e incrementa versão/auditoria, preservando
contador, agendamento, erro e sinalização. Expiração nunca transforma em falha
nem presume ausência de efeito externo. Resultado ambíguo, retry não agendado ou
contador esgotado mantêm a posse persistida como evidência operacional e não
podem ser retomados automaticamente. Lotes positivos limitam o trabalho de cada
transação; linhas bloqueadas são ignoradas e podem ser tratadas na próxima
execução. O claim seguro também pode retomar diretamente uma posse vencida, sem
liberação prévia. Solicitações `PENDING` persistidas ficam disponíveis ao polling
do worker após o startup; nenhuma transição de recuperação é necessária.

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

`ActivationRequestAttemptService.prepareAttempt` implementa a preparação por
atualização condicional de ID, versão, status, worker, lease válido e elegibilidade.
Incrementa o contador, remove o agendamento e ativa a proteção de reconciliação
atomicamente, com incremento de versão/auditoria e preservação do último erro.
A transação `REQUIRES_NEW` confirma antes de retornar `PreparedActivationAttempt`,
com a nova versão e o número da tentativa. Zero linhas retorna vazio, sem autorizar
HTTP; contagem inesperada reverte a transação. A preparação não renova o lease.

`ActivationRequestAttemptService.recordAmbiguousOutcome` registra o resultado
indeterminado de uma tentativa preparada, usando sua versão e seu worker. Exige
`PROCESSING`, lease ainda válido e proteção preventiva ativa. Em transação
`REQUIRES_NEW`, persiste apenas o código categorizado, mantém
`reconciliation_required=true`, remove qualquer agendamento e libera os dois
campos de posse, incrementando versão/auditoria. Não altera o contador nem
encerra em `FAILED`, inclusive na terceira tentativa. Retorna `false` sem alterar
a linha quando a posse foi perdida; nesse caso, a sinalização gravada antes do
HTTP continua protegendo a solicitação. Contagem inesperada reverte a transação.

Não há garantia documentada de idempotência nem consulta de reconciliação no
provedor. Portanto, resultado ambíguo permanece bloqueado para intervenção
operacional, sem prazo de liberação automática. `external_id` único protege a
criação local, mas não comprova idempotência do efeito externo. Expiração de
lease, reinício, `Retry-After` e limite de tentativas não removem esse bloqueio.
O cliente HTTP classifica timeout de conexão/leitura e respostas sem certeza do
efeito externo como `Indeterminate`. O worker encaminha esse resultado pelo
`ActivationRequestOutcomeHandler` a essa operação, sem agendar retry.

`ActivationRequestLeaseRecoveryService.recoverExpiredLeases` libera somente posse
vencida com reenvio comprovadamente seguro, em lotes, com CTE, `FOR UPDATE SKIP
LOCKED` e atualização condicional na mesma transação própria curta. Preserva os
metadados de segurança e retorna a quantidade liberada. Claims ambíguos ou sem
retry seguro permanecem intactos. O worker retoma `PENDING` persistido pelo
polling configurado após o startup; não há rotina separada de startup.

`ActivationRequestAttemptTests` e `ActivationRequestLeaseRecoveryTests` verificam
a fronteira antes/depois da preparação, a retomada pelo mesmo worker, preservação
da ambiguidade na terceira tentativa, limites de tempo, concorrência, linhas
bloqueadas, preservação dos dados e commit independente em PostgreSQL real.
`ActivationRequestAttemptServiceTests` cobre contagens de linhas sem autorização.

`ActivationRequestAmbiguousOutcomeTests` verifica persistência do resultado
indeterminado nas três tentativas, ausência de novo claim/preparação, repetição
da escrita sem efeito, continuidade de outras pendências, commit independente
e rejeição de versão/worker incorretos, lease ausente/vencido (inclusive no limite),
estados incompatíveis e resposta tardia após expiração do lease, preservando
todos os dados mesmo após executar a recuperação. Usa PostgreSQL real.

`ActivationRequestOutcomeService` grava sucesso, falha permanente e falha
transitória comprovadamente segura em transações `REQUIRES_NEW`. A atualização
condicional exige a posse válida, versão e contador da tentativa preparada,
`PROCESSING` e sinalização preventiva ativa. Libera a posse e limpa a sinalização
na mesma escrita que persiste estado, próximo agendamento e erro, com incremento
de versão/auditoria. Não incrementa novamente o contador. Sucesso limpa o erro;
falhas preservam somente o código categorizado. Zero linhas retorna `false`;
contagem inesperada lança exceção e reverte a transação.

`ActivationRetryPolicy` calcula o atraso exponencial e o jitter; o SQL persiste
o maior instante entre relógio do banco mais atraso e `Retry-After`. Atrasos
menores que 1 ms são rejeitados no SQL quando há retry. A terceira falha segura
gera `FAILED` sem agendamento; resultado ambíguo continua bloqueado conforme
o protocolo anterior. Repetição de resultado, worker antigo e lease expirado
não alteram os dados nem antecipam o prazo. Não existe loop local de retries.

`ActivationRequestOutcomeTests` verifica o ciclo até três tentativas,
agendamento/`Retry-After`, resultados terminais, concorrência, perda de posse,
vencimento exato do lease, liberação e commit independente em PostgreSQL real.
Os testes unitários cobrem a política de atraso, sua configuração, validação dos
códigos de erro e contagens de linhas inesperadas.

`ActivationRequestWorker` está habilitado por padrão e usa `@Scheduled` para
buscar trabalho persistido. `poll()` e `processNext()` suspendem transações
chamadoras com `NOT_SUPPORTED`; claim e preparação confirmam suas transações
antes da chamada a `HttpActivationProviderClient`. O handler encaminha sucesso,
rejeição permanente, falha transitória segura e resultado indeterminado aos
serviços correspondentes. O cliente produz falha transitória segura para `429/503`
somente com o contrato de rejeição antes da ativação selecionado explicitamente.
O claim retoma diretamente trabalho seguro com lease vencido; o serviço de
recuperação explícita não possui scheduler próprio.

Renovação de lease e reconciliação automática continuam pendentes. O lease não
é estendido durante o HTTP: uma resposta após seu vencimento não pode ser gravada
como resultado confirmado, e a proteção preventiva preserva a ambiguidade.
As demais relações entre colunas não possuem enforcement completo
no banco ou nos métodos da entidade. Os métodos atuais
`startProcessing()`, `complete()` e `fail()` cuidam das transições básicas;
não implementam o protocolo de posse e envio desta decisão.

Os testes existentes `FlywayMigrationTests`, `ActivationRequestPersistenceTests`,
`ActivationRequestTests` e `ActivationRequestMapperTests` verificam schema,
mapeamento, defaults, auditoria, lock otimista e isolamento dos campos públicos.
Esses testes não comprovam o fluxo completo do worker.
`HttpActivationProviderClientTests` e `HttpActivationProviderWireMockTests`
cobrem a fronteira HTTP com WireMock, incluindo timeout de leitura real e
classificação conservadora, inclusive na presença de `Retry-After`.
`ActivationRequestWorkerTests` verifica a orquestração e a suspensão de transação
com dependências simuladas. `ActivationRequestProviderRejectionTests` combina
worker, cliente HTTP, WireMock e PostgreSQL para `400` e `503`, com scheduler
desligado e contrato padrão conservador. `ActivationRequestProcessingIntegrationTests`
reúne criação/commit, scheduler/worker, HTTP real e GET para sucesso, `500` e timeout;
`ActivationRequestSchedulerRestartTests` cobre retomada com scheduler ativo,
conforme [A04](../../PENDING_ACTIONS.md#a04--completar-a-evidência-do-processamento-integrado).
`HttpActivationProviderRetryTests` e `ActivationRequestHttpRetryTests` cobrem
interpretação de `Retry-After` no contrato opt-in e seu efeito no agendamento,
nas chamadas efetivas e no limite de três tentativas.
