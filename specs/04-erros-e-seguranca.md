# SPEC-04 — Tratamento de erros e segurança

**Prioridade:** P0 (tratar erros e implementar **ou descrever** proteção), P1 (Problem Details, Resource Server, testes 401/403, BOLA, limites).  
**Dependências:** [SPEC-01](01-api-e-dominio.md), [SPEC-02](02-duplicidade-e-concorrencia.md).

## 1. Respostas de erro — obrigatório + diferencial

- Implementar `@RestControllerAdvice` com `ProblemDetail` (RFC 9457) e `Content-Type: application/problem+json` para exceções do MVC.
- Campos: `type`, `title`, `status`, `detail`, `instance`; propriedade de extensão `traceId` ou `correlationId` estável para buscar o mesmo evento no log; erros de validação incluem `errors: [{field, message}]`.
- `type` deve ser URI estável e documentada (sem domínio falso como se fosse site de produção); usar `about:blank` quando apropriado.
- Não expor stack traces, SQL, nomes de classes internas, tokens, credenciais nem detalhes do provedor.

| Erro                                                          | Status | Expectativa                                           |
| ------------------------------------------------------------- | ------:| ----------------------------------------------------- |
| Dados/JSON inválidos e Bean Validation                        | 400    | Mensagem segura por campo                             |
| Requisição sem token, JWT inválido ou expirado                | 401    | `WWW-Authenticate` preservado                         |
| Escopo insuficiente                                           | 403    | `ProblemDetail` e sem vazamento de recurso            |
| ID inexistente ou não autorizado a visualizar aquele registro | 404    | Sem revelação de existência alheia                    |
| `externalId` repetido com payload divergente                  | 409    | Explicação sem expor dados de outro consumidor        |
| Rate limit da própria API excedido                            | 429    | Mensagem e `Retry-After` apropriados                  |
| Falha inesperada                                              | 500    | Mensagem genérica + correlação, log interno detalhado |

Falhas do **provedor externo** são tratadas no fluxo assíncrono (SPEC-03), não repassadas cegamente como HTTP 500/503 da API de criação.

## 2. Spring Security / OAuth 2.0 / JWT

- Implementar Spring Security **OAuth 2.0 Resource Server**, sem Authorization Server no projeto.
- O cenário é sistema-para-sistema: documentar **Client Credentials**; consumidor obtém Access Token de um emissor externo. O serviço valida JWT de forma stateless.
- Verificações: assinatura via chave pública/JWKS, emissor `iss`, expiração `exp`, validade temporal (`nbf` quando presente), audience `aud` quando aplicável, finalidade de token (p.ex. `token_use=access` para Cognito) e identidade do cliente (`client_id` ou claim compatível com contrato do emissor).
- Configurar emissores/audience/chave por variáveis de ambiente ou profile. **Não** inserir chave privada, client secret de consumidor, JWT de produção ou credenciais no Git.
- No desenvolvimento local, oferecer roteiro seguro para gerar uma chave efêmera e token de demonstração **fora do repositório**, validado por uma chave pública configurável. Não exigir Cognito, Keycloak ou quarto container para subir o Compose.
- Para testes de regras de autorização, usar Spring Security Test e JWT simulado; para configuração de decoder, também testar token **realmente assinado**, inválido e expirado.

## 3. Escopos e proteção de dados

- Escopos da solução: `requests/read` (consultar) e `requests/write` (criar). Authorities do Spring: `SCOPE_requests/read` e `SCOPE_requests/write`.
- Proteger `POST /api/v1/requests` com escrita, `GET /api/v1/requests/{id}` e listagem com leitura.
- **BOLA:** o `consumerId`/owner é derivado da identidade confiável do JWT (por exemplo `client_id`); jamais obtê-lo do payload. `GET` e listagem devem aplicar filtro por owner **no banco** e retornar 404 para recursos de outro consumidor; não basta autorizar por escopo.
- `externalId` permanece único globalmente pelo enunciado. Ao haver colisão com outro consumidor, não revelar dados do registro; registrar e justificar a política de resposta segura.
- Usar injeção de dependência por construtor, `SecurityFilterChain` e method security (ex.: `@PreAuthorize`) conforme a arquitetura definida. Não implementar sessão ou login na API.

## 4. Segurança no filtro HTTP

- Respostas 401/403 nem sempre passam por `@RestControllerAdvice`: usar `AuthenticationEntryPoint` e `AccessDeniedHandler` para Problem Details, preservando semântica/cabeçalhos OAuth.
- Expor apenas health probes conforme política; proteger/limitar Actuator sensível e `/actuator/prometheus` (por rede ou credencial de monitoramento), não torná-los públicos indiscriminadamente.
- Limitar tamanho de body de forma efetiva no servidor/filtro (com 413 quando apropriado), sem consumir um payload gigante em memória para então rejeitá-lo.
- **Rate limiting — diferencial:** implementar limite simples e configurável por consumidor/IP conforme identidade disponível, com 429 + `Retry-After`. Preferir ferramenta leve ou filtro com concorrência correta; documentar que limitação **in-memory é por instância** e que cloud multi-instância exigiria gateway ou backend distribuído. Não usar Redis apenas para o take-home.
- CORS/CSRF: fundamentar configuração para API stateless usada por sistemas; não desativar indiscriminadamente segurança de endpoints sensíveis.
- Credenciais para o provedor, se requeridas: secret externo com política de acesso mínimo e rotação descrita no README; credenciais de consumidores ficam com os próprios clientes.

## 5. Testes e evidências

- 400 validação, 404, 409 e 500 retornam Problem Details sem stack trace.
- JWT ausente/expirado/assinatura inválida → 401; token válido sem escopo → 403; escopo correto → acesso permitido.
- Token de consumidor A não consulta nem lista solicitações de B (com mesmo escopo `read`); operação proibida não revela detalhes.
- 429 quando limite da API é atingido; validar cabeçalho `Retry-After` e ausência de interferência nos testes.
- Testar limites do body e token usado para fim errado (ID Token em lugar de Access Token) quando aplicável.
- `traceId` / correlação presente em erro e log correspondente.

## 6. Critérios de aceite

- [ ] Tratamento global uniforme RFC 9457 e mensagens seguras para os erros do enunciado.
- [ ] Resource Server valida JWT e escopos `read`/`write` sem Authorization Server próprio.
- [ ] 401 e 403 funcionam corretamente na cadeia Spring Security, com testes e cabeçalhos preservados.
- [ ] Consultas isoladas por consumidor; teste BOLA passa.
- [ ] Limites configuráveis de tamanho/rate são testados e documentados, inclusive limitações multi-instância.
- [ ] Local/CI não dependem de Cognito/AWS/credenciais reais; README demonstra uso seguro de token.