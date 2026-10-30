# Configuração do Resource Server JWT

A aplicação usa Spring Security OAuth 2.0 Resource Server com JWT e
`SessionCreationPolicy.STATELESS`. Cada chamada protegida precisa de um Access
Token no header `Authorization: Bearer`. O consumidor obtém seu token por Client
Credentials em um emissor externo; credenciais de clientes ficam com os próprios
consumidores. Não há Authorization Server, login, refresh ou logout na API.

## Fluxo M2M / Client Credentials

O emissor M2M e o cofre de segredos de produção ainda não estão definidos.
O fluxo abaixo descreve o contrato esperado, independente de fornecedor; não
exige conta, SDK, credenciais ou chamadas reais a Cognito/AWS. O único emissor
com contrato completo implementado nesta etapa é o utilitário local.

```mermaid
sequenceDiagram
    participant C as Serviço consumidor
    participant E as Emissor OAuth externo
    participant A as AtivaFlow API
    C->>E: POST token endpoint (client_credentials + escopos)
    Note over C,E: Cliente se autentica no emissor; conexão HTTPS
    E-->>C: access_token JWT, token_type, expires_in
    C->>A: Authorization: Bearer JWT
    Note over A: Valida JWT, sub, escopo e propriedade
    A-->>C: Resposta da operação ou Problem Details
```

1. A equipe responsável pelo emissor cadastra um cliente confidencial por
   consumidor e ambiente, autoriza os escopos mínimos e entrega a credencial
   pelo cofre escolhido. A identidade `sub` deve ser estável e compatível com
   o contrato de proprietário da API.
2. O backend consumidor autentica-se no token endpoint contratado e solicita
   `grant_type=client_credentials`, com `scope` separado por espaços. Não há
   usuário interativo, redirecionamento ou senha de usuário nesse fluxo.
