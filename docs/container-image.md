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
(`/__admin/health`). Seu próprio check consulta `/actuator/health`, que inclui a
conexão com o banco, usando o curl já presente na imagem. O check da API não chama
o provedor. A espera de inicialização usa `depends_on: condition: service_healthy`,
conforme a [referência do Compose](https://docs.docker.com/reference/compose-file/services/).

O volume nomeado `postgres-data` preserva os dados entre recriações. Para parar
os serviços mantendo os dados, execute `docker compose down`, sem `--volumes`.
A API recebe até 40 segundos para encerrar, acima do timeout padrão de 30 segundos
da aplicação. Para subir novamente, mantenha `.env` e a chave pública disponíveis
e execute `docker compose up -d --wait`. Alterar a senha no `.env` não altera a
senha de um banco já inicializado no volume; mantenha as credenciais coerentes.

Depois de encerrar o ambiente, remova a cópia pública com
`rm -- "$JWT_PUBLIC_KEY_FILE"` e limpe o material temporário conforme o
[roteiro JWT](local-jwt.md#encerrar-e-remover-material).
A demonstração autenticada completa e a validação de retomada de pendências
continuam no próximo item de A01.

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
A validação de retomada de pendências pertence ao roteiro completo com Compose,
ainda pendente em A01.

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

Esse smoke test comprova persistência de um registro concluído. A validação de
retomada de trabalho ainda pendente após restart continua no próximo item de A01.
