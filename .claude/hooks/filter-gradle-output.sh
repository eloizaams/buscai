#!/usr/bin/env bash
# Lê o JSON do evento PostToolUse via stdin, filtra o output de comandos Gradle
# para mostrar só o que importa (falhas/erros), reduzindo tokens de contexto.
set -euo pipefail

INPUT=$(cat)
COMMAND=$(echo "$INPUT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('tool_input',{}).get('command',''))" 2>/dev/null || echo "")

# Só age em comandos gradle de teste/build
if [[ "$COMMAND" != *"gradlew"* ]]; then
  exit 0
fi

TOOL_OUTPUT=$(echo "$INPUT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('tool_output',''))" 2>/dev/null || echo "")

# Se passou (BUILD SUCCESSFUL), não precisa reinjetar nada — economiza contexto
if echo "$TOOL_OUTPUT" | grep -q "BUILD SUCCESSFUL"; then
  exit 0
fi

# Se falhou, extrai só as linhas relevantes (FAILED, erros de compilação, stack de assert)
FILTERED=$(echo "$TOOL_OUTPUT" | grep -E "FAILED|error:|Exception|AssertionError|BUILD FAILED" | head -n 60)

if [ -n "$FILTERED" ]; then
  python3 -c "
import json
print(json.dumps({
    'hookSpecificOutput': {
        'hookEventName': 'PostToolUse',
        'additionalContext': 'Resumo filtrado do build (linhas irrelevantes removidas):\n' + '''$FILTERED'''
    }
}))
"
fi

exit 0
