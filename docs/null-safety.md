# Análise de nulidade com Eclipse JDT

O projeto usa Spring Boot 3.5.16 e Spring Framework 6.2.19. Os contratos usam
`org.springframework.lang.NonNullApi`, `Nullable` e `Contract`, compatíveis com
essa linha do Spring. A dependência `jsr305:3.0.2` tem escopo `provided` para
disponibilizar as meta-anotações do Spring durante a compilação.

Referência: [Null-safety do Spring Framework 6.2](https://www.springframework.org/spring/reference/6.2/core/null-safety.html).

## Causas e correções locais

| Diagnóstico | Causa | Correção |
| --- | --- | --- |
| Conversão não verificada em `repository.save(entity)` | Spring Data exige uma entidade não nula; o retorno de `ActivationRequest.create()` não tinha contrato de nulidade. | `@NonNullApi` no pacote `model` declara o retorno não nulo da fábrica. Não há nova validação em tempo de execução. |
| Conversões nos `ReflectionTestUtils.setField()` do teste do mapper | A mesma fábrica sem contrato produzia uma referência cuja nulidade era desconhecida para o JDT. | O contrato da fábrica também corrige esses 11 avisos. |
| Conversão em `ResponseEntity.created(location)` sem `requireNonNull` | O JDT não conhece o contrato de retorno de `java.net.URI.create()`. | A URI passa a ser construída com `UriComponentsBuilder`, cujos retornos têm o contrato do Spring. O `Objects.requireNonNull()` redundante foi removido. |
| Possível desempacotamento de `Long` nulo no teste de criação | `JdbcTemplate.queryForObject()` declara retorno anulável; o helper retornava `long`. | O helper retorna `@Nullable Long`. As asserções numéricas verificam o resultado, incluindo uma eventual resposta nula, sem desempacotamento antecipado. |
| Conversões de `path` nos testes parametrizados de documentação e segurança | Os parâmetros não tinham contrato, apesar de `@ValueSource` fornecer apenas strings não nulas. | `@NonNull` nesses dois parâmetros. |

`@NonNullApi` foi aplicado aos pacotes `model` e `controller`, não globalmente.
Ele cobre parâmetros e retornos, não campos nem subpacotes. A entidade possui
estados legítimos com valores nulos: versão e datas antes da persistência, além
dos metadados opcionais de processamento. Esses campos usam `@Nullable`, que o
Lombok também propaga aos getters. As datas anuláveis estão declaradas no DTO.
Não foi aplicado `@NonNullFields` a uma entidade que é hidratada pela JPA.

Os parâmetros da fábrica, do construtor interno, de `fail()` e do validador de
texto usam `@Nullable` porque essa fronteira recebe os valores para validá-los.
Isso não torna nulo um valor válido de domínio: a validação existente continua
rejeitando nulos com `IllegalArgumentException`, sem alterar a ordem de validação
ou as regras de transição. `requireText()` sempre retorna texto não nulo quando
não lança uma exceção.

O mapper mantém o comportamento `null -> null`, agora explícito com `@Nullable`
na entrada e no retorno e `@Contract("null -> null; !null -> !null")`. O JDT não
interpreta esse contrato condicional. Por isso, não foi aplicado `@NonNullApi`
indiscriminadamente aos pacotes `mapper` e `service`, nem acrescentado um cast
ou uma validação artificial no serviço para afirmar que o mapper nunca retorna
nulo. O teste que acessa os campos da resposta verifica antes que ela foi criada.

## Avisos remanescentes em dependências dos testes

A compilação JDT também encontra 19 conversões não verificadas nos testes.
Elas representam contratos desconhecidos nas APIs abaixo; não são evidência de
que os métodos efetivamente retornam nulo nesses cenários.

| Arquivo | Ocorrências | Origem |
| --- | ---: | --- |
| `CreateActivationRequestEndpointTests` | 8 | Retornos de `SecurityMockMvcRequestPostProcessors.user()` e `csrf()` consumidos por `with()`. |
| `CreateActivationRequestEndpointTests` | 6 | Campo `MediaType.APPLICATION_JSON` consumido por APIs com parâmetros não nulos. |
| `CreateActivationRequestEndpointTests` | 2 | Retorno de Jackson `ObjectMapper.writeValueAsString()` consumido por `content()`. |
| `FlywayMigrationTests` | 1 | Retorno do JDK `String.formatted()` consumido por `JdbcTemplate.update()`. |
| `RestClientWireMockTests` | 1 | Retorno de WireMock `getHttpBaseUrl()` consumido por `RestClient.create()`. |
| `AtivaflowApiApplicationTests` | 1 | Retorno de Hamcrest `Matchers.startsWith()` consumido por `JsonPathResultMatchers.value()`. |

Não foram criados wrappers com contratos presumidos, inseridos `requireNonNull()`
nessas chamadas ou alteradas configurações para esconder esses diagnósticos.
Para eliminar tais lacunas, o caminho é revisar os contratos nas dependências
ou fornecer anotações externas do Eclipse para as assinaturas exatas das versões
usadas. Uma atualização de bibliotecas exige verificar novamente esses contratos.

## Verificação

A análise foi reproduzida com o compilador batch do JDT instalado junto à extensão
Java do VS Code, Java 21 e o classpath Maven do projeto. O Lombok foi expandido
em diretório temporário e o código gerado pelo MapStruct também foi compilado.
Foram habilitados análise de nulidade por anotações, violações de contrato,
desreferenciamento potencial e conversões não verificadas, com as anotações do
Spring e seus equivalentes presentes nas dependências.

Resultado: nenhum diagnóstico de nulidade no código principal ou nos testes do
mapper; os 19 avisos externos acima permanecem visíveis nos demais testes. O JDT
também aponta um aviso preexistente de `serialVersionUID` na exceção de domínio,
que não está relacionado à nulidade.

A compilação Maven, a geração MapStruct, os testes com PostgreSQL/Testcontainers,
as verificações HTTP e o empacotamento são validados com `./mvnw clean verify`.
A suíte contém 139 testes. Nenhum teste é ignorado para contornar restrições do
ambiente; a execução requer acesso ao Docker local e aos sockets usados nos testes.
