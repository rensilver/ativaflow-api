# ADR-0002 — Contrato mínimo de ativação no provedor

Data: 2026-10-29. Decisão: aceita e aplicada à integração HTTP e ao mock.

## Contexto e alcance

A SPEC-03 exige `POST /provider/v1/activate`, mas registra que o enunciado não
fornece schema completo de entrada e saída. Não existe contrato oficial adicional.
Os campos JSON, o retorno `204` e a rejeição permanente `400` sem ativação
são **suposições do projeto confirmadas com o responsável**. O contrato opcional
de rejeição antes da ativação em `429/503` é uma suposição técnica explicitamente
selecionável para o mock. Nenhum desses cenários representa garantia de um provedor real.

Esta decisão define o protocolo da chamada de saída do AtivaFlow, implementado
por `HttpActivationProviderClient`, `ActivationProviderRequest` e
`ActivationRequestWorker`. O mapping padrão do WireMock retorna `204`.
O worker prepara a tentativa após o commit da criação, chama o cliente fora de
transação e persiste o resultado por serviços transacionais. Os contratos públicos
e os quatro estados da API são preservados.

## Decisão de usar WireMock

**Contexto:** não há um provedor real disponível nem schema oficial completo.
A demonstração local e os testes precisam exercitar a fronteira HTTP com
respostas e atrasos controlados, sem transformar suposições em garantias externas.

**Decisão:** usar WireMock como provedor simulado. O Compose executa a imagem
`wiremock/wiremock`, com o mapping estático de sucesso
[activation-success.json](../../infra/wiremock/mappings/activation-success.json)
retornando `204`. Os testes HTTP iniciam seu próprio WireMock em porta dinâmica,
com cenários definidos por teste; não dependem do Compose. O cenário opcional
de retry fica em `scenarios/`, fora dos mappings padrão, e só é carregado
explicitamente. O worker usa a porta `ActivationProviderClient`, cujo contrato
independe do transporte. A implementação HTTP usa `RestClient` e URL configurável, sem selecionar
comportamento por detectar o mock ou a URL de localhost.

**Alternativas consideradas:**

| Alternativa | Motivo para não adotar nesta etapa |
| --- | --- |
| Criar outro microsserviço Spring Boot ou endpoint artificial de cenário | Acrescenta código e manutenção para reproduzir funções já atendidas pelo WireMock. |
| Simular somente a interface Java | É útil nos testes unitários, mas não verifica serialização, headers, status ou timeout sobre HTTP real. |
| Compartilhar o mock do Compose entre os testes | Introduz dependência de serviço previamente iniciado e estado compartilhado entre cenários. |
| Depender de um provedor real | Impede controlar falhas e atrasos de forma isolada; seu contrato e ambiente ainda não foram fornecidos. |

**Consequências:** a mesma integração HTTP atende demonstração e testes isolados,
com um único sucesso padrão, sem mappings de erro conflitantes. O Compose
publica a porta do mock somente em loopback; sua API administrativa permite
alterar cenários e não deve ser exposta publicamente. WireMock comprova o
comportamento do cliente diante das respostas simuladas, sem validar autenticação,
idempotência, capacidade ou semântica de um provedor real. Antes de mudar o
destino, é necessário confirmar o contrato descrito nesta decisão.

