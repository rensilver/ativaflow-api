#!/usr/bin/env bash
# Funções compartilhadas pelos roteiros locais. Não carrega .env como código shell.
set -euo pipefail
umask 077
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."

fail() { printf 'Erro: %s\n' "$*" >&2; exit 1; }

require_tools() {
  local tool
  for tool in "$@"; do
    command -v "$tool" >/dev/null 2>&1 || fail "Instale $tool e tente novamente."
  done
}

positive_seconds() {
  [[ "$2" =~ ^[1-9][0-9]{0,5}$ ]] || fail "$1 deve ser um inteiro positivo em segundos (até 999999)."
}

compose_available() {
  require_tools docker
  docker compose version >/dev/null 2>&1 || fail 'Docker Compose indisponível.'
  docker info >/dev/null 2>&1 || fail 'Docker indisponível. Inicie o daemon e confira a permissão de acesso.'
}

load_config() {
  local config issuer audiences port
  [[ -f .env && ! -L .env ]] || fail 'Prepare um .env regular com scripts/dev-up.sh.'
  chmod 600 .env
  config=$(docker compose config --format json) || fail 'Configuração Compose inválida. Confira o .env (incluindo DB_PASSWORD); nenhum valor existente foi substituído.'
  issuer=$(jq -r '.services.app.environment.SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI' <<< "$config")
  audiences=$(jq -r '.services.app.environment.SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES' <<< "$config")
  [[ "$issuer" == https://ativaflow.local && ",$audiences," == *,ativaflow-api,* ]] ||
    fail 'Issuer/audience incompatíveis com LocalJwt. Configuração preservada; consulte docs/local-development.md para execução manual.'
  PUBLIC_KEY_FILE=$(jq -er '.secrets["jwt-public-key"].file' <<< "$config")
  port=$(jq -er '.services.app.ports[] | select(.target == 8080) | .published' <<< "$config")
  API_URL="http://127.0.0.1:$port"
}

jwt_material() {
  local allow_create="$1" saved_dir=''
  if [[ -e .env.jwt-dir || -L .env.jwt-dir ]]; then
    [[ -f .env.jwt-dir && ! -L .env.jwt-dir ]] || fail '.env.jwt-dir deve ser um arquivo regular.'
    saved_dir=$(cat .env.jwt-dir)
    [[ -n "$saved_dir" ]] || fail '.env.jwt-dir está vazio; restaure a referência às chaves locais.'
    [[ -z "${JWT_DEMO_DIR:-}" || "$JWT_DEMO_DIR" == "$saved_dir" ]] ||
      fail 'JWT_DEMO_DIR difere de .env.jwt-dir. Nenhuma credencial foi substituída.'
  fi
  JWT_DIR=${saved_dir:-${JWT_DEMO_DIR:-}}
  if [[ -z "$JWT_DIR" ]]; then
    [[ "$allow_create" == yes ]] || fail 'Credenciais locais ausentes. Execute scripts/dev-up.sh.'
    [[ ! -e "$PUBLIC_KEY_FILE" && ! -L "$PUBLIC_KEY_FILE" ]] ||
      fail 'Já existe uma chave pública sem referência à privada. Informe JWT_DEMO_DIR com o par original; a chave existente foi preservada.'
    JWT_DIR=$(java scripts/LocalJwt.java create) || fail 'Não foi possível gerar as credenciais com LocalJwt.'
    # Persiste a referência imediatamente para permitir retomar uma preparação interrompida.
    (set -o noclobber; printf '%s\n' "$JWT_DIR" > .env.jwt-dir)
    saved_dir=$JWT_DIR
    printf 'Credenciais JWT locais criadas em %s (fora do container).\n' "$JWT_DIR"
  fi
  [[ -d "$JWT_DIR" && ! -L "$JWT_DIR" ]] ||
    fail 'Diretório JWT ausente ou inválido. Restaure as chaves indicadas em .env.jwt-dir; não haverá rotação automática.'
  local file
  for file in private.pem public.pem; do
    [[ -f "$JWT_DIR/$file" && ! -L "$JWT_DIR/$file" ]] || fail "Material JWT inválido: $file ausente ou symlink. Chaves preservadas."
  done
  [[ ! -L "$JWT_DIR/authorization.header" ]] || fail 'authorization.header não pode ser symlink.'
  chmod 700 "$JWT_DIR"
  chmod 600 "$JWT_DIR/private.pem" "$JWT_DIR/public.pem"
  if [[ -e "$JWT_DIR/authorization.header" ]]; then chmod 600 "$JWT_DIR/authorization.header"; fi
  if [[ -e "$PUBLIC_KEY_FILE" || -L "$PUBLIC_KEY_FILE" ]]; then
    [[ -f "$PUBLIC_KEY_FILE" && ! -L "$PUBLIC_KEY_FILE" ]] || fail 'A chave pública do Compose deve ser um arquivo regular.'
    cmp -s "$JWT_DIR/public.pem" "$PUBLIC_KEY_FILE" ||
      fail 'A chave pública do Compose difere do par local. Nenhuma chave foi substituída; restaure o par original ou siga docs/local-development.md.'
  fi
  # O próprio utilitário valida o par e renova somente o token de cinco minutos.
  java scripts/LocalJwt.java token "$JWT_DIR" || fail 'Par JWT inválido. Restaure as credenciais originais; não haverá rotação automática.'
  if [[ ! -f "$PUBLIC_KEY_FILE" ]]; then
    [[ "$allow_create" == yes ]] || fail 'Chave pública do Compose ausente. Execute scripts/dev-up.sh.'
    (set -o noclobber; cat "$JWT_DIR/public.pem" > "$PUBLIC_KEY_FILE")
  fi
  chmod 444 "$PUBLIC_KEY_FILE"
  if [[ -z "$saved_dir" ]]; then
    (set -o noclobber; printf '%s\n' "$JWT_DIR" > .env.jwt-dir)
  fi
  chmod 600 .env.jwt-dir "$JWT_DIR/authorization.header"
}
