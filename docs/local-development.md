# Referência de desenvolvimento local

Requisitos: Bash, JDK 21, Docker Engine com Compose que suporte `up --wait`, curl e
jq. Não é necessário Maven instalado. Execute na raiz do projeto; os scripts
também podem ser chamados por caminho absoluto a partir de outro diretório.
As portas padrão são `8080` para API e `8081` para WireMock, somente em loopback.

## Scripts

```bash
./scripts/dev-up.sh
./scripts/demo.sh
./scripts/dev-down.sh
```

`dev-up.sh` copia `.env.example` somente se `.env` não existir. A senha do banco é
aleatória e local; issuer e audience usam os valores do `LocalJwt.java`.
Um `.env` existente não tem seu conteúdo alterado. Se estiver incompleto (por
exemplo, `DB_PASSWORD` vazio), corrija-o manualmente. Variáveis exportadas no shell
continuam tendo precedência, conforme o Compose. O `.env` é interpretado pelo
Compose, nunca executado como código shell pelos scripts.

As chaves são geradas pelo `LocalJwt.java` fora do Git, em diretório temporário
privado (`0700`), com chave privada e headers Bearer em `0600`. A referência ao
diretório fica em `.env.jwt-dir` (`0600`), ignorado pelo Git, assim como `.env`.
Somente a cópia pública `.env.jwt-public.pem` (`0444`, para leitura pelo UID 10001)
é montada na API. O contexto Docker já exclui configurações e chaves locais.
Nenhuma chave privada ou token é enviado ao container.

As próximas execuções reutilizam o par; apenas o JWT de cinco minutos é renovado.
Para reaproveitar um par criado manualmente, informe `JWT_DEMO_DIR` apontando para
o diretório original antes da primeira execução. Uma referência existente tem
precedência e valores conflitantes são recusados. Se o diretório temporário for
removido (por exemplo, após reboot), se as chaves forem inválidas ou se a cópia
pública divergir, o script interrompe sem substituir as chaves. Restaure o par
original ou faça a rotação manual e explícita descrita abaixo. Issuer/audience
externos exigem o procedimento manual com tokens do emissor correspondente.

`DEV_WAIT_TIMEOUT=180` limita a espera dos healthchecks após o build; o build e o
download das imagens podem levar mais tempo. A API só é anunciada como pronta
após os três healthchecks e uma consulta HTTP à readiness pela porta publicada.
`SERVER_PORT` no `.env` também é respeitado pela demonstração.

`DEMO_WAIT_TIMEOUT=60` limita a espera das consultas, em segundos. Cada execução
cria um `externalId` novo, usa o JWT local sem exibi-lo no terminal e para em
`COMPLETED` ou `FAILED`. Ambos são resultados de negócio com execução bem-sucedida
(exit 0). A espera em `PENDING` ou `PROCESSING` também termina com exit 0 e uma
mensagem de acompanhamento. O contrato público não expõe o indicador interno de
reconciliação: `PROCESSING` pode indicar resultado externo incerto ou trabalho
em andamento. O script informa essa possibilidade sem tentar liberar reenvio.
Falhas HTTP, credenciais inválidas e indisponibilidade retornam código diferente
de zero. Tokens são renovados durante esperas superiores a quatro minutos.

`dev-down.sh` executa `docker compose down`, sem `--volumes`. Não remove dados nem
material JWT. Preserve a senha: alterar `.env` não muda a senha de um PostgreSQL
já inicializado no volume. O Compose permanece utilizável independentemente dos
scripts. Não há alteração nos endpoints, no Spring Security ou no worker.

## Diagnóstico e manutenção

