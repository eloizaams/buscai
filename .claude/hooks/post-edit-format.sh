#!/usr/bin/env bash
# PostToolUse (Write|Edit): formata e roda os testes rápidos do módulo Kotlin tocado.
# android/ e backend/ são dois builds Gradle independentes — precisa rodar no diretório certo.
set -euo pipefail

INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('tool_input',{}).get('file_path',''))" 2>/dev/null || echo "")

# Só age em arquivos Kotlin/Gradle Kotlin DSL — não formatar/testar por editar docs, XML, etc.
case "$FILE_PATH" in
  *.kt|*.kts) ;;
  *) exit 0 ;;
esac

case "$FILE_PATH" in
  "$CLAUDE_PROJECT_DIR"/android/*|android/*)
    cd "$CLAUDE_PROJECT_DIR/android"
    ./gradlew --console=plain -q ktlintFormat
    ./gradlew --console=plain -q :app:testDebugUnitTest
    ;;
  "$CLAUDE_PROJECT_DIR"/backend/*|backend/*)
    cd "$CLAUDE_PROJECT_DIR/backend"
    ./gradlew --console=plain -q ktlintFormat
    ./gradlew --console=plain -q test
    ;;
  *) exit 0 ;;
esac
