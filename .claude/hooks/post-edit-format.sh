#!/usr/bin/env bash
# PostToolUse (Write|Edit): formata o módulo Kotlin tocado com ktlintFormat.
# android/ e backend/ são dois builds Gradle independentes — precisa rodar no diretório certo.
#
# De propósito, este hook NÃO roda testes: a suíte a cada edição é lenta (o contextLoads do
# Spring sobe um contexto inteiro) e o build android exige SDK que nem todo ambiente tem.
# Testes são responsabilidade do agente ao concluir a tarefa (regra do kotlin-implementer
# em .claude/agents/) e do CI (.github/workflows/ci.yml).
set -uo pipefail

INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('tool_input',{}).get('file_path',''))" 2>/dev/null || echo "")

# Só age em arquivos Kotlin/Gradle Kotlin DSL — não formatar por editar docs, XML, etc.
case "$FILE_PATH" in
  *.kt|*.kts) ;;
  *) exit 0 ;;
esac

case "$FILE_PATH" in
  "$CLAUDE_PROJECT_DIR"/android/*|android/*)
    MODULE_DIR="$CLAUDE_PROJECT_DIR/android"
    ;;
  "$CLAUDE_PROJECT_DIR"/backend/*|backend/*)
    MODULE_DIR="$CLAUDE_PROJECT_DIR/backend"
    ;;
  *) exit 0 ;;
esac

cd "$MODULE_DIR" || exit 0

# ktlintFormat do módulo tocado; se falhar (ex.: android sem SDK local, erro de sintaxe no
# meio de uma edição em várias etapas), devolve o motivo ao Claude sem bloquear a sessão.
if ! OUTPUT=$(./gradlew --console=plain -q ktlintFormat 2>&1); then
  echo "ktlintFormat falhou em $MODULE_DIR:" >&2
  echo "$OUTPUT" | grep -E "error|Error|FAILED" | head -n 20 >&2
  exit 1
fi

exit 0
