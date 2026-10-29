# Execução local com chave pública ou JWKS

Pré-requisitos: JDK 21, Bash, curl e PostgreSQL configurado conforme o README.
O modo JWKS também usa Python 3 para servir exclusivamente a chave pública em
loopback. Execute os comandos na raiz do projeto. O utilitário usa apenas o JDK,
sem bibliotecas adicionais, OpenSSL ou serviço real de identidade.

Este roteiro exercita o lado Resource Server com tokens assinados de verdade.
O utilitário não oferece token endpoint OAuth nem valida `client_id` ou
`client_secret`; portanto, não implementa o grant Client Credentials. O
[fluxo M2M e a gestão de segredos](resource-server.md) descrevem o contrato
esperado para um emissor externo, ainda não escolhido, sem exigir Cognito/AWS.

Para executar a API em container junto com PostgreSQL e WireMock, siga o
[roteiro completo do Compose](container-image.md#ambiente-completo-com-docker-compose).
Ele reutiliza este utilitário e os mesmos issuer/audience, monta somente a chave
pública e demonstra POST/GET autenticados, shutdown e retomada de pendências.
Nesse modo, não carregue o `.env` pelo shell: o Compose o lê automaticamente.

## Gerar material efêmero fora do Git

Depois de copiar `.env.example` para `.env` e preencher os dados de banco:

```bash
set -a
source .env
set +a
export JWT_DEMO_DIR="$(java scripts/LocalJwt.java create)"
source "$JWT_DEMO_DIR/env.sh"
```

O comando imprime somente o diretório criado em `java.io.tmpdir` (normalmente
`/tmp/ativaflow-jwt.*` em Linux). Se o diretório temporário estiver dentro de um
checkout Git, a geração falha; use `java -Djava.io.tmpdir=/tmp
scripts/LocalJwt.java create`. O roteiro suporta Linux/macOS com permissões POSIX.
Mantenha os arquivos gerados fora do repositório e das fixtures de testes. Para
o Compose, o roteiro permite copiar somente `public.pem` para o arquivo local
ignorado `.env.jwt-public.pem`, legível pelo usuário do container.

| Arquivo | Conteúdo |
| --- | --- |
| `private.pem` | Chave privada RSA 2048, usada somente pelo utilitário. |
| `public.pem` | Chave pública RSA PEM X.509, carregada pela API no modo PEM. |
| `jwks/jwks.json` | JWKS público com `kty`, `use`, `alg`, `kid`, `n` e `e`. |
| `authorization.header` | Header Bearer com token RS256 válido por cinco minutos. |
| `env.sh` | Variáveis para iniciar a API no modo PEM. |

Diretórios são criados com permissão `700`; arquivos, com `600`. Nenhum token ou
chave é escrito no console. O token tem `iss=https://ativaflow.local`,
`aud=ativaflow-api`, `sub=demo-consumer`, `token_use=access`,
`scope="requests/read requests/write"`, `iat`, `nbf`, `exp`
e header `kid`.
Esses valores são exclusivos da demonstração local.

## Modo 1: PEM sem servidor de chaves

O `env.sh` já ativa `local`, configura issuer/audience e aponta
`PUBLICKEYLOCATION` para `public.pem`. Também remove `JWKSETURI` do ambiente.
Inicie a API no mesmo terminal:

```bash
bash ./mvnw spring-boot:run
```

`LocalJwtDecoderConfiguration` valida assinatura RS256, issuer, `exp`
obrigatório, `nbf` quando presente, audience obrigatória e `token_use=access`.
A API lê somente a chave pública. O issuer local serve
como identificador e não precisa resolver na rede.

## Modo 2: JWKS em loopback

Como alternativa ao PEM, antes de iniciar a API:

```bash
unset SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_PUBLICKEYLOCATION
export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI=http://127.0.0.1:8082/jwks.json
python3 -m http.server 8082 --bind 127.0.0.1 \
  --directory "$JWT_DEMO_DIR/jwks" &
export JWT_JWKS_PID=$!
curl --fail http://127.0.0.1:8082/jwks.json
bash ./mvnw spring-boot:run
```

Mantenha issuer e audience definidos por `env.sh`. A configuração local usa a URL
explícita do JWKS sem discovery do issuer e aplica as mesmas regras do modo PEM,
incluindo `exp` obrigatório e `token_use=access`. A chave pública é buscada
ao validar tokens e fica em cache; mantenha o servidor disponível. A porta 8082
deve estar livre. O servidor HTTP expõe somente `jwks/`; a chave privada e o
header Bearer ficam no diretório pai. HTTP é usado apenas neste loopback local;
para um emissor externo, use o endpoint HTTPS do seu contrato.

Não configure `PUBLICKEYLOCATION` e `JWKSETURI` simultaneamente: o perfil local
rejeita essa combinação na inicialização. Issuer, audience e uma fonte de chave
são obrigatórios no perfil `local`. Reinicie a API ao trocar de modo ou
gerar um novo par de chaves.

## Verificar a API e renovar o token

Em outro terminal, defina `JWT_DEMO_DIR` com o caminho impresso na geração,
sem copiar chaves ou tokens. O arquivo de header evita passar o Bearer na linha
de comando do processo. Ajuste a porta caso tenha alterado `SERVER_PORT`:

```bash
curl -i --request POST http://localhost:8080/api/v1/requests \
  --header @"$JWT_DEMO_DIR/authorization.header" \
  --header 'Content-Type: application/json' \
  --data '{"externalId":"LOCAL-JWT-001","customerId":"CUST-001","productCode":"INTERNET-500"}'
```

Com banco e provedor preparados, a criação retorna `201`. Use outro `externalId`
para uma nova solicitação. Consulte o `Location` retornado pelo POST:

```bash
curl -i 'http://localhost:8080/api/v1/requests/UUID_RETORNADO_NO_POST' \
  --header @"$JWT_DEMO_DIR/authorization.header"
```

Use o UUID real; com o mesmo consumidor a consulta retorna `200`. Sem Bearer,
a mesma rota protegida retorna `401`. Um token adulterado ou expirado também
retorna `401`, com `WWW-Authenticate: Bearer` e `invalid_token`; a validação
tem a tolerância de relógio padrão do Spring Security. A autorização por
consumidor usa o `sub`, portanto um token de outro consumidor não dá acesso ao
pedido criado por `demo-consumer`.

Para renovar por mais cinco minutos com as mesmas chaves e o consumidor
`demo-consumer`:

```bash
java scripts/LocalJwt.java token "$JWT_DEMO_DIR"
```

Tokens emitidos antes da exigência de `token_use=access` também devem ser
renovados com esse comando, usando o utilitário atualizado.

Para emitir para outro consumidor, informe o terceiro argumento, por exemplo
`another-consumer`. São aceitos de 1 a 100 caracteres alfanuméricos, `.`, `_`,
`@` e `-`, começando por um alfanumérico. O comando substitui o header anterior;
não é necessário reiniciar a API ao renovar o token com as mesmas chaves.

## Renovação de token e rotação de chave

`java scripts/LocalJwt.java token "$JWT_DEMO_DIR"` emite outro token com o par de
chaves existente. Não rotaciona chave, não revoga o token anterior e não usa
refresh token. O header em disco é substituído, mas cópias anteriores do Bearer
continuam utilizáveis até a expiração, considerada a tolerância temporal.
Sem o terceiro argumento, o consumidor usado é `demo-consumer`; se tiver
escolhido outra identidade, repita-a ao renovar.

Para trocar a chave de demonstração, pare a API e o servidor JWKS, se utilizado,
e remova o material antigo seguindo a limpeza abaixo. Repita a geração com
`create`, carregue o novo `env.sh` e reinicie a API no modo escolhido. O novo
par tem outro `kid`; o decoder PEM só confia na nova chave após reiniciar.
No JWKS, esta demonstração também exige reinício para não depender do cache de
chaves antigas. Confirme o acesso com o novo Bearer. Esse procedimento local
tem interrupção e não implementa sobreposição de chaves; a estratégia externa
está em [rotação da chave de assinatura](resource-server.md#rotacionar-a-chave-de-assinatura-jwt).

## Encerrar e remover material

Pare a API com Ctrl+C. No terminal que iniciou o servidor JWKS, se utilizado:

```bash
kill "$JWT_JWKS_PID"
wait "$JWT_JWKS_PID" 2>/dev/null || true
unset JWT_JWKS_PID
```

Em seguida, remova os arquivos gerados e as variáveis nos terminais utilizados:

```bash
rm -- "$JWT_DEMO_DIR/private.pem" "$JWT_DEMO_DIR/public.pem" \
  "$JWT_DEMO_DIR/authorization.header" "$JWT_DEMO_DIR/env.sh" \
  "$JWT_DEMO_DIR/jwks/jwks.json"
rmdir -- "$JWT_DEMO_DIR/jwks" "$JWT_DEMO_DIR"
unset JWT_DEMO_DIR
unset SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_PUBLICKEYLOCATION
unset SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI
unset SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI
unset SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES
```

O material não é apagado automaticamente ao parar a API. Expiração do token e
remoção dos arquivos têm finalidades diferentes; sempre faça a limpeza.

## Testes unitários disponíveis

Contrato do Access Token, geração e renovação:

```bash
bash ./mvnw -Dtest=LocalAccessTokenValidationUnitTests,LocalAccessTokenMaterialUnitTests test
```

Testes anteriores dos artefatos e das fontes de chave:

```bash
bash ./mvnw -Dtest=LocalJwtMaterialUnitTests,LocalJwtKeySourceUnitTests test
```

Esses testes executam o utilitário e validam os artefatos reais, as permissões,
a recusa de geração dentro de checkout e a configuração ambígua. Não iniciam
a API, PostgreSQL, Docker ou servidores de rede.

Referência: [JWT Resource Server no Spring Security 6.5](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html).
