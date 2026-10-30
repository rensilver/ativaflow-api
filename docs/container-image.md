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
SIGKILL (código 137).

## Ambiente completo com Docker Compose

O Compose usa o mesmo Dockerfile e inicia `app`, `postgres` e
`activation-provider-mock`. Na raiz do projeto, copie `.env.example` para `.env`
se ainda não existir e preencha `DB_PASSWORD` com uma senha não vazia.
O Compose lê `.env` automaticamente; não é necessário carregá-lo no shell.
Gere o material JWT com JDK 21 e prepare somente a chave pública para a API:

```bash
export JWT_DEMO_DIR="$(java scripts/LocalJwt.java create)"
source "$JWT_DEMO_DIR/env.sh"
export JWT_PUBLIC_KEY_FILE="$PWD/.env.jwt-public.pem"
cp -- "$JWT_DEMO_DIR/public.pem" "$JWT_PUBLIC_KEY_FILE"
chmod 0444 "$JWT_PUBLIC_KEY_FILE"
docker compose config --quiet
docker compose up --build -d --wait
docker compose ps
curl --fail http://localhost:8080/actuator/health
```

O nome `.env.jwt-public.pem` já é ignorado pelo Git e pelo contexto de build.
Essa cópia contém apenas a chave pública, legível pelo UID `10001`; chave privada
e token permanecem no diretório temporário privado. O Compose monta somente esse
arquivo em `/run/secrets/jwt-public.pem`, como somente leitura. Não é necessário
dar acesso ao diretório privado gerado pelo utilitário. O arquivo precisa estar
em um caminho acessível ao daemon Docker; o caminho no projeto também evita o
isolamento de `/tmp` de instalações via Snap. Docker remoto requer disponibilizar
o arquivo no host do daemon.

Este Compose é para demonstração local com PEM: fixa `SPRING_PROFILES_ACTIVE=local`,
issuer padrão `https://ativaflow.local` e audience `ativaflow-api`, compatíveis com
o utilitário. Issuer e audience podem ser ajustados pelas respectivas variáveis.
O caminho PEM no host vem de `JWT_PUBLIC_KEY_FILE` (padrão `.env.jwt-public.pem`);
o `PUBLICKEYLOCATION` do roteiro para execução na máquina não é usado no container.
As variáveis do Compose são as referenciadas no YAML; ele não encaminha todo o
`.env` para a API.

| Serviço | Endereço na rede Compose | Publicação no host |
| --- | --- | --- |
| API | `app:8080` | `127.0.0.1:${SERVER_PORT:-8080}` |
| PostgreSQL | `postgres:5432`, banco `ativaflow` | Nenhuma |
| WireMock | `activation-provider-mock:8080` | `127.0.0.1:8081` |

`DB_URL` e `ACTIVATION_PROVIDER_BASE_URL` da execução na máquina são substituídas
pelas URLs internas no Compose. `DB_USERNAME` (padrão `ativaflow`) e `DB_PASSWORD`
são compartilhados pelo banco e pela API. `SERVER_PORT` altera apenas a porta
publicada; a API continua escutando em `8080` dentro do container. Ajuste a URL do
curl se mudar essa variável. A API tem limite de memória de 512 MiB.

