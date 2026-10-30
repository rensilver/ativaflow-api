#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/dev-common.sh"
compose_available
printf 'Encerrando os serviços e preservando o volume do PostgreSQL...\n'
docker compose down || fail 'Não foi possível encerrar os serviços. Confira docker compose ps.'
printf 'Serviços encerrados. Dados, .env e credenciais JWT locais preservados.\n'
