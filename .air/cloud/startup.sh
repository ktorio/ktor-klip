#!/usr/bin/env bash
#
# Air environment startup script for ktor-klip.
#
# ktor-klip (KLIP - Ktor Library Improvement Proposals) is a documentation
# repository: design proposals written in Markdown, plus illustrative code
# appendices. There is no build system, no dependency manifest and no test
# suite, so there is nothing to compile or install here. What a real task
# needs is a complete, consistent checkout and the tools used to author and
# inspect Markdown (git, python3) — that is exactly what `healthcheck` asserts.

set -euo pipefail

log() { printf '[startup] %s\n' "$*"; }

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# The launch states the mode: `warmup` bakes the snapshot (env-setup
# verification runs in this mode), `task` is a real task run.
if [ "${AIR_STARTUP_MODE:-}" = warmup ]; then WARMUP=1; else WARMUP=; fi

# Assert the environment works the way a KLIP task needs it to. Owns all
# readiness waiting: it keeps polling for the checkout with no deadline of its
# own (the launch applies its own timeout). Returns non-zero on any failure so
# startup fails with it.
healthcheck() {
  local waited=0

  # 1. Wait for the workspace checkout to be in place.
  until [ -d "$REPO_DIR/proposals" ] &&
    [ -f "$REPO_DIR/0000-template.md" ] &&
    git -C "$REPO_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; do
    log "healthcheck: waiting for the repository checkout at $REPO_DIR (${waited}s elapsed)"
    sleep 3
    waited=$((waited + 3))
  done
  log "healthcheck: checkout present at $REPO_DIR after ${waited}s"

  # 2. The authoring tools a task relies on must be usable.
  local tool
  for tool in git python3; do
    if ! command -v "$tool" >/dev/null 2>&1; then
      log "healthcheck FAILED: required tool '$tool' is not on PATH"
      return 1
    fi
  done
  log "healthcheck: git $(git --version | awk '{print $3}'), $(python3 --version)"

  # 3. Git must be operational in the workspace (proposals are reviewed and
  #    submitted as pull requests, so a task always touches git).
  local head tracked
  if ! head="$(git -C "$REPO_DIR" rev-parse --short HEAD 2>&1)"; then
    log "healthcheck FAILED: cannot resolve HEAD: $head"
    return 1
  fi
  if ! git -C "$REPO_DIR" status --porcelain >/dev/null 2>&1; then
    log "healthcheck FAILED: 'git status' does not work in $REPO_DIR"
    return 1
  fi
  tracked="$(git -C "$REPO_DIR" ls-files | wc -l | tr -d ' ')"
  if [ "$tracked" -lt 1 ]; then
    log "healthcheck FAILED: no tracked files in $REPO_DIR"
    return 1
  fi
  log "healthcheck: git ok — HEAD $head on $(git -C "$REPO_DIR" rev-parse --abbrev-ref HEAD), $tracked tracked files"

  # 4. Every tracked blob must actually be present locally, otherwise the
  #    clone is incomplete and reads will fail mid-task.
  local missing
  missing="$(git -C "$REPO_DIR" ls-files -s | awk '{print $2}' |
    git -C "$REPO_DIR" cat-file --batch-check 2>&1 | grep -c missing || true)"
  if [ "$missing" -ne 0 ]; then
    log "healthcheck FAILED: $missing tracked git object(s) missing from the local clone"
    return 1
  fi
  log "healthcheck: all $tracked tracked git objects present locally"

  # 5. The KLIP structure a task reads and writes must be there, and the
  #    Markdown must be parseable with its relative links resolving — this
  #    reads every proposal off disk, so it also proves the content (including
  #    the appendices proposals link to) survived the clone intact.
  if ! python3 - "$REPO_DIR" <<'PY'; then
import collections
import os
import re
import subprocess
import sys
import urllib.parse

root = sys.argv[1]


def fail(msg):
    print(f"[startup] healthcheck FAILED: {msg}")
    sys.exit(1)


for required in ("README.md", "0000-template.md", "proposals"):
    if not os.path.exists(os.path.join(root, required)):
        fail(f"expected {required} in the repository root")

md_files = subprocess.run(
    ["git", "-C", root, "ls-files", "*.md"],
    capture_output=True, text=True, check=True,
).stdout.split()
proposals = [f for f in md_files if f.startswith("proposals/")]
if not proposals:
    fail("no proposal documents found under proposals/")

# The proposal template drives every new KLIP; check it still has its shape.
template = open(os.path.join(root, "0000-template.md"), encoding="utf-8").read()
for marker in ("| Feature", "# Summary"):
    if marker not in template:
        fail(f"0000-template.md is missing its {marker!r} section")

link_re = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")
heading_re = re.compile(r"^#{1,6}\s+(.*)$", re.M)
named_anchor_re = re.compile(r"<a\s+(?:name|id)=\"([^\"]+)\"")


def slug(heading):
    s = re.sub(r"<[^>]+>", "", heading).strip().lower()
    return re.sub(r"[^a-z0-9 _-]", "", s).replace(" ", "-")


broken_files, broken_anchors = [], []
for rel in md_files:
    path = os.path.join(root, rel)
    try:
        text = open(path, encoding="utf-8").read()
    except OSError as exc:
        fail(f"cannot read {rel}: {exc}")

    anchors, seen = set(), collections.Counter()
    for match in heading_re.finditer(text):
        base = slug(match.group(1))
        n = seen[base]
        seen[base] += 1
        anchors.add(base if n == 0 else f"{base}-{n}")
    anchors |= set(named_anchor_re.findall(text))

    for match in link_re.finditer(text):
        target = match.group(1)
        if target.startswith(("http://", "https://", "mailto:")):
            continue
        if target.startswith("#"):
            if target[1:] not in anchors:
                broken_anchors.append(f"{rel} -> {target}")
            continue
        local = urllib.parse.unquote(target.split("#", 1)[0])
        if local and not os.path.exists(os.path.join(os.path.dirname(path), local)):
            broken_files.append(f"{rel} -> {target}")

if broken_files:
    fail(
        f"{len(broken_files)} relative link(s) point at files missing from the "
        f"checkout: " + "; ".join(broken_files[:10])
    )

print(
    f"[startup] healthcheck: parsed {len(md_files)} Markdown file(s), "
    f"{len(proposals)} proposal(s); all relative file links resolve"
)
# Unresolved in-document anchors are a content-quality signal, not a broken
# environment (a proposal being drafted may legitimately have them), so report
# without failing.
if broken_anchors:
    print(
        f"[startup] healthcheck: note — {len(broken_anchors)} in-document "
        f"anchor(s) do not match a heading: " + "; ".join(broken_anchors[:10])
    )
PY
    return 1
  fi

  log "healthcheck: PASSED — environment is ready for KLIP authoring and review"
}

log "starting (mode=${AIR_STARTUP_MODE:-unset}, repo=$REPO_DIR)"

# ktor-klip carries no dependency manifest, lockfile or build script, so there
# is nothing to fetch, install or compile — anything else here would install
# tooling the repository does not define. git and python3 ship with the image;
# `healthcheck` verifies them rather than assuming them.
log "no dependency install or build step: ktor-klip is a Markdown-only proposals repository"

# No server or other runtime process is needed: there is no application to run.
if [ -n "${WARMUP:-}" ]; then
  log "warmup run: verifying the environment before the snapshot is taken"
  healthcheck || exit 1
else
  log "task run: nothing to start, environment is ready"
fi

log "done"
