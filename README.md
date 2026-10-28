# AtivaFlow API

Projeto Java 21 com Spring Boot 3.5.16, organizado em camadas para uma API REST.
A base atual reúne dependências, configurações por ambiente, estrutura de pacotes,
migrações, entidade JPA com transições de estado, auditoria e controle otimista,
e DTOs públicos como records com Bean Validation na entrada.
Os pacotes de aplicação possuem suas responsabilidades descritas em
`package-info.java`; endpoints e integração com o provedor ainda estão pendentes.

## Tecnologias e dependências

As dependências e os plugins de build estão definidos no `pom.xml`:

| Área | Componentes |
| --- | --- |
| API HTTP | Spring Web e Bean Validation |
| Documentação da API | Springdoc OpenAPI e Swagger UI |
| Persistência | Spring Data JPA, PostgreSQL e Flyway |
| Segurança | Spring Security e OAuth2 Resource Server |
| Monitoramento | Spring Boot Actuator e Micrometer com registro Prometheus |
| Mapeamento e geração de código | MapStruct e Lombok |
| Desenvolvimento | Spring Boot DevTools e Maven Wrapper |
| Testes | Spring Boot Test, Spring Security Test, Testcontainers, WireMock, ArchUnit e Awaitility |

A presença dessas dependências não implica que todas as funcionalidades estejam
implementadas ou expostas pela aplicação.

## Pacotes

Pacote base: `com.rensilver.ativaflow_api`.

| Pacote | Responsabilidade definida |
| --- | --- |
| `config` | Configurações Spring e propriedades da aplicação. |
| `controller` | Endpoints REST, validação de entrada e respostas HTTP. |
| `service` | Orquestração dos casos de uso e delimitação de transações. |
| `repository` | Acesso a dados com Spring Data JPA e operações de persistência. |
| `domain` | Regras, estados e resultados de negócio. |
| `domain.exception` | Exceções de regras e transições de domínio. |
| `model` | Entidades JPA e mapeamentos de persistência. |
| `dto.request` | Contratos de entrada da API. |
| `dto.response` | Contratos de saída da API. |
| `mapper` | Conversões entre entidades e DTOs com MapStruct. |
| `client.provider` | Contrato e implementação HTTP da integração com o provedor. |
| `client.provider.dto` | Contratos exclusivos da integração com o provedor. |
| `exception` | Tratamento HTTP centralizado de erros. |
| `security` | Autenticação, autorização e identidade do consumidor. |
| `worker` | Agendamento e processamento de trabalho persistido. |
| `observability` | Correlação, métricas e suporte a tracing. |

`AtivaflowApiApplication.java` contém o ponto de entrada da aplicação.

## Arquivos de configuração

| Arquivo | Finalidade |
| --- | --- |
| `pom.xml` | Dependências, versão do Java, plugins e processadores de anotações. |
| `mvnw` e `mvnw.cmd` | Execução do Maven Wrapper em Unix e Windows. |
| `.mvn/wrapper/maven-wrapper.properties` | Versão e distribuição do Maven utilizadas pelo Wrapper. |
| `src/main/resources/application.yaml` | Configuração compartilhada: porta, Flyway, JPA, pool de conexões, timeouts HTTP e desligamento gracioso. |
| `src/main/resources/application-local.yaml` | Conexão com PostgreSQL e URL do provedor no ambiente local. |
| `src/main/resources/application-prod.yaml` | Configuração por variáveis de ambiente e desativação do DevTools em produção. |
| `src/test/resources/application-test.yaml` | Configuração do provedor para o perfil de testes. |
| `.env.example` | Modelo de variáveis para execução local. |
| `.github/workflows/ci.yaml` | Build e testes no GitHub Actions. |
| `.gitignore` | Exclusões de build, arquivos de IDE e configurações locais de ambiente. |
| `.gitattributes` | Padronização de finais de linha dos scripts do Maven Wrapper. |

O Hibernate está configurado com `ddl-auto: validate` e `open-in-view: false`.
O Flyway está habilitado explicitamente na configuração compartilhada e aplica
as migrações de `classpath:db/migration` na inicialização, antes da validação do
Hibernate. Com `validate-on-migrate: true`, divergências no histórico de migrações,
como checksums alterados, impedem a inicialização. O Hibernate apenas valida os
mapeamentos JPA contra o schema; alterações de estrutura devem ser feitas por
novas migrações. Com `open-in-view: false`, o contexto de persistência não permanece
aberto durante toda a requisição HTTP; o carregamento de associações lazy deve
ocorrer dentro da transação da aplicação.

