# ADR-0005 — Integração por contrato e simulação externa com WireMock

- **Status:** Aceita e aplicada à integração HTTP, ao Compose e aos testes.
- **Data:** 2026-10-28; revisada em 2026-10-30.
- **Referências:** [ADR-0002](0002-contrato-minimo-provedor.md), [Compose](../../docker-compose.yaml) e [provedor simulado no README](../../README.md#provedor-simulado-no-compose).

## Contexto e relação com as decisões existentes

O projeto exige `POST /provider/v1/activate`, mas não dispõe de provedor real ou
schema oficial completo. Demonstração e testes precisam controlar respostas,
falhas e atrasos na fronteira HTTP.

Esta ADR complementa a
[decisão de usar WireMock da ADR-0002](0002-contrato-minimo-provedor.md#decisão-de-usar-wiremock).
O payload, a correlação, as suposições de resposta e a classificação de resultados
permanecem definidos ali. O mock demonstra o comportamento do cliente sob essas
suposições; não certifica o contrato de um provedor real.

## Decisão

1. O worker depende de `ActivationProviderClient`. O adaptador
   `HttpActivationProviderClient` usa `RestClient`, configurado por
   `ActivationProviderConfiguration` com transporte JDK HTTP/1.1, sem seguir
   redirecionamentos automaticamente. `ActivationProviderProperties` recebe URL,
   timeouts e contrato opcional de retry por configuração externa.
2. A integração retorna a `sealed interface` `domain.ProviderOutcome`, já
   implementada com `Success`, `PermanentFailure`, `TransientFailure` e
   `Indeterminate`. O worker e o handler usam switch por tipo; os resultados não
   alteram entidades nem escolhem transições por conta própria. O domínio e a
   porta não dependem do WireMock ou do transporte HTTP.
3. O Compose executa `activation-provider-mock` com `wiremock/wiremock:3.13.2`,
   montando somente `infra/wiremock/mappings/` e `infra/wiremock/__files/` nos
   diretórios correspondentes de `/home/wiremock`, em modo somente leitura.
   O [mapping padrão](../../infra/wiremock/mappings/activation-success.json)
   retorna `204` para o endpoint de ativação.
4. O [cenário de rejeição segura](../../infra/wiremock/scenarios/activation-rejected-before-activation.json)
   fica fora de `mappings/` e só é carregado explicitamente pela API
   administrativa. Simula `503` com `Retry-After: 120`; o retry requer também
   selecionar `ACTIVATION_PROVIDER_RETRY_CONTRACT=rejected-before-activation`.
   O padrão `none` preserva esse resultado como indeterminado.
5. Os testes HTTP iniciam WireMock isolado em porta dinâmica, com respostas por
   cenário. Não usam o mock do Compose. Os testes com persistência utilizam
   PostgreSQL via Testcontainers; dispensam Compose, mas exigem Docker disponível.
6. A aplicação não detecta o mock pela URL nem adiciona parâmetros ou headers
   de cenário à API de negócio. O Compose publica o mock em
   `127.0.0.1:8081`, acessível à aplicação por
   `http://activation-provider-mock:8080`. A API administrativa fica restrita ao
   ambiente local e não deve ser publicada externamente.

O payload contém somente `externalId`, `customerId` e `productCode` persistidos,
sem normalização. A integração propaga a correlação persistida em
`X-Correlation-ID`, sem encaminhar JWT ou credenciais do consumidor. O mock não
exige autenticação; `ACTIVATION_PROVIDER_API_KEY` é reserva de configuração,
sem header contratado ou enviado. O contrato real precisa definir autenticação
antes do uso.

## Alternativas consideradas

| Alternativa | Motivo para não adotar nesta etapa |
| --- | --- |
| Outro microsserviço Spring Boot para simular o provedor | Acrescenta código e operação para recursos já oferecidos pelo WireMock. |
| Apenas Mockito na porta Java | Não verifica serialização, headers, status ou timeout sobre HTTP real. |
| Mock do Compose compartilhado com testes | Cria dependência de ambiente iniciado manualmente e interferência entre cenários. |
| Provedor real nos testes | Não está disponível e não permite controlar falhas e atrasos de forma isolada. |

## Consequências e limitações

A mesma implementação HTTP atende demonstração e testes, com sucesso padrão
simples e erros configurados explicitamente. Os três serviços locais são `app`,
`postgres` e `activation-provider-mock`. Mappings e contrato assumido precisam
permanecer consistentes com a ADR-0002.

WireMock não garante idempotência, autenticação, capacidade ou comportamento real
de concorrência do provedor. Trocar apenas a URL só é suficiente para um destino
de contrato compatível; outro contrato pode exigir adaptar o cliente. As regras
de retry e resultado ambíguo seguem a [ADR-0006](0006-resiliencia-sem-circuit-breaker.md).

## Evidências no repositório

- `ActivationProviderClientArchitectureTests`: independência do contrato de saída em relação a frameworks e transporte.
- `HttpActivationProviderWireMockTests` e `HttpActivationProviderCorrelationTests`: corpo, método/caminho, correlação, respostas e falhas de comunicação, incluindo timeout real.
- `HttpActivationProviderRetryTests` e `ActivationRequestHttpRetryTests`: contrato opt-in, interpretação de `Retry-After` e efeito sobre agendamento e chamadas reais.
- `ActivationRequestProcessingIntegrationTests`: worker, WireMock e PostgreSQL no fluxo de criação e consulta.
- `docker-compose.yaml`, mappings e instruções do README: configuração da demonstração local.

Essas referências identificam configuração e cobertura existentes; não registram
execução do Compose ou da suíte nesta revisão documental.

## Quando reavaliar

Quando houver contrato oficial, atualizar primeiro as suposições na ADR-0002 e
os testes do adaptador, incluindo autenticação, idempotência e consulta de status
quando disponíveis. Manter detalhes administrativos da simulação fora do domínio.
