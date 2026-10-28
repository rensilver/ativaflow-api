# AtivaFlow API

Projeto Java 21 com Spring Boot 3.5.16, organizado em camadas para uma API REST.
A base atual reúne dependências, configurações por ambiente, estrutura de pacotes
e testes de inicialização e comunicação HTTP. Os pacotes de aplicação possuem
suas responsabilidades descritas em `package-info.java`; endpoints e regras de
negócio ainda não estão implementados.

## Tecnologias e dependências

As dependências e os plugins de build estão definidos no `pom.xml`:

| Área | Componentes |
| --- | --- |
| API HTTP | Spring Web e Bean Validation |
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
sem trigger no banco. O controle de transições de status também caberá ao domínio.

A migração `V2__add_activation_request_owner_and_processing_metadata.sql`
adiciona os campos internos abaixo, preservando a V1 e o contrato público:

| Coluna interna | Tipo e finalidade |
| --- | --- |
| `consumer_id` | `VARCHAR(100)`, obrigatório, sem padrão; rejeita texto vazio ou composto apenas por espaços em branco. Identifica o consumidor proprietário da solicitação. |
| `version` | `BIGINT`, obrigatório, padrão `0`, não negativo; reservado para controle de concorrência otimista com `@Version`. |
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
aceito do payload. Os DTOs públicos deverão aceitar apenas `externalId`,
`customerId` e `productCode` na criação e expor apenas `id`, `externalId`,
`customerId`, `productCode`, `status`, `createdAt` e `updatedAt` nas respostas.
Nenhuma das oito colunas internas deverá ser exposta na API.

Esta entrega implementa somente o schema, seus testes e esta documentação.
Autenticação, entidades/DTOs, controle otimista, worker, retries, lease e
reconciliação ainda estão pendentes. Os campos opcionais começam nulos; a
sinalização de reconciliação permitirá manter um resultado ambíguo em
`PROCESSING`, sem adicionar estados públicos. O limite de tentativas será
aplicado pelo processamento; a constraint do banco impede somente contagens
negativas. A unicidade de `external_id` continua global, inclusive entre
consumidores. Índices para consultas/claim serão definidos quando essas
operações forem implementadas.

A V2 pressupõe a tabela `activation_requests` vazia, conforme o cenário atual
de desenvolvimento. Não há proprietário padrão nem backfill inferido de
`customer_id` ou `external_id`. Se a V1 tiver registros, a adição de
`consumer_id NOT NULL` falhará: será necessário planejar a atribuição dos
proprietários antes de aplicar essa migração a um banco com dados.

Os diretórios `infra/wiremock/mappings/`,
`infra/wiremock/__files/` e `docs/adr/` também estão preparados, sem conteúdo além
dos arquivos `.gitkeep`.

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

## Testes e integração contínua

Para executar o build e os testes:

```bash
bash ./mvnw -B --no-transfer-progress verify
```

O teste `AtivaflowApiApplicationTests` verifica a inicialização do contexto com o
perfil `test`. `TestcontainersConfiguration` disponibiliza PostgreSQL com a
imagem `postgres:17-alpine`, sem depender do banco ou das credenciais locais.
`RestClientWireMockTests` verifica uma chamada HTTP com `RestClient` e um
servidor WireMock iniciado pelo próprio teste.
`FlywayMigrationTests` verifica a aplicação e validação das migrações V1 e V2,
persistência dos campos públicos e internos, valores padrão, obrigatoriedade
de proprietário não vazio, rejeição de contadores negativos, unicidade global
de `external_id` entre consumidores e rejeição de status desconhecido no
PostgreSQL real.

Também é possível executar a aplicação com o PostgreSQL do Testcontainers e o
perfil `test` por meio de `TestAtivaflowApiApplication`:

```bash
bash ./mvnw spring-boot:test-run
```

O workflow `.github/workflows/ci.yaml` executa `verify` em Ubuntu com Java 21
Temurin e cache Maven, em pushes e pull requests destinados às branches `main`
e `develop`.
