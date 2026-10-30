#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/dev-common.sh"
require_tools java curl jq docker
positive_seconds DEMO_WAIT_TIMEOUT "${DEMO_WAIT_TIMEOUT:-60}"
load_config
curl --fail --silent --show-error --max-time 5 "$API_URL/actuator/health/readiness" >/dev/null ||
  fail 'API indisponível. Execute scripts/dev-up.sh e confira a readiness.'
jwt_material no

external_id="DEMO-$(date -u +%Y%m%dT%H%M%SZ)-$$-$RANDOM"
payload=$(jq -nc --arg id "$external_id" '{externalId:$id,customerId:"CUST-001",productCode:"INTERNET-500"}')
created=$(curl --fail-with-body --silent --show-error --max-time 10 \
  --header "@$JWT_DIR/authorization.header" --header 'Content-Type: application/json' \
  --data "$payload" "$API_URL/api/v1/requests") || fail 'Falha HTTP ao criar a solicitação de demonstração.'
request_id=$(jq -er '.id' <<< "$created") || fail 'Resposta de criação sem ID.'
[[ "$request_id" =~ ^[0-9a-fA-F-]{36}$ ]] || fail 'ID inválido na resposta de criação.'
printf 'Solicitação criada: %s (externalId=%s)\n' "$request_id" "$external_id"
deadline=$((SECONDS + ${DEMO_WAIT_TIMEOUT:-60}))
renew_at=$((SECONDS + 240))
status=$(jq -er '.status' <<< "$created")
while (( SECONDS < deadline )); do
  if (( SECONDS >= renew_at )); then
    java scripts/LocalJwt.java token "$JWT_DIR" || fail 'Falha ao renovar o JWT local.'
    renew_at=$((SECONDS + 240))
  fi
  remaining=$((deadline - SECONDS))
  request_timeout=$((remaining < 10 ? remaining : 10))
  result=$(curl --fail-with-body --silent --show-error --max-time "$request_timeout" \
    --header "@$JWT_DIR/authorization.header" "$API_URL/api/v1/requests/$request_id") ||
    fail "Falha HTTP ao consultar $request_id."
  status=$(jq -er '.status' <<< "$result") || fail 'Resposta de consulta sem status.'
  printf 'Estado: %s\n' "$status"
  case "$status" in
    COMPLETED|FAILED)
      jq . <<< "$result"
      printf 'Consulta concluída em estado terminal: %s.\n' "$status"
      exit 0 ;;
    PENDING|PROCESSING) ;;
    *) fail "Estado inesperado na resposta: $status" ;;
  esac
  (( SECONDS >= deadline )) || sleep 1
done
if [[ "$status" == PROCESSING ]]; then
  printf 'Limite de espera atingido em PROCESSING; o pedido pode estar aguardando reconciliação do resultado externo. Isso não é erro de execução do script.\n'
else
  printf 'Limite de espera atingido em %s; o processamento ainda não terminou. Isso não é erro de execução do script.\n' "$status"
fi
printf 'Consulte depois: GET %s/api/v1/requests/%s (com JWT local válido).\n' "$API_URL" "$request_id"