O `baseline-on-migrate: false` impede a criação automática de um baseline em um
schema não vazio sem histórico Flyway. Bancos existentes nessa condição precisam
de uma estratégia explícita de adoção antes de iniciar a aplicação.

A migração `V1__create_activation_requests.sql` cria a tabela `activation_requests`:

| Coluna | Tipo e regras |
| --- | --- |
| `id` | UUID, chave primária; gerado pela aplicação. |
| `external_id` | `VARCHAR(100)`, obrigatório e único globalmente. |
| `customer_id` | `VARCHAR(100)`, obrigatório; identificador textual do cliente. |
| `product_code` | `VARCHAR(100)`, obrigatório. |
| `status` | `VARCHAR(20)`, obrigatório; aceita `PENDING` (padrão), `PROCESSING`, `COMPLETED` e `FAILED`. |
| `created_at` | `TIMESTAMP WITH TIME ZONE`, obrigatório; padrão `CURRENT_TIMESTAMP`. |
| `updated_at` | `TIMESTAMP WITH TIME ZONE`, obrigatório; padrão `CURRENT_TIMESTAMP`. |

Os nomes em `snake_case` correspondem aos campos `externalId`, `customerId`,
`productCode`, `createdAt` e `updatedAt` do contrato da API. O limite adotado para
os três identificadores textuais é de 100 caracteres. As datas representam
instantes, compatíveis com `Instant` e exposição em UTC. O padrão de `updated_at`
vale apenas para inserções; a aplicação deverá atualizá-lo a cada alteração,
sem trigger no banco. A entidade aplica as transições de status descritas abaixo.

A migração `V2__add_activation_request_owner_and_processing_metadata.sql`
adiciona os campos internos abaixo, preservando a V1 e o contrato público:

| Coluna interna | Tipo e finalidade |
| --- | --- |
| `consumer_id` | `VARCHAR(100)`, obrigatório, sem padrão; rejeita texto vazio ou composto apenas por espaços em branco. Identifica o consumidor proprietário da solicitação. |
| `version` | `BIGINT`, obrigatório, padrão `0`, não negativo; controle de concorrência otimista com `@Version`. |
| `attempt_count` | `INTEGER`, obrigatório, padrão `0`, não negativo; quantidade de tentativas de envio ao provedor. |
| `next_attempt_at` | `TIMESTAMP WITH TIME ZONE`, opcional; instante da próxima tentativa, quando houver retry agendado. |
| `lease_until` | `TIMESTAMP WITH TIME ZONE`, opcional; vencimento da posse temporária do processamento. |
| `claimed_by` | `VARCHAR(100)`, opcional; identificador da instância/worker que assumiu o trabalho. |
| `last_error_code` | `VARCHAR(100)`, opcional; código categorizado da última falha, sem payload, credenciais ou resposta bruta do provedor. |
| `reconciliation_required` | `BOOLEAN`, obrigatório, padrão `false`; indica resultado externo indeterminado que exige reconciliação. |

`consumer_id` é diferente de `customer_id`: o primeiro identifica o sistema
consumidor autenticado; o segundo identifica o cliente informado no pedido.
A derivação de `consumer_id` da identidade confiável do JWT (por exemplo,
`client_id`) será implementada na etapa de autenticação. Ele não poderá ser
aceito do payload. Os DTOs públicos aceitam apenas `externalId`,
`customerId` e `productCode` na criação e expõem apenas `id`, `externalId`,
`customerId`, `productCode`, `status`, `createdAt` e `updatedAt` nas respostas.
Nenhuma das oito colunas internas deverá ser exposta na API.

O schema, a entidade JPA e os DTOs estão implementados e cobertos por testes.
Autenticação, endpoints, worker, retries, lease e reconciliação ainda estão
pendentes. Os campos opcionais começam nulos; a sinalização de reconciliação
permitirá manter um resultado ambíguo em
`PROCESSING`, sem adicionar estados públicos. O limite de tentativas será
aplicado pelo processamento; a constraint do banco impede somente contagens
negativas. A unicidade de `external_id` continua global, inclusive entre
consumidores. Índices para consultas/claim serão definidos quando essas
operações forem implementadas.

