#!/usr/bin/env bash
# Refresh the bundled last-resort copies of the write-testrail-cases skill used by the Cypress
# Factory flow (see factory-flow-cypress/CYPRESS_FLOW_PLAN.md Part I §4): SKILL.md, the rule files
# the generation prompt pastes in (SkillWorkspaceService.RULE_FILES), the cross-area examples and
# the per-area golden cases. Paths are preserved under references/, matching the classpath lookup.
#
# These live in the jar and are the final tier of the clone -> GitHub -> classpath
# chain, so they must be refreshed deliberately. Do NOT wire this into the Maven
# build: the bundle would silently become whatever the last person to build had on disk.
#
# Usage: scripts/update-cypress-testcase-skill-bundle.sh [path-to-folio-eureka-ai-dev]
set -euo pipefail

SRC="${1:-$HOME/folio-eureka-ai-dev}/skills/write-testrail-cases"
DST="factory-flow-cypress/src/main/resources/references"

if [ ! -d "$SRC" ]; then
  echo "error: skill dir not found: $SRC" >&2
  echo "usage: $0 [path-to-folio-eureka-ai-dev]" >&2
  exit 1
fi

mkdir -p "$DST/examples"
files="SKILL.md references/examples.md references/writing-rules.md references/step-patterns.md references/house-style.md"
for f in "$SRC"/references/examples/*.md; do
  [ -e "$f" ] && files="$files references/examples/$(basename "$f")"
done
for f in $files; do
  out="$DST/${f#references/}"
  old=$( [ -f "$out" ] && wc -c < "$out" || echo 0 )
  cp "$SRC/$f" "$out"
  printf '%-40s %8s -> %8s bytes\n' "$f" "$old" "$(wc -c < "$out")"
done

# The FACTORY DELTAS block in prompts/cypress-testcase-gen/system.md overrides parts of
# SKILL.md. It describes them by behaviour rather than step number, but if MQA removes a
# part entirely the delta silently talks about nothing. Same anchors as
# SkillWorkspaceService.DELTA_ANCHORS - keep the two in step.
echo
echo "Checking that each factory delta still anchors to something in SKILL.md:"
drift=0
check_anchor() {   # $1 = delta label, $2 = phrase that must exist
  if grep -qF "$2" "$DST/SKILL.md"; then
    printf '  ok      %s\n' "$1"
  else
    printf '  MISSING %s  (looked for: "%s")\n' "$1" "$2"
    drift=1
  fi
}
check_anchor "delta 1 - gathering already performed" "read the matching context file"
check_anchor "delta 2 - never pause for confirmation" "wait for confirmation"
check_anchor "delta 3 - never touch TestRail"        "Ask for the TestRail Section ID"

if [ "$drift" -ne 0 ]; then
  echo
  echo "warning: review the FACTORY DELTAS block in" >&2
  echo "         factory-flow-cypress/src/main/resources/prompts/cypress-testcase-gen/system.md" >&2
  echo "         and the DELTA_ANCHORS map in SkillWorkspaceService.java." >&2
fi
