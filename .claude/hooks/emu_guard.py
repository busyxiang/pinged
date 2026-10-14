#!/usr/bin/env python3
"""PreToolUse hook: refuses a Bash command that reaches the emulator outside
`.claude/bin/emu`'s lease.

Sessions in other worktrees share the one emulator, so an unleased install or
`am instrument` lands in the middle of someone else's run. What counts as
reaching it: `adb` other than a few read-only subcommands, and a Gradle task
named connected*, install* or uninstall*. It is a guard against habit, not
a sandbox: a command built at runtime (`sh -c "$x"`, a script file) passes.

A command this cannot parse is let through: refusing it would block a
heredoc that only mentions adb, and the lease is a courtesy among sessions,
not a security boundary.
"""
import json
import os
import re
import shlex
import sys

# Subcommands that touch no app, setting or file on the device, so they are
# safe while another session holds the lease.
ADB_READ_ONLY = {"devices", "version", "get-state", "get-serialno", "start-server"}
# adb's own options that take a value, so the subcommand is found after them.
ADB_VALUED = {"-s", "-t", "-H", "-P", "-L"}
DEVICE_TASK = re.compile(r"^(connected|install|uninstall)")
# Prefixes that run the next word as the command.
WRAPPERS = {"env", "time", "nohup", "exec", "command", "nice", "stdbuf"}
# shlex runs adjacent punctuation together (`;\n`, `&&`), so a token of
# nothing but these is a separator.
SEPARATOR_CHARS = set(";&|()\n")
SHELLS = {"sh", "bash", "zsh", "fish", "dash"}


HEREDOC = re.compile(r"<<(-?)\s*(['\"]?)([A-Za-z_][A-Za-z0-9_]*)\2")


def without_heredocs(command):
    """The command with each heredoc's body dropped. A body is data: a
    script that quotes `... && adb shell ...` would otherwise read as a
    command after the `&&`."""
    out, lines = [], iter(command.split("\n"))
    for line in lines:
        out.append(line)
        for strip_tabs, _, word in HEREDOC.findall(line):
            for body in lines:
                if (body.lstrip("\t") if strip_tabs else body) == word:
                    break
    return "\n".join(out)


def segments(command):
    command = without_heredocs(command).replace("\\\n", " ")
    # A newline ends a command, so it is punctuation here, not whitespace.
    lexer = shlex.shlex(command, posix=True, punctuation_chars="".join(SEPARATOR_CHARS))
    lexer.whitespace = " \t\r"
    lexer.whitespace_split = True
    lexer.commenters = ""
    segment = []
    for token in lexer:
        if set(token) <= SEPARATOR_CHARS:
            if segment:
                yield segment
            segment = []
        else:
            segment.append(token)
    if segment:
        yield segment


def head(words):
    """The word that is run, past assignments and wrappers."""
    i = 0
    while i < len(words):
        w = words[i]
        if re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", w):
            i += 1
        elif w in WRAPPERS:
            i += 1
            while i < len(words) and words[i].startswith("-"):
                i += 1
        elif w == "timeout":
            i += 1
            while i < len(words) and words[i].startswith("-"):
                i += 1
            i += 1  # the duration
        else:
            return i
    return i


def refusal(command, depth=0):
    """Why this command needs the lease, or None."""
    if depth > 3:
        return None
    for words in segments(command):
        i = head(words)
        if i >= len(words):
            continue
        run, args = os.path.basename(words[i]), words[i + 1:]
        if run in SHELLS and "-c" in args:
            inner = args[args.index("-c") + 1:]
            if inner:
                found = refusal(inner[0], depth + 1)
                if found:
                    return found
            continue
        if run == "adb":
            j = 0
            while j < len(args) and args[j].startswith("-"):
                j += 2 if args[j] in ADB_VALUED else 1
            if j < len(args) and args[j] not in ADB_READ_ONLY:
                return f"`adb {args[j]}`"
        if run in ("gradlew", "gradle"):
            for a in args:
                if not a.startswith("-") and DEVICE_TASK.match(a.rsplit(":", 1)[-1]):
                    return f"the Gradle task `{a}`"
    return None


def main():
    event = json.load(sys.stdin)
    if event.get("tool_name") != "Bash":
        return 0
    command = event.get("tool_input", {}).get("command", "")
    try:
        found = refusal(command)
    except ValueError:
        return 0
    if not found:
        return 0
    print(
        f"{found} uses the shared emulator. Run it under the lease instead:\n"
        f"  .claude/bin/emu [--ci-size] -- <command>\n"
        f"Other sessions' tests run on the same device; `.claude/bin/emu --status` "
        f"shows who holds it. A suite can outlast the Bash timeout, so run long "
        f"ones with run_in_background.",
        file=sys.stderr,
    )
    return 2


if __name__ == "__main__":
    sys.exit(main())