`model.ActivationRequest` mapeia todas as colunas de V1/V2, com acesso JPA pelos
campos e construtor sem argumentos `protected`, compatível com o Hibernate.
O construtor público recebe `externalId`, `customerId`, `productCode` e
`consumerId`, exige texto não vazio de até 100 caracteres, preserva os valores
sem normalização, gera o UUID na aplicação e inicia em `PENDING`. O proprietário
deve ser fornecido pela camada de autenticação confiável, nunca pelo payload.

A entidade expõe getters, sem setters públicos ou builder. As mudanças de estado
ocorrem por `startProcessing()` (`PENDING → PROCESSING`), `complete()`
(`PROCESSING → COMPLETED`) e `fail(errorCode)` (`PROCESSING → FAILED`).
Transições inválidas lançam `InvalidActivationStatusTransitionException`;
estados terminais não podem ser reabertos. `fail` exige texto não vazio de até
100 caracteres para `last_error_code`; o chamador deve fornecer apenas um código
categorizado, sem resposta bruta do provedor. As operações de retry, claim e reconciliação serão adicionadas
com suas próprias regras; esta entidade ainda não implementa o processamento.

`createdAt` e `updatedAt` usam `Instant`, com `@CreatedDate`, `@LastModifiedDate`
e `AuditingEntityListener`. `JpaAuditingConfiguration` habilita a auditoria com
um `DateTimeProvider` baseado em `Clock.systemUTC()`. As datas são preenchidas
na persistência; `updatedAt` também muda quando uma alteração é enviada ao banco,
e `createdAt` não é atualizável. Identidade e campos de criação também não são
atualizáveis. Todos os horários internos usam `Instant`, compatível com as colunas
`TIMESTAMP WITH TIME ZONE`, sem depender do fuso local da JVM ou da sessão SQL.

O campo `version` é `Long` com `@Version`: permanece nulo antes da primeira
persistência (permitindo ao Spring Data reconhecer uma entidade nova mesmo com
UUID atribuído), recebe `0` na inserção e é incrementado pelo Hibernate nas
atualizações. Gravações com versão desatualizada são rejeitadas por lock otimista.
Atualizações SQL diretas, como o futuro claim do worker, precisarão controlar
explicitamente a versão e a auditoria, pois não passam pelos callbacks JPA.

A V2 pressupõe a tabela `activation_requests` vazia, conforme o cenário atual
de desenvolvimento. Não há proprietário padrão nem backfill inferido de
`customer_id` ou `external_id`. Se a V1 tiver registros, a adição de
`consumer_id NOT NULL` falhará: será necessário planejar a atribuição dos
proprietários antes de aplicar essa migração a um banco com dados.

Os diretórios `infra/wiremock/mappings/`,
`infra/wiremock/__files/` e `docs/adr/` também estão preparados, sem conteúdo além
dos arquivos `.gitkeep`.

## Contratos de entrada e saída

`dto.request.CreateActivationRequest` é um record com os campos `externalId`,
`customerId` e `productCode`.

| Campo | Obrigatoriedade | Tamanho máximo | Formato |
| --- | --- | --- | --- |
| `externalId` | Texto não nulo, não vazio e não composto somente por espaços em branco. | 100 | Identificador textual, sem padrão adicional. |
| `customerId` | Texto não nulo, não vazio e não composto somente por espaços em branco. | 100 | Identificador textual, sem padrão adicional. |
| `productCode` | Texto não nulo, não vazio e não composto somente por espaços em branco. | 100 | Identificador textual, sem padrão adicional. |

O teto de 100 é uma decisão técnica baseada nas colunas `VARCHAR(100)` existentes,
não uma exigência de negócio ou do provedor. Não há tamanho mínimo adicional:
um único caractere que não seja espaço em branco é suficiente. Não se exige UUID,
CPF/CNPJ, conteúdo apenas numérico, prefixo ou consulta a um catálogo de produtos.
Letras acentuadas, pontuação e espaços junto de texto são aceitos.

