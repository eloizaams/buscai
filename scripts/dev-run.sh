#!/usr/bin/env bash
# Sobe o backend (API + web/ estático via copyWebStatic, ver backend/build.gradle.kts) em
# http://localhost:8080. Variáveis sensíveis vêm de um .env na raiz do repo (gitignored) ou já
# exportadas no shell — ver backend/CLAUDE.md.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

if [ -f "$repo_root/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  source "$repo_root/.env"
  set +a
fi

required_vars=(DATABASE_URL DATABASE_USERNAME DATABASE_PASSWORD VOYAGE_API_KEY ANTHROPIC_API_KEY BUSCAI_API_KEY)
missing=()
for var in "${required_vars[@]}"; do
  if [ -z "${!var:-}" ]; then
    missing+=("$var")
  fi
done
if [ "${#missing[@]}" -gt 0 ]; then
  echo "Faltam variáveis de ambiente: ${missing[*]}" >&2
  echo "Defina-as em $repo_root/.env ou exporte antes de rodar (ver backend/CLAUDE.md)." >&2
  exit 1
fi

echo "Subindo backend + web em http://localhost:8080 ..."
cd "$repo_root/backend"
exec ./gradlew bootRun