```bash
docker compose ps
docker compose logs --tail=100 app postgres activation-provider-mock
curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

Ajuste a porta do curl se tiver configurado `SERVER_PORT`. Em caso de falha, os
containers permanecem disponíveis para diagnóstico. Não remova o volume para
corrigir credenciais: restaure a senha usada na sua criação.

Para uma rotação local intencional, pare os serviços com `dev-down.sh`, guarde o
par original se ainda precisar dele, remova explicitamente `.env.jwt-dir` e a
cópia pública indicada por `JWT_PUBLIC_KEY_FILE` (padrão `.env.jwt-public.pem`),
desfaça um eventual `export JWT_DEMO_DIR` e execute `dev-up.sh` novamente. Isso
invalida os tokens antigos, preservando `.env` e o banco. O diretório privado
original só deve ser removido quando não for mais necessário.

Os testes específicos dos scripts não requerem daemon Docker e usam o utilitário
JWT real, substituindo somente as fronteiras Docker/HTTP:

```bash
bash scripts/tests/dev-scripts-test.sh
bash ./mvnw -B --no-transfer-progress -Dtest=LocalJwtMaterialUnitTests test
```

## Procedimento manual com Compose

Os comandos abaixo também permitem executar a demonstração sem os scripts.
Caso já tenha usado `dev-up.sh`, reutilize seu diretório em vez de gerar chaves:
`export JWT_DEMO_DIR="$(cat .env.jwt-dir)"`. Não substitua uma chave pública
existente por outro par sem uma rotação intencional.

### Preparar e iniciar

1. Copie `.env.example` para `.env` e preencha `DB_PASSWORD`. Mantenha
   `SERVER_PORT=8080` para seguir os exemplos. O Compose carrega esse arquivo.

   ```bash
   test -e .env || cp .env.example .env
   ```

2. Gere as credenciais locais e monte somente a chave pública na API:

   ```bash
   if [[ -f .env.jwt-dir ]]; then
     export JWT_DEMO_DIR="$(cat .env.jwt-dir)"
   elif [[ -z "${JWT_DEMO_DIR:-}" ]]; then
     export JWT_DEMO_DIR="$(java scripts/LocalJwt.java create)"
   fi
   source "$JWT_DEMO_DIR/env.sh"
   export JWT_PUBLIC_KEY_FILE="$PWD/.env.jwt-public.pem"
   if [[ -e "$JWT_PUBLIC_KEY_FILE" ]]; then
     cmp -- "$JWT_DEMO_DIR/public.pem" "$JWT_PUBLIC_KEY_FILE" || exit 1
   else
     cp -- "$JWT_DEMO_DIR/public.pem" "$JWT_PUBLIC_KEY_FILE"
   fi
   chmod 0444 "$JWT_PUBLIC_KEY_FILE"
   docker compose config --quiet
   docker compose up --build -d --wait
   curl --fail http://localhost:8080/actuator/health/readiness
   ```

3. Renove o token após o build (ele dura cinco minutos), crie um pedido e guarde o ID:

   ```bash
   java scripts/LocalJwt.java token "$JWT_DEMO_DIR"
   export DEMO_EXTERNAL_ID="DEMO-$(date -u +%Y%m%dT%H%M%SZ)-$RANDOM"
   export DEMO_PAYLOAD="$(jq -nc --arg id "$DEMO_EXTERNAL_ID" \
     '{externalId:$id,customerId:"CUST-001",productCode:"INTERNET-500"}')"
   curl --fail-with-body --silent --show-error \
     --dump-header "$JWT_DEMO_DIR/post.headers" \
     --header "@$JWT_DEMO_DIR/authorization.header" \
     --header 'Content-Type: application/json' --data "$DEMO_PAYLOAD" \
     http://localhost:8080/api/v1/requests > "$JWT_DEMO_DIR/created.json"
   cat "$JWT_DEMO_DIR/post.headers"
   jq . "$JWT_DEMO_DIR/created.json"
   export DEMO_REQUEST_ID="$(jq -er '.id' "$JWT_DEMO_DIR/created.json")"
   ```

4. Consulte até observar `COMPLETED`; a primeira consulta pode mostrar `PENDING`
   ou `PROCESSING`:

   ```bash
   curl --fail-with-body --silent --show-error \
     --header "@$JWT_DEMO_DIR/authorization.header" \
     "http://localhost:8080/api/v1/requests/$DEMO_REQUEST_ID" | jq .
   ```

O POST retorna `201` e `Location`. Repetir o mesmo payload e consumidor retorna
`200`, com o mesmo ID e estado atual; alterar cliente/produto mantendo `externalId`
retorna `409`. A listagem está em `GET /api/v1/requests`, com filtros opcionais
`status`, `customerId` e paginação `page`/`size`.
Consulte os contratos em [Swagger UI](http://localhost:8080/swagger-ui.html).

O Compose inicia API, PostgreSQL e WireMock. O mock retorna `204` em
`POST /provider/v1/activate`; seus endpoints administrativos ficam em
`http://localhost:8081/__admin`. Configuração, demonstração de restart e execução
com banco próprio estão no [guia de containers](container-image.md).
Para executar a API pelo Maven, veja o [guia de JWT local](local-jwt.md).

### Executar os testes

Com JDK 21 e Docker disponíveis, execute:

```bash
bash ./mvnw -B --no-transfer-progress verify
```

Os testes criam PostgreSQL com Testcontainers e servidores WireMock isolados;
não precisam do Compose nem do `.env`. Os tipos de teste e sua finalidade estão
na [seção de testes do README](../README.md#testes).

### Encerrar o ambiente

```bash
docker compose down
unset JWT_DEMO_DIR JWT_PUBLIC_KEY_FILE DEMO_EXTERNAL_ID DEMO_PAYLOAD DEMO_REQUEST_ID
```

O volume do banco permanece após `down`; preserve a senha para reutilizá-lo.
Para subir novamente, reutilize o mesmo material JWT e mantenha a senha. A limpeza
intencional das chaves está na seção de manutenção acima.

