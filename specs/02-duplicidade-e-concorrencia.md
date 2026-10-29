# SPEC-02 — Duplicidade, concorrência e consistência

**Prioridade:** P0 (não duplicar `externalId`), P1 (semântica de repetição, teste concorrente, lock/versionamento).  
**Dependências:** [SPEC-01](01-api-e-dominio.md) e requisitos da [SPEC-00](00-visao-geral.md).

## 1. Invariantes

- Nunca persistir duas solicitações válidas com o mesmo **`externalId` global**, conforme enunciado, nem sob race condition.
- Não confundir **idempotência da API** (criação sem duplicar registros) com **idempotência do provedor** (não ativar duas vezes): são garantias distintas.
- Validar unicidade no **PostgreSQL**, não só com `existsByExternalId` seguido de `save`.
- Evitar que duas instâncias assumam simultaneamente a mesma ativação.
- Escopos e identidade do consumidor (SPEC-04) restringem acesso aos recursos, mesmo quando o `externalId` for conhecido.

## 2. Contrato para repetições — diferencial

| Cenário                                                                        | Comportamento proposto                                                                                                          |
| ------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------- |
| Novo `externalId`                                                              | `201 Created`, `Location`, dados novos                                                                                          |
| Mesmo `externalId` e conteúdo igual (`customerId`, `productCode` e mesmo dono) | `200 OK` + representação existente, sem criar nova linha nem reiniciar a ativação                                               |
| Mesmo `externalId`, conteúdo diferente                                         | `409 Conflict` com `ProblemDetail`, sem alteração do registro original                                                          |
| Duas criações idênticas paralelas                                              | Exatamente uma inserção; respostas coerentes com criação vs repetição (tipicamente 201 e 200)                                   |
| `externalId` pertencente a outro consumidor                                    | Não expor dados do recurso; resposta genérica de conflito/negação conforme política documentada; nunca retornar o objeto alheio |

A semântica 200/409 é uma **decisão do projeto**, não uma imposição do enunciado; justificar no README. Normalizar input apenas se a regra estiver documentada; não ignorar alterações materiais do payload.

## 3. Estratégia no banco

- Flyway adiciona `UNIQUE(external_id)` (índice/constraint com nome explícito). Não depender de lock pessimista global.
- Consulta prévia é **otimização**, não garantia; capturar violação de unicidade no limite transacional e consultar registro existente em **nova transação**, se necessário, pois a transação anterior pode ficar invalidada após exceção SQL.
- Converter a exceção de corrida no mesmo contrato HTTP da repetição; não retornar 500 por disputa normal.
- Utilizar `@Version` para mudanças concorrentes de entidade; em fluxos de workers, usar claim **atômico**, com predicado de estado/lease, e validar o número de linhas atualizadas.
- Evitar `synchronized` como mecanismo de unicidade: ele não protege múltiplas instâncias e não substitui constraint SQL.

## 4. Worker e recuperação após falha

- Representar trabalho pendente no banco, usando metadados internos necessários: `attempt_count`, `next_attempt_at`, `lease_until`, `claimed_by`, `last_error_code`, `reconciliation_required`, quando justificáveis.
- Claim em transação **curta**, com seleção/atualização condicional e bloqueio de linhas adequado ao PostgreSQL (por exemplo, `FOR UPDATE SKIP LOCKED` em um desenho consistente). Não manter lock ou transação aberta enquanto executa HTTP.
- Recuperar `PENDING` após reinício. Para `PROCESSING` com lease vencido, distinguir **tarefa abandonada** de **resultado externo desconhecido**; somente reenviar quando for comprovadamente seguro.
- Registrar tentativas/horários de forma atômica, limitando disputa entre workers e evitando hot loops.
- Em caso de timeout ambíguo: manter `PROCESSING` com sinalização de reconciliação, sem repetir ativação automaticamente até garantia do provedor. Se não existir operação de consulta externa, documentar claramente a necessidade de intervenção/reconciliação futura.
- Encerramento gracioso deve liberar/expirar leases com segurança; recuperação não implica afirmar “exatamente uma chamada HTTP” em falhas de rede.

## 5. Testes que comprovam o diferencial

- Teste de integração contra **PostgreSQL real com Testcontainers**, usando barreira de sincronização para disparar múltiplas threads ao mesmo tempo, cada uma executando a criação em **sua transação**.
- Verificar que há **exatamente uma linha** com aquele `externalId`; respostas compatíveis com a tabela da seção 2; nada de erro 500 por corrida.
- Repetição idêntica sequencial não dispara processamento duplicado.
- Mesmo `externalId` e `productCode` divergente gera 409.
- Concorrência no claim: no máximo um worker assume a mesma linha por lease, inclusive simulando múltiplos workers.
- Verificar colisão de `@Version` ou compare-and-set e proteção de estados terminais.

## 6. Critérios de aceite

- [ ] Constraint única criada por Flyway e verificada em PostgreSQL.
- [ ] Mesmo payload + mesmo consumidor retorna registro existente; conteúdo divergente gera conflito documentado.
- [ ] Teste paralelo com Testcontainers valida exatamente uma inserção, sem H2 ou Repository mockado.
- [ ] Operações de claim e atualização de estados impedem dois workers simultâneos.
- [ ] Reinício e lease vencido não causam reenvio inseguro em situação ambígua.
- [ ] README explica race condition, limite da verificação prévia, diferença entre as duas idempotências e comportamento por cenário.