A API aguarda os healthchecks do PostgreSQL (`pg_isready` via TCP) e do WireMock
(`/__admin/health`). Seu próprio check consulta `/actuator/health/readiness`, que
inclui o estado de aceitação de tráfego e a conexão com o banco, usando o curl já
presente na imagem. A [liveness](../README.md#probes-de-saúde) verifica somente o
estado interno da aplicação. Nenhuma das duas probes chama o provedor. A espera
de inicialização usa `depends_on: condition: service_healthy`,
conforme a [referência do Compose](https://docs.docker.com/reference/compose-file/services/).

O volume nomeado `postgres-data` preserva os dados entre recriações. Para parar
os serviços mantendo os dados, execute `docker compose down`, sem `--volumes`.
A API recebe até 40 segundos para encerrar, acima do timeout padrão de 30 segundos
da aplicação. Para subir novamente, mantenha `.env` e a chave pública disponíveis
e execute `docker compose up -d --wait`. Alterar a senha no `.env` não altera a
senha de um banco já inicializado no volume; mantenha as credenciais coerentes.

Continue no mesmo terminal para demonstrar o fluxo abaixo. Mantenha o mesmo
projeto Compose, senha e par de chaves durante as recriações.

### Demonstração autenticada e retomada de pendências

Além dos pré-requisitos anteriores, estes comandos usam `jq`. Execute-os em Bash,
na raiz do projeto, depois da subida dos três serviços. Defina as URLs de acordo
com as portas publicadas (o mock usa 8081). Se `SERVER_PORT` estiver somente no
`.env`, ajuste `API_URL` explicitamente: o shell não carrega esse arquivo.

```bash
set -euo pipefail
export API_URL="http://127.0.0.1:${SERVER_PORT:-8080}"
export MOCK_URL=http://127.0.0.1:8081
export DEMO_RUN="$(date -u +%Y%m%dT%H%M%SZ)-$RANDOM"
java scripts/LocalJwt.java token "$JWT_DEMO_DIR"

# Health público: 200; recurso protegido sem Bearer: 401.
test "$(curl -sS -o /dev/null -w '%{http_code}' "$API_URL/actuator/health")" = 200
test "$(curl -sS -o /dev/null -w '%{http_code}' "$API_URL/api/v1/requests")" = 401

create_request() {
  local external_id="$1" destination="$2" code
  code=$(curl --silent --show-error --output "$destination" --write-out '%{http_code}' \
    --header "@$JWT_DEMO_DIR/authorization.header" \
    --header 'Content-Type: application/json' \
    --data "$(jq -nc --arg id "$external_id" \
      '{externalId:$id,customerId:"CUST-001",productCode:"INTERNET-500"}')" \
    "$API_URL/api/v1/requests") || return 1
  test "$code" = 201 && jq -e '.id' "$destination" >/dev/null
}

get_request() {
  curl --fail --silent --show-error \
    --header "@$JWT_DEMO_DIR/authorization.header" "$API_URL/api/v1/requests/$1"
}

await_completed() {
  local id="$1" attempt status
  for attempt in {1..60}; do
    status=$(get_request "$id" | jq -er '.status') || return 1
    if [ "$status" = COMPLETED ]; then return 0; fi
    if [ "$status" = FAILED ]; then return 1; fi
    sleep 1
  done
  printf 'Prazo de 60 consultas excedido: %s (%s)\n' "$id" "$status" >&2
  return 1
}

provider_calls() {
  curl --fail --silent --show-error "$MOCK_URL/__admin/requests" |
    jq --arg id "$1" '[.requests[] |
      select(.request.method == "POST" and .request.url == "/provider/v1/activate") |
      select((.request.body | fromjson).externalId == $id)] | length'
}

create_request "COMPOSE-OK-$DEMO_RUN" "$JWT_DEMO_DIR/completed.json" || exit 1
COMPLETED_ID=$(jq -er '.id' "$JWT_DEMO_DIR/completed.json")
await_completed "$COMPLETED_ID" || exit 1
get_request "$COMPLETED_ID" | jq
test "$(provider_calls "COMPOSE-OK-$DEMO_RUN")" = 1 || exit 1
```

O POST deve responder `201`; o GET autenticado deve chegar a `COMPLETED`. O
journal do WireMock comprova um POST real para `/provider/v1/activate` com o
mesmo `externalId`. Não se exige observar `PENDING` no primeiro GET: o worker
pode concluir antes da consulta. Se algum comando falhar, interrompa o roteiro
e confira `docker compose logs --tail=100 app`; não marque a validação como aprovada.

Para manter uma solicitação pendente sem depender de uma corrida com o scheduler,
use um override temporário. Ele altera apenas a API e não modifica o YAML versionado.
O arquivo fica em `.env.*`, ignorado pelo Git, em um caminho acessível também ao
cliente Docker via Snap, que pode ter seu próprio `/tmp`:

```bash
export WORKER_OVERRIDE_FILE="$PWD/.env.compose-worker-paused.yaml"
test ! -e "$WORKER_OVERRIDE_FILE" || exit 1
cat > "$WORKER_OVERRIDE_FILE" <<'YAML'
services:
  app:
    environment:
      WORKER_ENABLED: "false"
YAML
docker compose -f docker-compose.yaml -f "$WORKER_OVERRIDE_FILE" \
  up -d --no-deps --wait app
java scripts/LocalJwt.java token "$JWT_DEMO_DIR"
create_request "COMPOSE-PENDING-$DEMO_RUN" "$JWT_DEMO_DIR/pending.json" || exit 1
PENDING_ID=$(jq -er '.id' "$JWT_DEMO_DIR/pending.json")
get_request "$PENDING_ID" | jq -e '.status == "PENDING"' || exit 1
test "$(provider_calls "COMPOSE-PENDING-$DEMO_RUN")" = 0 || exit 1

docker compose stop app
docker compose logs --tail=40 app
docker inspect "$(docker compose ps -aq app)" \
  --format 'ExitCode={{.State.ExitCode}} OOMKilled={{.State.OOMKilled}}'
# Não adicione -v/--volumes: o volume contém a pendência que será retomada.
docker compose down

# Sem o override: o worker volta habilitado por padrão em uma nova instância.
docker compose up -d --wait
java scripts/LocalJwt.java token "$JWT_DEMO_DIR"
await_completed "$PENDING_ID" || exit 1
get_request "$PENDING_ID" | jq
get_request "$COMPLETED_ID" | jq -e '.status == "COMPLETED"' || exit 1
test "$(provider_calls "COMPOSE-PENDING-$DEMO_RUN")" = 1 || exit 1
test "$(provider_calls "COMPOSE-OK-$DEMO_RUN")" = 0 || exit 1

# Reinicie apenas a API e confira que nenhum dos pedidos foi reenviado.
docker compose restart app
docker compose up -d --wait
java scripts/LocalJwt.java token "$JWT_DEMO_DIR"
get_request "$PENDING_ID" | jq -e '.status == "COMPLETED"' || exit 1
get_request "$COMPLETED_ID" | jq -e '.status == "COMPLETED"' || exit 1
test "$(provider_calls "COMPOSE-PENDING-$DEMO_RUN")" = 1 || exit 1
test "$(provider_calls "COMPOSE-OK-$DEMO_RUN")" = 0 || exit 1
```

Os logs da parada devem conter `Graceful shutdown complete`; confira também
`OOMKilled=false` e ausência de SIGKILL (exit 137). Na validação local, a saída
normal ao receber SIGTERM foi 143. O journal do mock fica em memória e é zerado
no `down`/`up`: por isso, depois da recriação, deve haver uma chamada para a
pendência retomada e nenhuma para o pedido já concluído. `restart app` preserva
o journal e permite conferir novamente a ausência de reenvio.

Esta demonstração cobre retomada de `PENDING` ainda sem tentativa externa. Leases
vencidos só são retomados quando seguros pelas regras existentes; pedidos com
resultado indeterminado continuam protegidos contra reenvio e exigem reconciliação.
O roteiro não comprova recuperação de uma ativação interrompida durante o HTTP.

### Encerrar preservando os dados

```bash
docker compose down
rm -- "$JWT_PUBLIC_KEY_FILE" "$WORKER_OVERRIDE_FILE"
rm -- "$JWT_DEMO_DIR/completed.json" "$JWT_DEMO_DIR/pending.json"
unset API_URL MOCK_URL DEMO_RUN COMPLETED_ID PENDING_ID JWT_PUBLIC_KEY_FILE WORKER_OVERRIDE_FILE
unset -f create_request get_request await_completed provider_calls
```

Limpe também o material temporário e as variáveis JWT conforme o
[roteiro JWT](local-jwt.md#encerrar-e-remover-material). A chave privada e o Bearer
permanecem fora do repositório durante toda a demonstração; somente a cópia pública
é montada no container. O volume do PostgreSQL permanece. Para reutilizar esses
dados mais tarde, mantenha a senha e prepare uma nova chave pública/token antes
da subida. Não use `down --volumes`, `docker volume prune` ou `docker system prune`
como limpeza deste roteiro; a remoção de dados deve ser uma decisão separada.

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
A retomada de pendências foi validada posteriormente no roteiro completo com
Compose, conforme a evidência ao final deste guia.

## Evidência de validação do Compose — 29/10/2026

Validação em projeto Compose isolado, com portas dinâmicas em loopback para
evitar conflitos com serviços existentes:

| Verificação | Resultado |
| --- | --- |
| Configuração | `docker compose config --quiet` passou; senha ausente/vazia rejeitada; usuário compartilhado, porta externa customizada e URLs internas conferidos. |
| Build e inicialização | `docker compose up --build -d --wait`: três serviços `healthy`; API como `10001:10001` e montagem pública somente leitura. |
| Fluxo | Health 200 `UP`, GET sem token 401, POST autenticado 201 e GET posterior `COMPLETED`; chamada registrada no WireMock. |
| Volume | Registro preservado após `down` sem remoção do volume e nova subida dos três serviços. |
| Suíte | `./mvnw -B --no-transfer-progress clean verify`: 949 testes em 58 relatórios; 0 falhas, erros ou ignorados. |
| Limpeza | Removidos somente containers, rede, volume e material JWT do projeto de teste. |

Esse primeiro smoke test comprovou persistência de um registro concluído. A
validação complementar abaixo exercita a retomada de trabalho ainda pendente.

## Evidência do roteiro completo — 29/10/2026

Executados os blocos Bash da demonstração em projeto Compose isolado, com API
em `127.0.0.1:18080` e mock em `127.0.0.1:8081`, usando JWT temporário do utilitário.

| Verificação | Resultado |
| --- | --- |
| Build e configuração | `docker compose config --quiet` e `up --build -d --wait` passaram; três serviços saudáveis, API `10001:10001` e chave pública montada somente para leitura. |
| Fluxo autenticado | Health 200, GET sem Bearer 401, POST com Bearer 201; GET chegou a `COMPLETED` e o WireMock registrou exatamente uma chamada correspondente. |
| Pendência durável | Com `WORKER_ENABLED=false` apenas no override, o segundo POST retornou 201; GET confirmou `PENDING` e o journal não continha chamada para esse pedido. |
| Recriação e retomada | `stop app`, `down` sem remover o volume e nova subida sem override; o worker concluiu a pendência automaticamente e o pedido anterior permaneceu `COMPLETED`. O novo journal registrou uma chamada para a pendência e nenhuma para o pedido anterior. |
| Restart da API | Ambos permaneceram `COMPLETED`; contagens no journal preservadas, inclusive após vários ciclos do scheduler. Consulta ao PostgreSQL confirmou `attempt_count=1` e `reconciliation_required=false` nos dois registros. |
| Shutdown | Log `Graceful shutdown complete`, exit 143, `OOMKilled=false`. |
| Suíte | `./mvnw -B --no-transfer-progress clean verify`: 949 testes em 58 relatórios, 0 falhas, erros ou ignorados, incluindo PostgreSQL via Testcontainers. |
| Roteiros | Sintaxe Bash dos 18 blocos deste guia e de `local-jwt.md` conferida com `bash -n`. |
| Limpeza | Depois de provar a preservação entre subidas, removidos somente containers, rede, volume efêmero, override e material JWT do projeto de teste. Serviços existentes preservados. O roteiro de uso mantém o volume. |

O Docker via Snap não enxergou um override criado no `/tmp` do host. Um YAML
mínimo confirmou o isolamento; o roteiro usa `.env.compose-worker-paused.yaml`
no projeto, sem credenciais e ignorado pelo Git/contexto de build. Chave privada
e Bearer continuam no diretório temporário privado, acessado apenas pelo JDK/curl.
