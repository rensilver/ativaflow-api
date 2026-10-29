# Configuração do Resource Server JWT

A aplicação usa Spring Security OAuth 2.0 Resource Server com JWT e
`SessionCreationPolicy.STATELESS`. Cada chamada protegida precisa de um Access
Token no header `Authorization: Bearer`. O consumidor obtém seu token por Client
Credentials em um emissor externo; credenciais de clientes ficam com os próprios
consumidores. Não há Authorization Server, login, refresh ou logout na API.

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

Escopos e conversão de identidade para `client_id` continuam fora desta etapa.

Sem issuer, JWKS ou chave pública que permita configurar um decoder, a aplicação
falha na inicialização. Não há fallback que libere acesso sem validação.

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

## Verificação da segurança

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

Para executar somente os testes criados para o utilitário e a configuração de
fontes de chave desta tarefa:

```bash
bash ./mvnw -Dtest=LocalJwtMaterialUnitTests,LocalJwtKeySourceUnitTests test
```

Referências: [JWT Resource Server no Spring Security 6.5](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html)
e [propriedades do Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/appendix/application-properties/index.html).