`@NotBlankText` usa `String.isBlank()`, assim como o construtor da entidade:
rejeita texto formado somente por caracteres reconhecidos por
`Character.isWhitespace`, incluindo tabulação, quebra de linha, U+2003 e U+3000.
A constraint própria evita a diferença do `@NotBlank` no Hibernate Validator 8,
que usa `String.trim()` e aceita esses dois espaços Unicode. Ela não normaliza
o valor nem impõe um formato aos identificadores.

`@Size(max = 100)` e a entidade medem o tamanho com `String.length()`, em unidades
UTF-16, incluindo os espaços. Por exemplo, `"a"` repetido 100 vezes é válido;
101 vezes é inválido. Um emoji fora do plano básico Unicode, como `🚀`, ocupa
duas unidades desse limite. O PostgreSQL conta caracteres Unicode em `VARCHAR`,
portanto a validação Java pode ser mais restritiva para esses caracteres.

Os identificadores são preservados sem trim, conversão entre maiúsculas e
minúsculas ou outra normalização. Por exemplo, `" EXT-12345 "` conserva os
espaços e eles contam para o limite. Os valores `"EXT-12345"` e `"ext-12345"`
também são preservados como recebidos. A unicidade de `externalId` é uma regra
separada, garantida pela constraint do banco; a validação do DTO não consulta
duplicidades, existência de cliente ou disponibilidade do produto.

```json
{
  "externalId": "EXT-12345",
  "customerId": "CUST-10001",
  "productCode": "INTERNET-500"
}
```

`dto.response.ActivationRequestResponse` é um record com `id` (`UUID`),
`externalId`, `customerId`, `productCode`, `status` (`ActivationStatus`),
`createdAt` e `updatedAt` (`Instant`). O JSON usa UUID textual, os quatro nomes
do enum como status e datas UTC em ISO-8601. Não contém proprietário,
versão, contadores ou outros metadados internos.

Os DTOs definem os contratos; os endpoints ainda estão pendentes. A validação
das entradas deverá ser acionada com `@Valid` no parâmetro `@RequestBody` dos
controllers quando forem implementados. As anotações não validam automaticamente
uma chamada direta ao construtor do record.

## Perfis e variáveis de ambiente

Nenhum perfil é ativado automaticamente. A seleção é feita por
`SPRING_PROFILES_ACTIVE` ou pelos pontos de entrada dos testes.

| Perfil | Configuração |
| --- | --- |
| `local` | PostgreSQL em `localhost:5432/ativaflow`, usuário `ativaflow` e provedor em `http://localhost:8081` por padrão. Requer `DB_PASSWORD`. |
| `test` | PostgreSQL e credenciais fornecidos pelo Testcontainers via `@ServiceConnection`. Requer Docker disponível. |
| `prod` | Requer `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` e `ACTIVATION_PROVIDER_BASE_URL`, sem valores padrão. DevTools desabilitado. |

| Variável | Valor padrão ou finalidade |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | Perfil a ativar; `.env.example` define `local`. |
| `SERVER_PORT` | `8080`. |
| `DB_URL` | URL JDBC do PostgreSQL; padrão no perfil `local`: `jdbc:postgresql://localhost:5432/ativaflow`. |
| `DB_USERNAME` | Usuário do banco; padrão no perfil `local`: `ativaflow`. |
| `DB_PASSWORD` | Senha do banco, sem valor padrão. |
| `DB_CONNECTION_TIMEOUT_MS` | `30000`, espera por uma conexão do pool HikariCP em milissegundos. |
| `ACTIVATION_PROVIDER_BASE_URL` | URL base do provedor; padrão no perfil `local`: `http://localhost:8081`. |
| `ACTIVATION_PROVIDER_CONNECT_TIMEOUT` | `2s`, timeout de conexão HTTP. |
| `ACTIVATION_PROVIDER_READ_TIMEOUT` | `5s`, timeout de leitura HTTP. |
| `ACTIVATION_PROVIDER_API_KEY` | Vazio por padrão; propriedade reservada para a integração com o provedor. |
| `SHUTDOWN_TIMEOUT` | `30s`, limite por fase do desligamento gracioso. |

Os timeouts HTTP e de desligamento aceitam unidades como `500ms`, `2s` e `1m`.
O timeout do HikariCP é informado como inteiro em milissegundos. A URL e a chave
do provedor estão declaradas em `app.activation-provider`; o cliente dessa
integração ainda não está implementado.

