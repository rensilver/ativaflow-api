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
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES` | Audience(s) aceitas, separadas por vírgula. Quando configuradas, o token precisa conter ao menos uma delas em `aud`. |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_PUBLICKEYLOCATION` | Arquivo de chave pública PEM X.509 para desenvolvimento local, como `file:/tmp/ativaflow-jwt/public.pem`. |

Com um emissor externo, configure `ISSUERURI` e, conforme seu contrato,
`AUDIENCES`; informe `JWKSETURI` quando necessário. Deixe `PUBLICKEYLOCATION`
desconfigurada nesse modo. A autoconfiguração do Boot fornece o decoder e a
validação de assinatura, `iss`, `exp` e `nbf` quando presentes. O algoritmo padrão
é RS256. Emissor e audience devem vir do contrato real, sem valores de produção
fixados no código.

Apenas a configuração base de autenticação está implementada nesta etapa. O
principal usa `sub` como nome; os controllers existentes usam esse nome como
identidade do consumidor. Conversão para `client_id`, exigência de escopos e
validação de finalidade de token específica de um emissor serão tratadas conforme
o contrato adotado. O suporte padrão a JWT não distingue sozinho ID Token de
Access Token.

Sem issuer, JWKS ou chave pública que permita configurar um decoder, a aplicação
falha na inicialização. Não há fallback que libere acesso sem validação.

## Execução local com PEM ou JWKS

O [roteiro local](local-jwt.md) usa `scripts/LocalJwt.java` (Java 21) para
produzir chaves RSA 2048, JWKS público e tokens RS256 de cinco minutos em diretório
temporário fora do Git, com permissões restritas. Inclui os modos PEM e JWKS,
chamadas válidas de criação/consulta, renovação e limpeza do material.

Com o perfil `local` e `PUBLICKEYLOCATION` definida, `LocalJwtDecoderConfiguration`
carrega a chave pública RSA e valida assinatura RS256, issuer, timestamps e
as audiences configuradas. O issuer é obrigatório também nesse modo. Uma chave
ausente ou inválida impede a inicialização. Configurar PEM e JWKS simultaneamente
também impede a inicialização. A aplicação não gera chaves nem lê a chave privada.

No modo JWKS, deixe `PUBLICKEYLOCATION` desconfigurada e informe `JWKSETURI`,
issuer e audience. O decoder é fornecido pela autoconfiguração do Spring Boot.
O servidor local publica somente o subdiretório `jwks/`, sem expor a chave privada
ou o header Bearer.

## Verificação da segurança

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