Rastreabilidade: [SPEC-03](../../specs/03-integracao-provedor.md),
[Compose](../../docker-compose.yaml),
[demonstração no README](../../README.md#provedor-simulado-no-compose),
`HttpActivationProviderWireMockTests` e `ActivationRequestHttpRetryTests`.

## Requisição

| Item | Contrato adotado |
| --- | --- |
| Método e caminho | `POST /provider/v1/activate`, sem query parameters. |
| Destino | URL base por `ACTIVATION_PROVIDER_BASE_URL`; o caminho é acrescentado pelo cliente. |
| Tipo do corpo | `Content-Type: application/json`, JSON em UTF-8. |
| Correlação | Propagar `X-Correlation-ID` quando disponível; não é campo de negócio nem chave de idempotência. |
| Autenticação | O mock não exige credencial. O contrato real deverá definir o mecanismo antes de seu uso. `ACTIVATION_PROVIDER_API_KEY` é apenas uma reserva de configuração e não define um header. |

A implementação de correlação aceita um único `X-Correlation-ID` de entrada com
1 a 128 caracteres ASCII (primeiro alfanumérico; demais alfanuméricos, `.`, `_`,
`:` ou `-`). Ausência, valor inválido ou headers repetidos geram um UUID, devolvido
no header da resposta. O identificador é persistido em `correlation_id` pela V4 e
reutilizado pelo worker em todas as tentativas; POSTs duplicados mantêm o original.
Registros anteriores sem correlação válida usam seu UUID como fallback estável.
O contexto MDC é restaurado ao terminar cada execução, inclusive em falhas.
O cliente HTTP lê apenas essa correlação; não copia headers da requisição pública
nem credenciais do contexto de segurança.

O corpo contém exclusivamente os dados persistidos da solicitação:

```json
{
  "externalId": "EXT-12345",
  "customerId": "CUST-10001",
  "productCode": "INTERNET-500"
}
```

| Campo | Tipo e obrigatoriedade | Significado assumido |
| --- | --- | --- |
| `externalId` | String obrigatória. | Referência de negócio informada pelo consumidor; não comprova deduplicação externa. |
| `customerId` | String obrigatória. | Identificador textual do cliente a ativar. |
| `productCode` | String obrigatória. | Código do produto a ativar para o cliente. |

Os três valores são enviados sem normalização, preservando caixa e espaços,
inclusive em retries. A aplicação já aceita apenas texto não nulo e não vazio
segundo `String.isBlank()`, com até 100 unidades UTF-16 por campo. Esse limite
é uma decisão local de validação/persistência, não um requisito conhecido do
provedor. Não presumir formato de documento, catálogo de produtos ou regex adicional.

O DTO `ActivationProviderRequest` pertence a `client.provider.dto`, separado dos DTOs públicos.
Não enviar `id`, `consumerId`, status, datas, versão, tentativas ou demais metadados
internos. Não encaminhar o JWT do consumidor ao provedor. Não exigir `Accept`
específico, pois nenhum schema de resposta é contratado.

Exemplo para o mock local iniciado conforme o [README](../../README.md#provedor-simulado-no-compose):

```bash
curl -i --request POST 'http://localhost:8081/provider/v1/activate' \
  --header 'Content-Type: application/json' \
  --data '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'
```

## Retorno e classificação

O retorno canônico de sucesso é `204 No Content`, sem corpo. Assume-se que ele
confirma a **ativação concluída**, não apenas o recebimento do pedido. Não exigir
`activationId`, `status`, `message`, datas ou qualquer outro campo de resposta.
Não há schema de erro contratado; o corpo de erro pode estar vazio e não participa
da classificação. Não persistir nem registrar a resposta bruta.

| Resposta ou falha | Evidência adotada | Resultado local e novo envio |
| --- | --- | --- |
| `204`, sem corpo | Ativação concluída, conforme a suposição de sucesso. | `COMPLETED`; sem retry. |
| `200 OK` ou `201 Created` | Alternativas de sucesso assumidas para compatibilidade com o mock; também confirmam conclusão, com corpo dispensável e ignorado. | `COMPLETED`; sem retry. |
| `202 Accepted` | Apenas aceitação; não confirma conclusão. Está fora do retorno de sucesso contratado. | `PROCESSING`, `reconciliation_required=true`; sem reenvio automático. |
| `400 Bad Request` | No mock, rejeição permanente da entrada antes de qualquer ativação. | `FAILED`; sem retry. |
| `500 Internal Server Error` | O status não confirma se o produto foi ativado. | `PROCESSING` com reconciliação; sem reenvio automático. |
| `429 Too Many Requests` ou `503 Service Unavailable`, contrato `none` (padrão) | Status e header não comprovam ausência de ativação. | `PROCESSING`, `reconciliation_required=true`; sem reenvio automático. |
| `429/503`, contrato `rejected-before-activation` selecionado explicitamente | Garantia de rejeição antes de ativar ou enfileirar trabalho remoto. | `TransientFailure`: `PROCESSING` com próximo prazo; esgotar o limite de tentativas confirmadas encerra em `FAILED`. Prazo não representável bloqueia o reenvio. |
| Timeout de conexão/leitura, conexão interrompida ou ausência de resposta confiável | Efeito externo desconhecido. | `PROCESSING`, `reconciliation_required=true`; sem reenvio automático, inclusive na terceira tentativa. |
| Demais respostas não contratadas, incluindo outros `2xx` e redirecionamentos, ou erro de provedor real sem garantia contratual de não ativação | Evidência insuficiente sobre o efeito externo. | `PROCESSING`, `reconciliation_required=true`; sem reenvio automático; revisar o contrato para classificar com segurança. |

A referência genérica a sucesso `2xx` na SPEC-03 fica concretizada aqui por
`200`, `201` e `204`. `202` exige tratamento separado porque indica processamento
ainda não concluído ([RFC 9110, seção 15.3.3](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.3.3)).
O mock padrão usa `204`. Nenhum endpoint de consulta externa ou callback
é presumido para resolver `202` ou timeout.

A garantia de não ativação não é inferida da URL ou do header. A propriedade
`app.activation-provider.retry-contract`, exposta por
`ACTIVATION_PROVIDER_RETRY_CONTRACT`, aceita `none` (padrão) e
`rejected-before-activation`. Valores desconhecidos impedem a inicialização.
O modo opt-in declara que **todas** as respostas `429/503` do endpoint rejeitam
antes de qualquer ativação ou enfileiramento remoto. Ele só deve ser selecionado
para um provedor real após confirmar essa garantia. O `500` continua indeterminado
em ambos os modos; corpo vazio e `Retry-After` isoladamente não liberam retry.

O cenário [activation-rejected-before-activation.json](../../infra/wiremock/scenarios/activation-rejected-before-activation.json)
simula esse contrato: rejeita com `503`, sem ativar ou manter trabalho remoto,
e pede 120 segundos antes do próximo envio. Fica fora de `mappings/` para não
conflitar com o sucesso padrão. A carga explícita pela API administrativa e a
restauração estão no [README](../../README.md#habilitar-retry-http-no-contrato-seguro).
Os testes isolados também simulam `429` com a mesma garantia. Isso demonstra a
suposição do mock, sem atestar comportamento de um serviço real.

## Retry e resultado indeterminado

São permitidas até **três tentativas totais**, incluindo a primeira, apenas para
falhas comprovadamente seguras. Cada tentativa envia os mesmos três valores.
Não fazer retry implícito na biblioteca HTTP nem seguir redirecionamentos
automaticamente; o agendamento persistido controla os novos envios.

Com o contrato seguro selecionado, o cliente interpreta `Retry-After` como
segundos inteiros não negativos ou data
HTTP, conforme a [RFC 9110, seção 10.2.3](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3).
O próximo instante é o maior entre o backoff com jitter e o prazo válido do header,
conforme o serviço existente. Header ausente, inválido ou data passada não elimina
o backoff positivo. Não antecipar uma data futura válida para caber no teto do
backoff. Segundos são contados a partir da interpretação da resposta, sem usar
o relógio remoto. Datas HTTP são interpretadas pelo parser de `HttpHeaders`.
O limite operacional é `9999-12-31T23:59:59Z`, inferior aos limites de
`Instant` e PostgreSQL. Overflow numérico, instante além desse limite ou headers
repetidos produzem `Indeterminate("PROVIDER_RETRY_AFTER_UNSUPPORTED")`;
preservam a pendência para análise operacional, sem truncar nem antecipar o prazo.
Header ausente ou malformado produz `Optional.empty()` e mantém o backoff positivo.
Não persistir ou registrar o header bruto.

Alternativas descartadas: inferir garantia pela URL de localhost, habilitar retry
por status/header isolados ou presumir idempotência externa. Um contrato enumerado
e conservador por padrão torna a garantia selecionada explícita sem alterar a
porta de domínio, o protocolo de persistência ou a biblioteca HTTP.

Timeouts configurados atualmente: conexão `2s` e leitura `5s`, alteráveis por
`ACTIVATION_PROVIDER_CONNECT_TIMEOUT` e `ACTIVATION_PROVIDER_READ_TIMEOUT`.
Eles limitam a espera do cliente; não cancelam comprovadamente a ativação remota.

Aplicar o protocolo da [ADR-0001](0001-metadados-de-processamento.md): confirmar a
preparação antes do HTTP, chamar fora de transação de banco e persistir o resultado
em transação curta, condicionada à posse e versão da tentativa. Resultado ambíguo
permanece protegido para reconciliação/intervenção operacional. Vencimento do
lease e esgotamento de tentativas não autorizam reenvio nem falha terminal presumida.

## Decisão de não usar Circuit Breaker

**Contexto:** o provedor pode ficar lento ou indisponível. Conforme a
[SPEC-03](../../specs/03-integracao-provedor.md), o escopo atual usa processamento
assíncrono, timeouts configuráveis e até três tentativas totais apenas quando
há garantia de não ativação. A criação não aguarda o HTTP e a chamada não mantém
transação de banco aberta.

**Decisão:** manter a integração sem Circuit Breaker nesta etapa. Timeouts
limitam a espera da chamada, enquanto backoff, jitter e `Retry-After` regulam
retries seguros por solicitação. Resultado ambíguo permanece bloqueado para
reconciliação. Esses mecanismos atendem o escopo atual, mas não equivalem a um
bloqueio global de chamadas durante uma indisponibilidade prolongada.

**Alternativas consideradas:** adicionar Circuit Breaker agora permitiria
suspender chamadas e testar a recuperação do provedor, porém exigiria definir
limiares, janela, duração da abertura, chamadas de sondagem e comportamento
persistido para uma tentativa impedida localmente. Também seria necessário
decidir como coordenar essa política entre instâncias. Retry automático em
memória ou fallback que presume sucesso/falha não substituem essa decisão:
contornariam o agendamento durável ou afirmariam um resultado externo desconhecido.

**Consequências e condição para revisão:** durante uma queda, solicitações novas
ainda podem chegar ao provedor e ocupar o worker até seus timeouts, acumulando
pendências; o limite de tentativas por solicitação não limita o tráfego agregado.
A ausência do breaker reduz configuração e estados operacionais, com esse custo
explícito. Reavaliar sua adoção quando latência, taxa de falhas, ocupação do worker
e idade/volume do backlog demonstrarem chamadas repetidamente inúteis ou risco
ao orçamento de chamadas do provedor. A adoção deve integrar rejeição local,
claim e preparação sem consumir tentativas de envio nem criar ambiguidade apenas
porque o circuito está aberto. Deve preservar pendências, definir retomada e
sondagens seguras e ter testes próprios. Um breaker não resolve idempotência
externa nem reconciliação; ambas continuam dependentes do contrato do provedor.

Rastreabilidade: `ActivationProviderConfiguration` (timeouts e transporte),
`ActivationRequestWorker` (execução assíncrona), `ActivationRetryPolicy`
(backoff/jitter) e o protocolo da [ADR-0001](0001-metadados-de-processamento.md).

## Evidências existentes e limitações

`externalId` não é uma chave de idempotência garantida pelo provedor. Não enviar
`Idempotency-Key` nem prometer efeito externo exatamente uma vez sem suporte
confirmado pelo contrato real, incluindo deduplicação, escopo, retenção e
comportamento para dados divergentes. A integração atual não envia essa chave.
Não existe consulta externa de reconciliação contratada; a operação automática
de reconciliação também continua pendente.

Antes de integrar um provedor real, confirmar campos, limites, autenticação,
semântica de sucesso, não ativação em erros, idempotência e reconciliação.
Alternativas descartadas nesta etapa: inventar JSON de resposta, reutilizar DTO
público como contrato externo e tratar qualquer erro HTTP como autorização de retry.

`HttpActivationProviderClientTests` e `HttpActivationProviderWireMockTests`
iniciam WireMock isolado e cobrem payload, sucesso `200/201/204`, rejeição `400`,
respostas indeterminadas, timeout de leitura e ausência de reenvio automático.
`HttpActivationProviderCorrelationTests` verifica propagação da correlação e
ausência do JWT na saída com transporte simulado. Os testes de `429/503` com
`Retry-After` comprovam que o header não libera retry no contrato padrão.
`ActivationRequestProviderRejectionTests` combina worker, HTTP real, WireMock e
PostgreSQL para `400/503`, sem scheduler. Os testes de persistência de resultados
e ambiguidade verificam o limite de tentativas e o bloqueio de reenvio no banco.

A cobertura conjunta de criação/commit → worker → HTTP → estado persistido/GET
para sucesso, `500` e timeout, e a retomada com scheduler habilitado estão em
[A04](../../PENDING_ACTIONS.md#a04--completar-a-evidência-do-processamento-integrado).
`HttpActivationProviderRetryTests` cobre contrato opt-in, segundos/data HTTP,
header ausente, inválido, zero, passado, repetido e enorme, além da preservação
das demais classificações. `ActivationRequestHttpRetryTests` combina worker,
WireMock e PostgreSQL reais: prazo mínimo persistido, bloqueio antes do prazo,
backoff positivo, três chamadas efetivas até `FAILED`, sucesso após retry e
bloqueio em timeout ou overflow. O cenário JSON de demonstração é reutilizado
nessa suíte. O scheduler fica desligado; Awaitility conduz o worker conforme
o prazo real no banco, sem adiantar agendamentos por SQL.
Não há operação automática de reconciliação nem teste que possa comprovar
idempotência de um provedor real sem contrato correspondente.
