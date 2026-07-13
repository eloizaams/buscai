#!/usr/bin/env python3
"""
Status line do Claude Code para o projeto buscai.

Lê o JSON de input (stdin) documentado em
https://docs.claude.com/en/docs/claude-code/statusline e monta uma linha com:
diretório atual (nome curto), branch git, modelo e um medidor de uso de
contexto — para saber quando compactar a conversa (ver regra "GESTAO DE
CONTEXTO" em ~/.claude/CLAUDE.md).

Métrica de contexto usada: `context_window.used_percentage`, já pré-calculado
pelo Claude Code a partir do último uso de tokens da API (input + cache).
Se esse campo vier nulo (ainda sem nenhuma resposta na sessão), calculamos
manualmente `total_input_tokens / context_window_size * 100` como fallback;
se nem isso existir, omitimos o medidor em vez de mostrar um número errado.

Falha aberta: qualquer erro inesperado imprime uma linha mínima (ou vazia)
em vez de quebrar a UI do Claude Code.
"""
import json
import os
import subprocess
import sys

RESET = "\033[0m"
DIM = "\033[2m"
GREEN = "\033[32m"
YELLOW = "\033[33m"
RED = "\033[1;31m"
CYAN = "\033[36m"


def git_branch(cwd):
    try:
        # --no-optional-locks: nunca esperar/disputar o lock do índice do git
        # (a status line roda com frequência e não pode travar por causa disso).
        result = subprocess.run(
            ["git", "--no-optional-locks", "-C", cwd, "rev-parse", "--abbrev-ref", "HEAD"],
            capture_output=True,
            text=True,
            timeout=1,
        )
        if result.returncode != 0:
            return None
        branch = result.stdout.strip()
        return branch or None
    except Exception:
        return None


def context_meter(context_window):
    if not isinstance(context_window, dict):
        return None

    pct = context_window.get("used_percentage")
    if pct is None:
        total_input = context_window.get("total_input_tokens")
        size = context_window.get("context_window_size")
        if isinstance(total_input, (int, float)) and isinstance(size, (int, float)) and size > 0:
            pct = total_input / size * 100
        else:
            return None

    if pct >= 90:
        color, warning = RED, " ⚠️ COMPACTAR AGORA"
    elif pct >= 70:
        color, warning = YELLOW, " ⚠ considerar compactar"
    else:
        color, warning = GREEN, ""

    return "{}ctx {:.0f}%{}{}".format(color, pct, warning, RESET)


def main():
    try:
        data = json.load(sys.stdin)
    except Exception:
        print("")
        return

    try:
        workspace = data.get("workspace") or {}
        cwd = workspace.get("current_dir") or data.get("cwd") or os.getcwd()
        dirname = os.path.basename(cwd.rstrip("/")) or cwd

        model = (data.get("model") or {}).get("display_name") or "?"

        parts = ["{}{}{}".format(CYAN, dirname, RESET)]

        branch = git_branch(cwd)
        if branch:
            parts.append("{}({}){}".format(DIM, branch, RESET))

        parts.append("{}{}{}".format(DIM, model, RESET))

        meter = context_meter(data.get("context_window") or {})
        if meter:
            parts.append(meter)

        print(" | ".join(parts))
    except Exception:
        print("")


if __name__ == "__main__":
    main()
