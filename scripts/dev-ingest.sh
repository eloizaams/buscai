#!/usr/bin/env bash
# Roda a ingestão de um PDF via IngestCommand (profile "ingest") — ver
# backend/src/main/kotlin/com/buscai/backend/ingestion/cli/IngestCommand.kt.
# Uso: passe os argumentos do IngestCommand como UMA string, exatamente como em --args:
#   scripts/dev-ingest.sh "--book-id=dom-casmurro --file=/caminho/livro.pdf --title='Dom Casmurro'"
#   scripts/dev-ingest.sh "--book-id=dom-casmurro --file=/caminho/livro.pdf --title='Dom Casmurro' --reindex"
#   scripts/dev-ingest.sh "--book-id=o-livro-dos-espiritos --file=/caminho/livro.pdf --title='O Livro dos Espíritos' --reference-style=numbered-item"
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

if [ -f "$repo_root/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  source "$repo_root/.env"
  set +a
fi

required_vars=(DATABASE_URL DATABASE_USERNAME DATABASE_PASSWORD VOYAGE_API_KEY)
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

if [ "$#" -eq 0 ]; then
  echo "Uso: $0 \"--book-id=<slug> --file=<caminho-do-pdf> --title=<titulo> [--reindex] [--reference-style=chapter|numbered-item]\"" >&2
  exit 1
fi

cd "$repo_root/backend"
SPRING_PROFILES_ACTIVE=ingest exec ./gradlew bootRun --args="$*"
