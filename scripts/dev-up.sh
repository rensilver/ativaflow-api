#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/dev-common.sh"
require_tools java curl jq
compose_available
positive_seconds DEV_WAIT_TIMEOUT "${DEV_WAIT_TIMEOUT:-180}"

if [[ ! -e .env && ! -L .env ]]; then
  # Senha aleatória apenas para o PostgreSQL local; nunca modifica um .env existente.
  password=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
  (set -o noclobber; awk -v password="$password" '
    /^DB_PASSWORD=/ { print "DB_PASSWORD=" password; next }
    /^SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI=/ { print "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI=https://ativaflow.local"; next }
    /^SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=/ { print "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=ativaflow-api"; next }
    { print }
  ' .env.example > .env)
  unset password
  printf '.env criado a partir de .env.example com credenciais exclusivas de desenvolvimento.\n'
else
  printf 'Preservando a configuração do .env existente.\n'
fi
load_config
jwt_material yes
printf 'Iniciando API, PostgreSQL e activation-provider-mock; aguardando os healthchecks...\n'
if ! docker compose up --build --detach --wait --wait-timeout "${DEV_WAIT_TIMEOUT:-180}" app postgres activation-provider-mock; then
  docker compose ps >&2 || true
  fail 'Inicialização falhou ou excedeu o prazo. Consulte docker compose logs --tail=100 app postgres activation-provider-mock. Dados e credenciais foram preservados.'
fi
curl --fail --silent --show-error --max-time 5 "$API_URL/actuator/health/readiness" >/dev/null ||
  fail "Containers iniciados, mas readiness inacessível em $API_URL. Confira as portas e docker compose logs --tail=100 app."
docker compose ps
printf 'Ambiente pronto em %s. Demonstração: ./scripts/demo.sh\n' "$API_URL"
