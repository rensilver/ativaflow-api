# ADR-0002 — Contrato mínimo de ativação no provedor

Data: 2026-10-29. Decisão: aceita para orientar a futura integração e o mock.

## Contexto e alcance

A SPEC-03 exige `POST /provider/v1/activate`, mas registra que o enunciado não
fornece schema completo de entrada e saída. Não existe contrato oficial adicional.
Os campos JSON, o retorno `204` e a ausência de ativação nos cenários de erro
do mock abaixo são **suposições do projeto confirmadas com o responsável**.
Não representam garantias de um provedor real.

Esta decisão define o protocolo da chamada de saída do AtivaFlow. A implementação
do cliente HTTP, dos DTOs de integração, dos mappings WireMock e do worker continua
pendente. Os endpoints públicos e o schema do banco não mudam.

## Requisição

| Item | Contrato adotado |
| --- | --- |
| Método e caminho | `POST /provider/v1/activate`, sem query parameters. |
| Destino | URL base por `ACTIVATION_PROVIDER_BASE_URL`; o caminho é acrescentado pelo cliente. |
| Tipo do corpo | `Content-Type: application/json`, JSON em UTF-8. |
| Correlação | Propagar `X-Correlation-ID` quando disponível; não é campo de negócio nem chave de idempotência. |
| Autenticação | O mock não exige credencial. O contrato real deverá definir o mecanismo antes de seu uso. `ACTIVATION_PROVIDER_API_KEY` é apenas uma reserva de configuração e não define um header. |

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

Os DTOs futuros pertencem a `client.provider.dto`, separados dos DTOs públicos.
Não enviar `id`, `consumerId`, status, datas, versão, tentativas ou demais metadados
internos. Não encaminhar o JWT do consumidor ao provedor. Não exigir `Accept`
específico, pois nenhum schema de resposta é contratado.

Exemplo para um mock local **quando ele estiver implementado e iniciado**:

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
| `429 Too Many Requests` ou `503 Service Unavailable` | **Somente no mock**, recusa transitória sem ativação, com `Retry-After` opcional. | Mesmo limite de retry seguro; respeitar `Retry-After` válido. |
| Timeout de conexão/leitura, conexão interrompida ou ausência de resposta confiável | Efeito externo desconhecido. | `PROCESSING`, `reconciliation_required=true`; sem reenvio automático, inclusive na terceira tentativa. |
| Demais respostas não contratadas, incluindo outros `2xx` e redirecionamentos, ou erro de provedor real sem garantia contratual de não ativação | Evidência insuficiente sobre o efeito externo. | `PROCESSING`, `reconciliation_required=true`; sem reenvio automático; revisar o contrato para classificar com segurança. |

A referência genérica a sucesso `2xx` na SPEC-03 fica concretizada aqui por
`200`, `201` e `204`. `202` exige tratamento separado porque indica processamento
ainda não concluído ([RFC 9110, seção 15.3.3](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.3.3)).
O mock padrão deverá usar `204`. Nenhum endpoint de consulta externa ou callback
é presumido para resolver `202` ou timeout.

A garantia de não ativação em `429/503` pertence ao **contrato simulado**, não
ao significado genérico desses códigos. O `500` é sempre tratado como resultado
indeterminado, pois pode ocorrer após a ativação. Alterar a URL base para um
provedor real não transfere a garantia do mock para `429/503`. Corpo vazio e
`Retry-After` isoladamente não provam ausência de ativação.

## Retry e resultado indeterminado

São permitidas até **três tentativas totais**, incluindo a primeira, apenas para
falhas comprovadamente seguras. Cada tentativa envia os mesmos três valores.
Não fazer retry implícito na biblioteca HTTP nem seguir redirecionamentos
automaticamente; o agendamento persistido controla os novos envios.

Para um retry seguro, aceitar `Retry-After` como segundos inteiros não negativos
ou data HTTP, conforme a [RFC 9110, seção 10.2.3](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3).
O próximo instante é o maior entre o backoff com jitter e o prazo válido do header,
conforme o serviço existente. Header ausente, inválido ou data passada não elimina
o backoff positivo. Não antecipar uma data futura válida para caber no teto do
backoff. Se o prazo não puder ser representado com segurança, não reenviar antes
dele: preservar a pendência para análise operacional.

Timeouts configurados atualmente: conexão `2s` e leitura `5s`, alteráveis por
`ACTIVATION_PROVIDER_CONNECT_TIMEOUT` e `ACTIVATION_PROVIDER_READ_TIMEOUT`.
Eles limitam a espera do cliente; não cancelam comprovadamente a ativação remota.

Aplicar o protocolo da [ADR-0001](0001-metadados-de-processamento.md): confirmar a
preparação antes do HTTP, chamar fora de transação de banco e persistir o resultado
em transação curta, condicionada à posse e versão da tentativa. Resultado ambíguo
permanece protegido para reconciliação/intervenção operacional. Vencimento do
lease e esgotamento de tentativas não autorizam reenvio nem falha terminal presumida.

## Limitações e validação futura

`externalId` não é uma chave de idempotência garantida pelo provedor. Não enviar
`Idempotency-Key` nem prometer efeito externo exatamente uma vez sem contrato
real que defina deduplicação, escopo, retenção e comportamento para dados divergentes.
Não existe consulta externa de reconciliação contratada; a operação automática
de reconciliação também continua pendente.

Antes de integrar um provedor real, confirmar campos, limites, autenticação,
semântica de sucesso, não ativação em erros, idempotência e reconciliação.
Alternativas descartadas nesta etapa: inventar JSON de resposta, reutilizar DTO
público como contrato externo e tratar qualquer erro HTTP como autorização de retry.

Na implementação, verificar com WireMock iniciado pelos próprios testes: método,
caminho, headers e os três campos exatos; sucesso sem corpo; erros com e sem corpo;
`202`; `500` indeterminado e `429/503` seguros apenas conforme contrato;
formatos de `Retry-After`;
timeouts e ausência de retries cegos. Verificar com persistência real o limite de
três tentativas e a preservação de resultados indeterminados. Esses cenários são
critérios futuros, não testes já entregues nesta decisão.