## Execução local

Requisitos: JDK 21 e uma instância PostgreSQL acessível. O Maven é executado pelo
Wrapper incluído no repositório.

Copie o modelo de ambiente:

```bash
cp .env.example .env
```

Preencha `DB_PASSWORD` e ajuste as demais variáveis conforme o ambiente. O Spring
Boot não carrega `.env` automaticamente. Em Bash, carregue as variáveis e inicie
a aplicação:

```bash
set -a
source .env
set +a
bash ./mvnw spring-boot:run
```

Os arquivos `.env` locais são ignorados pelo Git; `.env.example` é versionado.

## Documentação OpenAPI e Swagger UI

Com a aplicação em execução na porta padrão, acesse:

| Recurso | URL |
| --- | --- |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| OpenAPI YAML | http://localhost:8080/v3/api-docs.yaml |

A integração usa `springdoc-openapi-starter-webmvc-ui` 2.10.0, compatível com
Spring Boot 3.5.x. `OpenApiConfiguration` define título, descrição e versão
`v1` do contrato da API. Se `SERVER_PORT` for alterada, ajuste a porta nas URLs.

`SecurityConfiguration` permite acesso sem autenticação às rotas da documentação
e mantém o health check público e a exigência de autenticação nas demais rotas.
Os mecanismos padrão de login, HTTP Basic e proteção CSRF do Spring Security
continuam habilitados; a autenticação JWT ainda será implementada.
A documentação fica disponível em todos os perfis. Para desativá-la, configure
`SPRINGDOC_API_DOCS_ENABLED=false` e `SPRINGDOC_SWAGGER_UI_ENABLED=false`.

Os controllers REST serão documentados automaticamente quando implementados.
Como ainda não há endpoints de negócio, a interface inicialmente não lista
operações de ativação. As entidades JPA não são expostas pela documentação.

## Testes e integração contínua

Para executar o build e os testes:

```bash
bash ./mvnw -B --no-transfer-progress verify
```

O teste `AtivaflowApiApplicationTests` verifica a inicialização do contexto com o
perfil `test`, o acesso anônimo ao Swagger UI e seus recursos, os documentos
OpenAPI, os metadados da API e a preservação das regras de acesso às demais rotas
e ao health check. `TestcontainersConfiguration` disponibiliza PostgreSQL com a
imagem `postgres:17-alpine`, sem depender do banco ou das credenciais locais.
`RestClientWireMockTests` verifica uma chamada HTTP com `RestClient` e um
servidor WireMock iniciado pelo próprio teste.
`FlywayMigrationTests` verifica a aplicação e validação das migrações V1 e V2,
persistência dos campos públicos e internos, valores padrão, obrigatoriedade
de proprietário não vazio, rejeição de contadores negativos, unicidade global
de `external_id` entre consumidores e rejeição de status desconhecido no
PostgreSQL real.
`ActivationRequestTests` verifica criação, validações, encapsulamento e transições
de estado. `CreateActivationRequestTests` usa o validador Jakarta real para
verificar obrigatoriedade (inclusive espaços Unicode), limites de 99/100/101
unidades UTF-16 e preservação dos três identificadores na criação da entidade,
sem banco ou contexto Spring.
`ActivationDtosJsonTests` usa `@JsonTest` para verificar leitura/escrita do payload,
preservação dos identificadores e serialização exclusiva dos sete campos públicos,
incluindo os quatro status e datas UTC, sem depender de Docker.
`ActivationRequestPersistenceTests` verifica o mapeamento completo
contra as migrações no PostgreSQL, leitura dos quatro estados como texto,
auditoria com relógio controlado e sessão SQL fora de UTC, preservação da data
de criação e rejeição de gravação obsoleta entre dois contextos de persistência.

Também é possível executar a aplicação com o PostgreSQL do Testcontainers e o
perfil `test` por meio de `TestAtivaflowApiApplication`:

```bash
bash ./mvnw spring-boot:test-run
```

O workflow `.github/workflows/ci.yaml` executa `verify` em Ubuntu com Java 21
Temurin e cache Maven, em pushes e pull requests destinados às branches `main`
e `develop`.
