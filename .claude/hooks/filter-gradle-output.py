#!/usr/bin/env python3
"""
Hook PostToolUse (Bash): filtra o output de comandos Gradle para reinjetar só o que
importa (falhas/erros) como contexto adicional, reduzindo tokens de contexto.

Nota: o payload do PostToolUse entrega o resultado da ferramenta em `tool_response`
(para Bash, um objeto com `stdout`/`stderr`) — não em `tool_output`.
"""
import json
import re
import sys

RELEVANT = re.compile(r"FAILED|error:|Exception|AssertionError|BUILD FAILED")


def main():
    try:
        data = json.load(sys.stdin)
    except Exception:
        sys.exit(0)  # payload ilegível: não filtra, falha aberta

    command = data.get("tool_input", {}).get("command", "")
    if "gradlew" not in command:
        sys.exit(0)

    response = data.get("tool_response", "")
    if isinstance(response, dict):
        output = str(response.get("stdout", "")) + "\n" + str(response.get("stderr", ""))
    else:
        output = str(response)

    # Se passou, não reinjeta nada — economiza contexto.
    if "BUILD SUCCESSFUL" in output:
        sys.exit(0)

    filtered = [line for line in output.splitlines() if RELEVANT.search(line)][:60]
    if not filtered:
        sys.exit(0)

    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PostToolUse",
            "additionalContext": (
                "Resumo filtrado do build (linhas irrelevantes removidas):\n"
                + "\n".join(filtered)
            ),
        }
    }))
    sys.exit(0)


if __name__ == "__main__":
    main()
