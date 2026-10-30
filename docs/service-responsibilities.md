# Responsabilidades da camada de serviços

O pacote `service` reúne os serviços de aplicação e seus limites transacionais.
Os tipos que apenas transportam dados ficam em `service.model`; o cálculo de
atrasos de execução fica em `service.policy`.

| Classe | Responsabilidade |
| --- | --- |
| `ActivationRequestService` | Consultar solicitações no escopo do consumidor e coordenar a criação idempotente, incluindo a recuperação de conflitos de unicidade. |
| `ActivationRequestCreationTransaction` | Inserir e mapear uma solicitação em transação independente, permitindo rollback antes da releitura de um conflito. |
| `ActivationRequestClaimService` | Adquirir a posse de uma solicitação elegível e retornar a versão e o lease persistidos. |
| `ActivationRequestAttemptService` | Preparar uma tentativa antes do HTTP e registrar resultados ambíguos, preservando a exigência de reconciliação. |
| `ActivationRequestOutcomeService` | Persistir resultados confirmados e solicitar à política o atraso de uma falha transitória segura. |
| `ActivationRequestLeaseRecoveryService` | Liberar posses vencidas em lotes, sem inferir o resultado externo nem remover a proteção de reconciliação. |
| `service.model.ClaimedActivationRequest` | Transportar uma fotografia imutável da posse para a preparação condicional. |
| `service.model.PreparedActivationAttempt` | Transportar a tentativa registrada e sua versão para proteger a gravação do resultado. |
| `service.model.CreateActivationRequestResult` | Distinguir criação de reutilização, para o controller escolher a resposta HTTP. |
| `service.policy.ActivationRetryPolicy` | Validar a configuração e calcular o backoff exponencial com teto e jitter, sem acessar o banco. |

As classes com sufixo `Service` representam serviços de aplicação. O nome
`ActivationRequestCreationTransaction` foi preservado porque explicita sua função
transacional. Seu bean separado permite que a inserção passe pelo proxy Spring
com `REQUIRES_NEW`; incorporá-la ao serviço chamador comprometeria essa fronteira.

## Dependências

Entre os componentes analisados, as únicas dependências por injeção são
`ActivationRequestService -> ActivationRequestCreationTransaction` e
`ActivationRequestOutcomeService -> ActivationRetryPolicy`. Os demais serviços
dependem diretamente do repositório; os serviços de criação também usam o mapper.
Os records não dependem dos serviços e a política não depende de nenhum serviço.
Não há dependências circulares entre esses componentes.

## Responsabilidades próximas

O registro de resultados está dividido entre `ActivationRequestAttemptService`
(ambíguos) e `ActivationRequestOutcomeService` (confirmados). Há proximidade no
tema, mas os efeitos são distintos: o primeiro mantém a reconciliação obrigatória;
o segundo pode removê-la e concluir a solicitação ou agendar nova tentativa.
A divisão foi mantida para preservar esses caminhos e seus contratos existentes.

A preparação também registra preventivamente a incerteza antes da chamada HTTP;
isso não equivale a classificar um resultado confirmado. A recuperação de lease
apenas libera a posse, sem assumir as responsabilidades de nenhum desses serviços.

Os dois serviços de resultado repetem a validação do código de erro e a verificação
do número de linhas afetadas. Essa duplicação pequena não justifica um novo
serviço, uma superclasse ou um utilitário compartilhado nesta refatoração.

As consultas e a criação permanecem juntas em `ActivationRequestService`, pois
operam sobre o mesmo caso de uso público e escopo de consumidor. Não foi criada
uma separação adicional de serviços de leitura e escrita.