3. O emissor autentica o cliente, limita os escopos concedidos e emite o Access
   Token. O consumidor usa o token até próximo da expiração e solicita outro
   pelo mesmo grant. Não se prevê refresh token neste fluxo, conforme a
   [RFC 6749, seção 4.4](https://www.rfc-editor.org/rfc/rfc6749#section-4.4).
4. O consumidor envia somente o Bearer à API, por HTTPS fora do ambiente local,
   segundo a [RFC 6750, seção 2.1](https://www.rfc-editor.org/rfc/rfc6750#section-2.1).
   `client_secret` não é enviado à API nem ao provedor de ativação.

Exemplo de protocolo para um emissor que adote `client_secret_basic` (placeholders,
não é um endpoint implementado nem um comando para executar):

```http
POST /oauth2/token HTTP/1.1
Host: emissor.example
Authorization: Basic <credencial-do-cliente-codificada-conforme-OAuth>
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&scope=requests%2Fread%20requests%2Fwrite
```

```json
{
  "access_token": "<JWT-assinado-pelo-emissor>",
  "token_type": "Bearer",
  "expires_in": 300,
  "scope": "requests/read requests/write"
}
```

Endpoint, método de autenticação do cliente e duração acima são ilustrativos.
Basic, quando contratado, autentica o cliente **no emissor**; o HTTP Basic da
AtivaFlow continua desativado. `aud` e finalidade seguem o contrato do emissor;
não se presume que um parâmetro `audience` seja aceito no token endpoint.
Credenciais e respostas com token devem ficar fora de logs, histórico de shell,
URLs, repositório e artefatos de CI. Base64 não protege um segredo.

## Emissor externo

O Spring Boot recebe as propriedades nativas por variáveis de ambiente:

| Variável | Uso |
| --- | --- |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI` | Emissor confiável, igual ao `iss` do token. Obrigatório no roteiro suportado. |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI` | URL opcional do JWKS; quando definida, evita discovery do emissor. Mantenha também o issuer para validar `iss`. |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES` | Audience(s) aceitas, separadas por vírgula. Obrigatórias no perfil `local`; o token precisa conter ao menos uma delas em `aud`. |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_PUBLICKEYLOCATION` | Arquivo de chave pública PEM X.509 para desenvolvimento local, como `file:/tmp/ativaflow-jwt/public.pem`. |

Com um emissor externo, configure `ISSUERURI` e, conforme seu contrato,
`AUDIENCES`; informe `JWKSETURI` quando necessário. Deixe `PUBLICKEYLOCATION`
desconfigurada nesse modo. A autoconfiguração do Boot fornece o decoder e a
validação de assinatura, `iss`, `exp` e `nbf` quando presentes. O algoritmo padrão
é RS256. Emissor e audience devem vir do contrato real, sem valores de produção
fixados no código.

Fora do perfil `local`, esta configuração externa continua sendo apenas a base
de autenticação. O suporte padrão a JWT não distingue sozinho ID Token de Access
Token nem exige a presença de `exp`. A adoção de um emissor externo requer
implementar seu contrato de finalidade e de `aud`/`client_id`.

Antes de habilitar um emissor em produção, definir e validar com tokens de
teste: issuer exato, JWKS HTTPS, algoritmo aceito, audience da API, finalidade de
Access Token, presença de `exp`, duração máxima, escopos e identidade estável em
`sub`. O `token_use=access` local não garante compatibilidade com outro emissor.
Se o emissor não fornecer o `sub` contratado, a integração exige uma decisão e
adaptação explícitas; configurar apenas URLs não resolve essa diferença.

A validação é feita na API com chave pública, sem introspecção por requisição e
sem usar `client_secret`. PEM não precisa de rede para validar; JWKS depende da
busca de chaves e do cache do decoder. Informar `JWKSETURI` evita discovery,
mas não elimina a consulta ao JWKS quando necessária. Não se deve aceitar uma
URL de chaves fornecida pelo próprio token ou pela requisição do consumidor.

## Contrato do emissor local de demonstração

O emissor escolhido nesta etapa é somente o local, implementado pelo utilitário
`scripts/LocalJwt.java`. O perfil `local` aplica o mesmo contrato aos modos PEM e
JWKS, usando `LocalJwtDecoderConfiguration`:

| Verificação | Regra |
| --- | --- |
| Assinatura | RSA RS256 com a chave pública configurada ou publicada no JWKS. Tokens sem assinatura, adulterados ou com outro algoritmo são rejeitados. |
| `iss` | Obrigatório e exatamente igual ao issuer configurado. Não há discovery do issuer local. |
| `exp` | Obrigatório e não expirado, considerada a tolerância temporal abaixo. |
| `nbf` | Opcional; quando presente, não pode estar no futuro além da tolerância. |
| `aud` | Obrigatório e contém ao menos uma audience configurada da API. A configuração não aceita lista vazia ou entradas em branco. |
| Finalidade | Claim `token_use` obrigatório, do tipo string e exatamente `access`. ID Tokens, refresh tokens e tokens sem finalidade explícita são rejeitados. |

O contrato local usa `aud` para identificar a API; não usa `client_id` como
substituto de audience. A identidade do consumidor permanece em `sub`, como já
esperado pelos controllers. O utilitário emite `aud=ativaflow-api` e
`token_use=access` na criação e na renovação. Esse claim é uma escolha do contrato
local, não uma implementação de Cognito.

A tolerância de relógio padrão do Spring Security é de 60 segundos para `exp` e
`nbf`. Os validadores adicionais preservam as validações temporais padrão. Tokens
inválidos em rotas protegidas passam pelo tratamento Bearer existente e retornam
401 com Problem Details seguro.

É obrigatório configurar issuer, audience e exatamente uma fonte de chave
(PEM ou JWKS). A ausência dessas propriedades impede a inicialização local,
inclusive quando há somente um issuer configurado. Tokens locais antigos sem
`token_use=access` precisam ser renovados pelo utilitário atualizado.

O POST `/api/v1/requests` exige a authority `SCOPE_requests/write`. Os GETs
`/api/v1/requests` e `/api/v1/requests/{id}` exigem `SCOPE_requests/read`.
O conversor padrão do Spring adiciona `SCOPE_` aos escopos do JWT. O utilitário
local emite `scope="requests/read requests/write"` nos comandos `create` e `token`;
renove tokens antigos sem escopos para usar os endpoints. Um token válido sem o
escopo necessário recebe `403` com Problem Details e desafio Bearer
`insufficient_scope`; a ausência de autenticação recebe `401`.
A identidade do consumidor segue o contrato de `sub` descrito abaixo.

Sem issuer, JWKS ou chave pública que permita configurar um decoder, a aplicação
falha na inicialização. Não há fallback que libere acesso sem validação.

## Identidade e proprietário da solicitação

Em todos os perfis, `ConsumerJwtAuthenticationConverter` executa após a validação
do token pelo decoder e define explicitamente `Authentication.getName()` pelo
`sub` original do JWT. O valor deve ser uma string não branca de até 100 unidades
UTF-16, compatível com a factory de domínio. O identificador é preservado
exatamente, incluindo maiúsculas e espaços; não é normalizado nem truncado.

O conversor inspeciona o tipo original no payload já validado porque Nimbus
converte `sub` numérico em string durante o decode. Assim, `sub: 123` é rejeitado,
enquanto `sub: "123"` identifica o consumidor textual `123`. Essa inspeção não
substitui a validação de assinatura, issuer, finalidade e datas pelo decoder.

`sub` ausente, vazio, branco, não textual ou acima do limite retorna `401` com
Problem Details seguro e desafio Bearer `invalid_token`, antes de acessar os
serviços ou o banco. Não há fallback para `client_id`, `consumerId`, `owner` ou
identidade padrão. A troca de claim exige um contrato explícito com o emissor.
Os escopos continuam sendo convertidos para authorities com prefixo `SCOPE_`.

Os controllers usam esse nome autenticado na criação, consulta e listagem; os
serviços aplicam o proprietário nas consultas ao repositório e na factory de
domínio. Um recurso de outro consumidor retorna `404`. Headers e query params
não alteram a identidade. O body de criação aceita somente `externalId`,
`customerId` e `productCode`; tentativas de incluir `consumerId`, `consumer_id`,
`owner`, `sub` ou `client_id` recebem `400`, sem escrita.

### Matriz de autorização

| Recurso | Autorização atual |
| --- | --- |
| `POST /api/v1/requests` | JWT válido, `SCOPE_requests/write`; proprietário vem de `sub`. |
| `GET /api/v1/requests` | JWT válido, `SCOPE_requests/read`; conteúdo e contagem restritos ao consumidor. |
| `GET /api/v1/requests/{id}` | JWT válido, `SCOPE_requests/read`; recurso alheio ou inexistente retorna `404`. |
| Swagger UI, OpenAPI e health/probes | Acesso anônimo permitido pela configuração de segurança. |
| `GET /actuator/prometheus` | JWT válido, `SCOPE_monitoring/read`; identidade exclusiva do coletor, sem escopos de negócio. |
| Demais rotas | Autenticação obrigatória; não há escopo específico adicional configurado. Os demais endpoints administrativos do Actuator não estão expostos. |

Escopo concede a operação, mas não acesso aos dados de outro consumidor. A
listagem sem registros acessíveis retorna uma página vazia. A existência de uma
regra de segurança não implica que o endpoint esteja implementado ou exposto.
O emissor deve conceder `monitoring/read` somente à identidade de monitoramento;
`requests/read` e `requests/write` não permitem coleta. A política de rede,
renovação e configuração do coletor está em [acesso ao Prometheus](../README.md#acesso-ao-prometheus).

## Respostas de autenticação e autorização

`SecurityProblemHandler` implementa `AuthenticationEntryPoint` para falhas de
autenticação e `AccessDeniedHandler` para acesso negado. `SecurityConfiguration`
registra ambos em `exceptionHandling` e em `oauth2ResourceServer`, cobrindo os
erros do filtro Bearer e da autorização. O advice MVC deixa essas exceções
propagarem para a cadeia de segurança, que mantém a semântica OAuth.

| Situação | Status | `WWW-Authenticate` | `detail` |
| --- | --- | --- | --- |
| Sem autenticação em recurso protegido | 401 | `Bearer` | `Authentication is required` |
| JWT inválido ou expirado | 401 | `Bearer error="invalid_token"` | `Authentication is required` |
| JWT autenticado com escopo insuficiente | 403 | Bearer com `error="insufficient_scope"` | `Access is denied` |
| Falha de protocolo OAuth `invalid_request` | 400 | `Bearer error="invalid_request"` | `Invalid authentication request` |

Os delegates nativos `BearerTokenAuthenticationEntryPoint` e
`BearerTokenAccessDeniedHandler` definem o status e o desafio OAuth. Na
autenticação, as descrições e URIs do erro original são removidas antes da
delegação para evitar exposição de detalhes do decoder. O código OAuth e o
status são preservados. O handler de autorização mantém os metadados padrão do
Spring para `insufficient_scope`.

O corpo usa `Content-Type: application/problem+json`, inclusive com
`Accept: text/html`, e contém `type`, `title`, `status`, `detail`, `instance` e
`correlationId`. O `instance` contém somente o caminho, sem query string, e a
correlação corresponde ao header `X-Correlation-ID`. Não há redirecionamento
para login nem mensagens internas de exceções na resposta.

## Execução local com PEM ou JWKS

O [roteiro local](local-jwt.md) usa `scripts/LocalJwt.java` (Java 21) para
produzir chaves RSA 2048, JWKS público e tokens RS256 de cinco minutos em diretório
temporário fora do Git, com permissões restritas. Inclui os modos PEM e JWKS,
chamadas válidas de criação/consulta, renovação e limpeza do material.

Com o perfil `local` e `PUBLICKEYLOCATION` definida, `LocalJwtDecoderConfiguration`
carrega a chave pública RSA e aplica o contrato local completo descrito acima.
Issuer e audience são obrigatórios também nesse modo. Uma chave
ausente ou inválida impede a inicialização. Configurar PEM e JWKS simultaneamente
também impede a inicialização. A aplicação não gera chaves nem lê a chave privada.

No modo JWKS, deixe `PUBLICKEYLOCATION` desconfigurada e informe `JWKSETURI`,
issuer e audience. No perfil `local`, o decoder é fornecido pela mesma
`LocalJwtDecoderConfiguration`, preservando a exigência de `exp` e finalidade.
O servidor local publica somente o subdiretório `jwks/`, sem expor a chave privada
ou o header Bearer.

## Origem, custódia e rotação de segredos

Esta seção define o procedimento operacional esperado. Ainda faltam escolher
o emissor e o cofre de produção, nomear os responsáveis operacionais e definir
a periodicidade e os prazos de revogação. O projeto não integra automaticamente
um cofre nem implementa rotação ou recarga dinâmica de credenciais.

| Material | Origem e responsável esperado | Onde é usado / entregue |
| --- | --- | --- |
| `client_id` / `client_secret` M2M | Cadastro pelo responsável do emissor; custódia pelo responsável do serviço consumidor. `client_id` é identificador, não segredo. | Cofre e runtime do consumidor; não são propriedades da AtivaFlow. |
| Chave privada de assinatura JWT externa | Gerada e rotacionada pelo operador do emissor. | Permanece sob custódia do emissor; nunca é entregue à API. |
| Chave pública PEM / JWKS, issuer e audiences | Contrato e publicação pelo operador do emissor; configuração pela equipe da API. São dados públicos/de confiança, não segredos. | Decoder da API, por propriedades `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_*`. A integridade da configuração deve ser protegida. |
| Access Token | Emitido pelo emissor para o consumidor; é credencial Bearer temporária. | Memória ou arquivo protegido do consumidor e header HTTPS para a API; não vai para o provedor de ativação. |
| `DB_PASSWORD` | Provisionada pelo administrador do PostgreSQL. | Em desenvolvimento, `.env` ignorado pelo Git; em produção, injetada pelo mecanismo de entrega de segredos escolhido. |
| `ACTIVATION_PROVIDER_API_KEY` | Se exigida, provisionada pelo operador do provedor. | Reserva de configuração, vazia por padrão; o cliente HTTP atual não a envia. A autenticação real depende do [contrato do provedor](adr/0002-contrato-minimo-provedor.md). |
| `private.pem` e Bearer de demonstração | Gerados pelo JDK no utilitário local, sob responsabilidade do desenvolvedor. | Diretório temporário fora do Git, arquivos `600` e diretórios `700`; a API lê apenas a chave pública. |

`.env.example` contém nomes e valores ilustrativos, sem credenciais reais. O
Spring Boot não carrega `.env` automaticamente. Em produção, o mecanismo de
deploy deve obter os valores do cofre escolhido e entregá-los ao processo com
acesso restrito; uma variável de ambiente é um meio de entrega, não um cofre.
Não incluir segredos em imagens, manifests versionados, respostas de erro,
prints, logs ou relatórios de testes. Restringir também o acesso ao `.env` local.

### Rotacionar a credencial do cliente M2M

1. O responsável pelo emissor gera uma nova credencial para o mesmo cliente,
   preservando `sub` e escopos autorizados. Se houver coexistência de segredos,
   manter o anterior somente durante a migração; caso contrário, planejar a
   janela coordenada de troca conforme o contrato do emissor.
2. Publicar a nova versão no cofre e atualizar/reiniciar as instâncias do
   consumidor conforme seu mecanismo de carga. A AtivaFlow não recebe essa
   credencial e não precisa reiniciar por essa troca.
3. Confirmar emissão de novo token e acesso autorizado, sem registrar o token;
   então revogar a credencial antiga no emissor e confirmar que ela não emite
   novos tokens. Registrar apenas versão, responsável, horário e resultado.

Trocar `client_secret`, desabilitar o cliente ou reduzir seus escopos no emissor
não invalida imediatamente JWTs já emitidos nesta API. Eles podem continuar
válidos até `exp`, considerada a tolerância temporal, enquanto a assinatura e
as demais regras forem aceitas. Não há introspecção, denylist nem endpoint de
revogação/logout na AtivaFlow. Um novo cadastro com outro `sub` também muda o
proprietário dos dados; não é uma rotação transparente.

### Rotacionar a chave de assinatura JWT

Para um emissor externo com JWKS, o procedimento esperado é publicar a nova
chave pública com novo `kid`, mantendo a anterior, e só então começar a assinar
com a nova chave. Confirmar que as instâncias da API aceitam o novo `kid` e que
tokens antigos ainda válidos continuam funcionando. Manter a chave antiga
publicada até expirar o último token assinado por ela, acrescido da tolerância
de relógio e da janela de propagação/cache acordada; depois removê-la.
O decoder usa cache de JWKS; a atualização não é uma notificação instantânea
entre instâncias. Ver [comportamento do Resource Server](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html#oauth2resourceserver-jwt-runtimeexpectations).

No modo PEM local, a chave é carregada na criação do decoder e não há recarga
automática nem suporte configurado a dois PEMs. Gere novo material fora do Git,
atualize `PUBLICKEYLOCATION` e reinicie a API; os tokens assinados com a chave
anterior deixam de ser aceitos por essa instância. O utilitário publica apenas
uma chave no JWKS e não implementa a transição com duas chaves. O
[roteiro local](local-jwt.md#renovação-de-token-e-rotação-de-chave) descreve essa troca.

Em comprometimento, revogar imediatamente a credencial no sistema de origem,
atualizar os consumidores e avaliar os tokens já emitidos. Remover uma chave do
JWKS não garante bloqueio imediato por causa dos caches; uma contenção urgente
precisa atualizar o material confiável de todas as instâncias e verificar a
rejeição, considerando o impacto nos clientes legítimos. O prazo máximo de
exposição e o procedimento de emergência devem integrar o contrato de produção.

Para senha de banco ou futura credencial do provedor, coordenar a troca no
sistema de origem com a atualização do cofre e o reinício/redeploy da API.
Validar conectividade antes de retirar a versão anterior quando houver suporte
a coexistência. Não presumir que alterar o cofre atualiza o processo em execução.

## Verificação da segurança

Os comandos abaixo são referências para execução direcionada dos testes
unitários existentes em tarefas de implementação. Atualizações exclusivamente
documentais dispensam executar essas suítes. Nenhum deles exige Cognito/AWS real;
o modo PEM do roteiro local também dispensa qualquer servidor de identidade.
O utilitário gera JWTs diretamente e não simula o token endpoint nem a
autenticação por `client_secret`.

Para executar somente os testes unitários criados para a passagem de exceções
do MVC aos handlers de segurança:

```bash
bash ./mvnw -Dtest=SecurityMvcProblemContractUnitTests test
```

Usam o advice, os handlers e o filtro de tradução de exceções reais em memória.
Cobrem 401 por falha de autenticação ou acesso anônimo, 401 com `invalid_token`
e 403 com `insufficient_scope`, nos formatos solicitados JSON, Problem JSON e
HTML. Validam o corpo Problem Details completo, a correlação e a ausência de
detalhes internos e redirecionamentos, sem iniciar Boot, banco, Docker ou rede.

Para executar somente os testes unitários de identidade e propriedade:

```bash
bash ./mvnw -Dtest=JwtConsumerOwnershipUnitTests test
```

Usam JWTs realmente assinados, a cadeia de filtros, controllers e serviços reais
em memória, substituindo apenas o repositório. Cobrem identidade inválida nas
três operações, preservação do proprietário, tentativa de atribuição pelo body,
isolamento entre consumidores e exigência de escopo. Não iniciam Boot, banco,
Docker ou rede.

Para executar somente os testes unitários de isolamento BOLA:

```bash
bash ./mvnw -Dtest=ActivationRequestBolaUnitTests,ActivationRequestConsumerQueryUnitTests test
```

`ActivationRequestBolaUnitTests` usa controller, serviço e mapper reais com um
principal JWT já autenticado e repositório substituído. Verifica acesso pelo
proprietário, resposta `404` idêntica para recurso alheio ou inexistente,
parâmetros de proprietário forjados, filtros opcionais, paginação e repasse dos
totais restritos ao consumidor, inclusive quando não há registros acessíveis.
As duas identidades têm o mesmo escopo de leitura.

`ActivationRequestConsumerQueryUnitTests` executa a construção real da
`Specification` e inspeciona a fronteira Criteria: o predicado de `consumerId`
é obrigatório nas quatro combinações de filtros e se combina com `status` e
`customerId` usando AND. Esses testes não iniciam Boot, banco, Docker ou rede.
A execução de SQL e da contagem no PostgreSQL permanece coberta pelos testes de
integração existentes em `ActivationRequestQueryTests`.

Testes unitários criados para o contrato do Access Token local:

```bash
bash ./mvnw -Dtest=LocalAccessTokenValidationUnitTests,LocalAccessTokenMaterialUnitTests test
```

Usam tokens realmente assinados nos modos PEM e JWKS, incluindo finalidade
ausente/incorreta, `exp` ausente, issuer, audience, `nbf`, assinatura e algoritmo
inválidos. O transporte HTTP do JWKS é substituído em memória; nenhum servidor de
rede, banco ou Docker é iniciado. O teste do utilitário cria e renova tokens em
diretório temporário fora de qualquer checkout Git.

Testes anteriores da configuração e da cadeia de filtros:

```bash
bash ./mvnw -Dtest=ResourceServerSecurityConfigurationUnitTests,LocalJwtDecoderUnitTests test
```

Essas classes verificam a cadeia real de filtros e o decoder local,
incluindo tokens assinados, expiração, `nbf`, issuer, audience, assinatura,
ausência de sessão/CSRF, rejeição de Basic e erros seguros. Os testes de integração
existentes usam identidades simuladas e um issuer fictício no perfil `test`;
o decoder lazy não consulta esse endereço sem receber um Bearer.

Para executar somente os testes do utilitário e da configuração de fontes de
chave:

```bash
bash ./mvnw -Dtest=LocalJwtMaterialUnitTests,LocalJwtKeySourceUnitTests test
```

Referências: [JWT Resource Server no Spring Security 6.5](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html)
e [propriedades do Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/appendix/application-properties/index.html).
