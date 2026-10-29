# Imagem Docker Java 21

Na raiz do repositório, com Docker Engine, Buildx e BuildKit disponíveis:

```bash
./mvnw -B --no-transfer-progress clean verify
docker build --check .
docker build --progress=plain -t ativaflow-api:local .
```

Confira `docker buildx version` se o cliente não reconhecer `--check` ou
`--progress`. Em instalações via Snap, o cliente precisa localizar também os
plugins do Snap; executar apenas o binário interno pode acionar o builder legado.

O primeiro comando requer JDK 21 e Docker para os testes com Testcontainers.
O build da imagem requer apenas Docker: compila os fontes com o Maven Wrapper
do projeto e não utiliza o `target/` da máquina. Os testes ficam fora desse
build, pois a suíte de integração utiliza Docker e deve passar no `verify`
anterior; nenhum socket Docker é montado no estágio de compilação.

O Dockerfile usa as imagens oficiais [Eclipse Temurin](https://hub.docker.com/_/eclipse-temurin):
JDK 21 no build e JRE 21 no runtime, ambos sobre Ubuntu Jammy e fixados por digest.
Ao atualizar as bases, atualize os dois digests e repita as validações; `--pull`
sozinho não atualiza uma base fixada por digest. A resolução de dependências
precede a cópia dos fontes, e o cache BuildKit preserva o repositório Maven
e a distribuição do Wrapper entre builds.

O contexto permite somente `pom.xml`, `mvnw`, as propriedades do Wrapper e
`src/main/`. `.git`, `target`, testes, documentação, `.env`, configurações da IDE
e credenciais locais ficam fora. Arquivos de chaves também são excluídos de
`src/main/`; material de autenticação deve ser fornecido em runtime.

O runtime contém somente o JRE e o JAR da aplicação, sem Maven ou fontes. Executa
com UID/GID `10001:10001`, e o JAR pertence a root com permissão `0444`. A JVM
recebe SIGTERM diretamente pelo entrypoint em formato exec. `JAVA_TOOL_OPTIONS`
limita o heap a 75% da memória disponível ao container e encerra a JVM em caso
de `OutOfMemoryError`; o limite total de memória é definido no `docker run` ou
no orquestrador. O restante da memória atende metaspace, threads e buffers.

## Conferência do runtime

```bash
docker image inspect ativaflow-api:local \
  --format 'User={{.Config.User}} Entrypoint={{json .Config.Entrypoint}}'
docker run --rm --entrypoint java ativaflow-api:local -version
docker run --rm --entrypoint id ativaflow-api:local
```

## Execução com serviços acessíveis pelo container

Prepare `.env` conforme o README, com `DB_URL` e
`ACTIVATION_PROVIDER_BASE_URL` acessíveis a partir da rede do container.
`localhost` dentro do container se refere ao próprio container. Para serviços
na máquina Linux, pode-se usar `host.docker.internal` com o mapeamento abaixo,
desde que os serviços aceitem conexões pela interface correspondente.

Para uma chave local, reutilize o [utilitário JWT existente](local-jwt.md):

```bash
export JWT_DEMO_DIR="$(java scripts/LocalJwt.java create)"
source "$JWT_DEMO_DIR/env.sh"
# Somente a chave pública é enviada a um volume temporário do Docker.
JWT_PUBLIC_VOLUME="ativaflow-public-${JWT_DEMO_DIR##*/}"
docker volume create "$JWT_PUBLIC_VOLUME"
docker run --rm -i --user 0:0 --network none --cap-drop ALL \
  --mount "type=volume,src=$JWT_PUBLIC_VOLUME,dst=/keys" \
  --entrypoint sh ativaflow-api:local \
  -ec 'cat > /keys/jwt-public.pem; chmod 0444 /keys/jwt-public.pem' \
  < "$JWT_DEMO_DIR/public.pem"

docker run -d --name ativaflow-api \
  --add-host host.docker.internal:host-gateway \
  --env-file .env \
  --env SPRING_PROFILES_ACTIVE=local \
  --env SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI \
  --env SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES \
  --env SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_PUBLICKEYLOCATION=file:/run/secrets/jwt-public.pem \
  --mount "type=volume,src=$JWT_PUBLIC_VOLUME,dst=/run/secrets,readonly" \
  --publish 127.0.0.1:8080:8080 \
  --memory 512m --cpus 1 \
  --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --cap-drop ALL \
  ativaflow-api:local

docker logs --tail=100 ativaflow-api
curl --fail http://localhost:8080/actuator/health
curl --fail --header "@$JWT_DEMO_DIR/authorization.header" \
  http://localhost:8080/api/v1/requests
```

O volume evita depender do acesso do daemon ao `/tmp` do host, que pode estar
isolado em instalações via Snap ou em um Docker remoto. O container auxiliar
grava somente a chave pública; a API monta o volume como somente leitura e
executa com UID `10001`. Chave privada e token permanecem no diretório local.

O health deve retornar `{"status":"UP"}` após as migrações Flyway e a conexão
com o banco. A consulta autenticada deve retornar HTTP 200; sem Bearer, HTTP 401.
O health é consultado externamente, sem instalar ferramentas adicionais na imagem.
Se alterar `SERVER_PORT`, ajuste também o mapeamento de portas; `EXPOSE 8080`
documenta a porta padrão.

Para exercitar criação e processamento usando o mock, siga os comandos de POST
e consulta do [roteiro JWT](local-jwt.md#verificar-a-api-e-renovar-o-token),
mantendo o mesmo `JWT_DEMO_DIR`. A resposta de criação é HTTP 201; consulte o
`Location` até o estado `COMPLETED` no cenário de sucesso do mock.

Pare com um prazo superior aos 30 segundos configurados para o shutdown gracioso:

```bash
docker stop --time 40 ativaflow-api
docker logs --tail=30 ativaflow-api
docker inspect ativaflow-api --format 'ExitCode={{.State.ExitCode}} OOMKilled={{.State.OOMKilled}}'
docker rm ativaflow-api
docker volume rm "$JWT_PUBLIC_VOLUME"
rm -r -- "$JWT_DEMO_DIR"
unset JWT_DEMO_DIR JWT_PUBLIC_VOLUME
```

Os logs devem registrar a conclusão do shutdown gracioso, sem `OOMKilled` ou
SIGKILL (código 137). O Compose atual fornece somente o mock; a inclusão da API e
do PostgreSQL no Compose permanece uma tarefa separada de A01.

## Evidência de validação — 29/10/2026

Validação local em Linux/amd64 da imagem `ativaflow-api:local`:

| Verificação | Resultado |
| --- | --- |
| `./mvnw -B --no-transfer-progress clean verify` | 949 testes em 58 relatórios; 0 falhas, erros ou ignorados, incluindo PostgreSQL via Testcontainers. |
| `docker build --check .` | Sem avisos. |
| `docker build --progress=plain -t ativaflow-api:local .` | Sucesso; fontes compilados dentro do estágio de build. |
| Runtime | Temurin 21.0.13; UID/GID `10001:10001`; JAR somente leitura; sem `javac`, Maven ou `/workspace`. |
| Inicialização | Flyway/PostgreSQL e health HTTP 200 `UP`, com filesystem somente leitura, `/tmp` em tmpfs, 512 MiB, 1 CPU e `cap-drop ALL`. |
| Autenticação | GET sem Bearer: 401; GET com JWT RS256 local: 200. Somente a chave pública foi fornecida à API por volume somente leitura. |
| Processamento | POST autenticado: 201; consulta posterior: `COMPLETED`, após processamento pelo worker e HTTP real no WireMock. |
| SIGTERM | Log `Graceful shutdown complete`, exit 143, `OOMKilled=false`. |
| Limpeza | Containers, rede, volumes de teste e material JWT temporário removidos; imagem local preservada. |

Neste ambiente, o Docker via Snap recusou a execução tanto de `java` quanto de
`sh` com `--security-opt no-new-privileges=true` (`operation not permitted`).
O roteiro e a validação acima usam as demais restrições descritas na tabela.
A validação de restart e preservação dos dados pertence ao roteiro completo
com Compose, ainda pendente em A01.
