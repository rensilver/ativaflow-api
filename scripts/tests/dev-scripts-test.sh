#!/usr/bin/env bash
# Testes dos efeitos locais; Docker/HTTP substituídos somente nas fronteiras externas.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
[[ -f "$root/scripts/dev-up.sh" ]] || { echo 'FAIL: dev-up.sh ainda não existe'; exit 1; }
fixture=$(mktemp -d)
trap 'code=$?; if (( code != 0 )); then printf "FAIL: linha %s (exit %s)\n" "$LINENO" "$code"; tail -n 8 "$fixture"/*.log 2>/dev/null || true; fi; rm -rf -- "$fixture"' EXIT
mkdir -p "$fixture/project/scripts" "$fixture/bin" "$fixture/jwt"
cp "$root"/scripts/{dev-common.sh,dev-up.sh,dev-down.sh,demo.sh,LocalJwt.java} "$fixture/project/scripts/"
cp "$root/.env.example" "$fixture/project/"
# As chaves continuam reais e fora do checkout, incluindo nos testes.
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$fixture/jwt"
export TEST_FIXTURE="$fixture"
export PATH="$fixture/bin:$PATH"
cat > "$fixture/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  'info'|'compose version') exit 0 ;;
  'compose config --format json')
    jq -n --arg pub "$PWD/.env.jwt-public.pem" '{services:{app:{environment:{SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI:"https://ativaflow.local",SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES:"ativaflow-api"},ports:[{target:8080,published:"8080"}]}},secrets:{"jwt-public-key":{file:$pub}}}' ;;
  'compose up '*) [[ ! -f "$TEST_FIXTURE/fail-up" ]] ;;
  'compose ps') exit 0 ;;
  'compose down') touch "$TEST_FIXTURE/stopped" ;;
  *) echo "Chamada Docker inesperada: $*" >&2; exit 1 ;;
esac
DOCKER
cat > "$fixture/bin/curl" <<'CURL'
#!/usr/bin/env bash
set -euo pipefail
[[ "$*" != *'/actuator/health/readiness'* ]] || exit 0
case "$(cat "$TEST_FIXTURE/scenario")" in
  HTTP_ERROR) exit 22 ;;
  *) printf '{"id":"12345678-1234-1234-1234-123456789abc","status":"%s"}\n' "$(cat "$TEST_FIXTURE/scenario")" ;;
esac
CURL
chmod +x "$fixture/bin/"*
cd "$fixture/project"
bash scripts/dev-up.sh > "$fixture/up.log" 2>&1
[[ -s .env && -s .env.jwt-dir && -s .env.jwt-public.pem ]]
[[ $(stat -c %a .env) == 600 && $(stat -c %a .env.jwt-dir) == 600 ]]
material=$(cat .env.jwt-dir)
[[ $(stat -c %a "$material") == 700 && $(stat -c %a "$material/private.pem") == 600 ]]
[[ $(stat -c %a .env.jwt-public.pem) == 444 ]]
[[ -n $(sed -n 's/^DB_PASSWORD=//p' .env) ]]
cp .env "$fixture/original.env"
cp "$material/private.pem" "$fixture/original-private.pem"
cp .env.jwt-public.pem "$fixture/original-public.pem"
bash scripts/dev-up.sh > "$fixture/reuse.log" 2>&1
cmp .env "$fixture/original.env"
cmp "$material/private.pem" "$fixture/original-private.pem"
cmp .env.jwt-public.pem "$fixture/original-public.pem"
# Uma chave pública conflitante jamais deve ser substituída.
chmod 600 .env.jwt-public.pem
printf 'existing-public-key\n' > .env.jwt-public.pem
if bash scripts/dev-up.sh > "$fixture/conflict.log" 2>&1; then echo 'FAIL: chave conflitante aceita'; exit 1; fi
[[ $(cat .env.jwt-public.pem) == existing-public-key ]]
cat "$fixture/original-public.pem" > .env.jwt-public.pem
chmod 444 .env.jwt-public.pem
# Falhas de inicialização não podem ser anunciadas como sucesso.
touch "$fixture/fail-up"
if bash scripts/dev-up.sh > "$fixture/start-failure.log" 2>&1; then echo 'FAIL: falha do Compose ignorada'; exit 1; fi
rm "$fixture/fail-up"
for state in COMPLETED FAILED PROCESSING PENDING; do
  echo "$state" > "$fixture/scenario"
  DEMO_WAIT_TIMEOUT=1 bash scripts/demo.sh > "$fixture/demo-$state.log" 2>&1
  grep -q "$state" "$fixture/demo-$state.log"
done
grep -q 'reconciliação' "$fixture/demo-PROCESSING.log"
echo HTTP_ERROR > "$fixture/scenario"
if bash scripts/demo.sh > "$fixture/http-failure.log" 2>&1; then echo 'FAIL: falha HTTP ignorada'; exit 1; fi
bash scripts/dev-down.sh > "$fixture/down.log" 2>&1
[[ -f "$fixture/stopped" && -f .env && -f "$material/private.pem" ]]
cmp .env "$fixture/original.env"
echo 'PASS: configuração preservada, chaves reutilizadas, conflitos recusados, estados e falhas tratados.'
