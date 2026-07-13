#!/usr/bin/env python3
"""
Hook PreToolUse: bloqueia leitura/edição/escrita em arquivos sensíveis do projeto
(keystores, google-services.json, local.properties, .env). Exit 2 bloqueia a chamada
e devolve o motivo para o Claude via stderr.
"""
import json
import sys

BLOCKED_PATTERNS = [
    "local.properties",
    "google-services.json",
    ".jks",
    ".keystore",
    ".env",
    "secrets.",
]

def main():
    try:
        data = json.load(sys.stdin)
    except Exception:
        sys.exit(0)  # se não conseguir parsear, não bloqueia — falha aberta para não travar o dev

    tool_input = data.get("tool_input", {})
    path = (tool_input.get("file_path") or tool_input.get("path") or
            tool_input.get("command", ""))

    lowered = str(path).lower()
    for pattern in BLOCKED_PATTERNS:
        if pattern in lowered:
            print(
                f"Acesso bloqueado: '{pattern}' corresponde a um arquivo sensível. "
                "Peça ao usuário para lidar com esse arquivo manualmente.",
                file=sys.stderr,
            )
            sys.exit(2)

    sys.exit(0)

if __name__ == "__main__":
    main()
