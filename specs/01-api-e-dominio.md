# SPEC-01 — API REST e modelo de domínio

**Prioridade:** P0 (contrato), P1 (paginação, respostas HTTP refinadas, encapsulamento e Java moderno).  
**Dependência:** [SPEC-00](00-visao-geral.md).

## 1. Contratos públicos

### `POST /api/v1/requests` — obrigatório

Payload conforme enunciado:

```json
{
  "externalId": "EXT-12345",
  "customerId": "CUST-10001",
  "productCode": "INTERNET-500"
}
```

Responder com **201 Created**, header `Location: /api/v1/requests/{id}` e JSON contendo **ao menos**:

```json
{
  "id": "0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90",
  "externalId": "EXT-12345",
  "customerId": "CUST-10001",
  "productCode": "INTERNET-500",
  "status": "PENDING",
  "createdAt": "2026-10-26T14:03:11Z",
  "updatedAt": "2026-10-26T14:03:11Z"
}
```

- Exemplo de UUID/data do enunciado: gerar valores reais; persistir `Instant` e expor UTC ISO-8601.
- Validar texto não nulo/não vazio/não composto somente por espaços. Decisão técnica adotada: `@NotBlankText`, com a semântica de `String.isBlank()` usada na entidade, e `@Size(max = 100)` nos três campos, contando unidades UTF-16, inclusive espaços. O teto acompanha as colunas `VARCHAR(100)` existentes; não representa exigência de negócio ou do provedor. Não exigir formato adicional nem normalizar os valores. Detalhes e exemplos no [README](../README.md#contratos-de-entrada-e-saída); não alterar o contrato exigindo campos extras.
- A criação não aguarda a resposta externa: persistir em `PENDING` e devolver a fotografia da criação. A ativação posterior é outra fase.

### `GET /api/v1/requests/{id}` — obrigatório

- Retornar 200 e representação atual da solicitação, inclusive status e timestamps.
- Retornar 404 com `ProblemDetail` se não existir **ou não pertencer ao consumidor** (não vazar existência de recurso alheio).

### `GET /api/v1/requests?status=&customerId=&page=&size=` — diferencial

- Paginação 0-based do Spring (`page=0`) e ordenação estável (ex.: `createdAt DESC, id DESC`). Limitar `size` para evitar abuso.
- Filtros opcionais `status`, `customerId`, sem acesso a registros de outro consumidor.
- Retornar formato paginado estável e documentado; evitar carregar tudo em memória.
- Índices definidos conforme filtros reais, como `(consumer_id, status, created_at)` e `(consumer_id, customer_id, created_at)` quando justificados por `EXPLAIN`/plano esperado. Não criar índices redundantes às cegas.

## 2. Modelo e transições

Estados permitidos **exatamente**: `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`.

```text
PENDING ──início autorizado──> PROCESSING ──sucesso confirmado──> COMPLETED
                                  └────falha definitiva───────> FAILED
```

- Espera entre tentativas conserva `PROCESSING`; **não criar quinto estado**.
- `COMPLETED` e `FAILED` são terminais. Rejeitar transições inválidas com exceção de domínio específica.
- Entidade JPA com construtor adequado a Hibernate, ausência de setters públicos para status e métodos explícitos como `startProcessing()`, `complete()`, `fail(reason)`.
- `@Version` para evitar lost updates; concorrência entre workers será reforçada na SPEC-02.
- Metadados internos para retries/lease/reconciliação podem ser adicionados ao banco **sem** modificar os quatro estados públicos.

## 3. Organização técnica

- Controllers pequenos, Service de aplicação para orquestrar casos de uso, domínio independente de HTTP, Repository JPA e Client HTTP isolado.
- DTOs de entrada/saída como **Java `record`** com Bean Validation na entrada.
- MapStruct para Entity → Response DTO e paginação, quando útil; preferir `@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)`. Proteger campos internos/sensíveis contra exposição.
- Criar entidade por factory/construtor de domínio; **não** deixar MapStruct atribuir status, identidade, proprietário ou campos de auditoria a partir de input do consumidor.
- Lombok apenas para construtores/logging ou getters seguros. Evitar `@Data`, `@EqualsAndHashCode` indiscriminados em entidades e `@SneakyThrows`.
- `sealed interface` e `switch` por pattern matching são um **diferencial do enunciado**, mas aplicar onde houver variações reais (resultados do provedor na SPEC-03), não para criar hierarquias artificiais no DTO/JPA.
- Flyway define DDL e índices, `spring.jpa.hibernate.ddl-auto=validate`, `open-in-view=false`; sem `create-drop` fora dos testes.
- Datas geradas no servidor e armazenadas em UTC; não confiar em timestamps fornecidos pelo cliente.

## 4. Tratamento e documentação

- Dados inválidos: 400; inexistente: 404; externalId em conflito: seguir SPEC-02.
- Respostas de erro RFC 9457 na SPEC-04.
- Explicar no README exemplos `curl`, validações adotadas, filtros, paginação, timestamps e estados.
- Documentar toda decisão não estipulada pelo HTML (ex.: limites máximos, formato da paginação).

## 5. Critérios de aceite

- [ ] `POST` valida campos, persiste uma linha e responde 201 + Location + payload completo.
- [ ] Estado registrado inicialmente é `PENDING`, com `createdAt`/`updatedAt` em UTC.
- [ ] `GET /{id}` recupera o estado atualizado e devolve 404 para inexistentes/inacessíveis.
- [ ] Listagem paginada suporta combinações de filtros, limites e isolamento entre consumidores.
- [ ] `PENDING → PROCESSING → COMPLETED|FAILED` é aplicado no domínio; transições inválidas falham.
- [ ] JPA com `@Version`, migrações Flyway e índices coerentes.
- [ ] Records, MapStruct e Lombok seletivo compilam corretamente no Java 21/Spring Boot 3.5.16.
- [ ] Testes de contrato HTTP e domínio passam com `./mvnw verify`.
