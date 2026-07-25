#!/usr/bin/env python3
"""
Generates the PROJECT_KEY_AREAS map entries for ContextLoaderWorker.java.

Reads Jira projects from projects.json (must be in the same folder as this script).
Fetches the list of available area context files from the folio-ai GitHub repo.

Usage:
    python gen_project_area_map.py
    python gen_project_area_map.py YOUR_GITHUB_TOKEN

A GitHub token is optional but avoids the unauthenticated rate limit (60 req/hr).
You can also set it as an env var instead of passing it on the command line:
    GITHUB_TOKEN=ghp_xxx python gen_project_area_map.py
"""

import json
import os
import sys
import urllib.request
import urllib.error
from collections import defaultdict

FOLIO_AI_REPO = "folio-org/folio-eureka-ai-dev"
CONTEXT_DIR = "skills/write-testrail-cases/references/context"
BRANCH = "master"
GITHUB_API_URL = f"https://api.github.com/repos/{FOLIO_AI_REPO}/contents/{CONTEXT_DIR}?ref={BRANCH}"

# ---------------------------------------------------------------------------
# Heuristics: (list-of-substrings-in-project-name, area)
# Evaluated top to bottom — first match wins.
# Specific patterns MUST come before generic ones (e.g. "marc-authorities"
# before plain "marc") to avoid false positives.
# ---------------------------------------------------------------------------
HEURISTICS = [
    (["requests-mediated", "mediated-request"],    "mediated-requests"),
    (["marc-authorities", "marc-authority"],        "marc-authority"),
    (["marc-migrations", "marc-storage",
      "marc-components", "marc-utils",
      "marccat", "marc4ld"],                        "marc-authority"),
    (["quick-marc", "quickmarc"],                   "marc-bib-quickmarc"),
    (["bulk-edit", "bulkedit", "bulk-operations"],  "bulk-edit"),
    (["data-import"],                               "data-import"),
    (["data-export"],                               "data-export"),
    (["eholdings", "kb-ebsco"],                     "eholdings"),
    (["consortia", "consortium"],                   "consortium-manager"),
    (["circulation-log", "circ-log"],               "circulation-log"),
    (["patron-notice"],                             "patron-notices"),
    (["feesfines", "fees-fines", "fee-fine"],       "fees-fines"),
    (["oai-pmh"],                                   "oai-pmh"),
    (["courses", "course-reserve"],                 "course-reserves"),
    (["calendar"],                                  "circulation-settings"),
    (["checkout", "check-out"],                     "check-out"),
    (["checkin", "check-in"],                       "check-in"),
    (["circulation"],                               "circulation-settings"),
    (["authorization-roles"],                       "users"),
    (["invoic"],                                    "invoices"),
    (["finance", "fiscal"],                         "finance"),
    (["receiving"],                                 "receiving"),
    (["requests"],                                  "requests"),
    (["orders"],                                    "orders"),
    (["inventory"],                                 "inventory"),
    (["lists"],                                     "lists"),
    (["organizations", "organization"],             "organizations"),
    (["patron"],                                    "users"),
    (["users", "user-"],                            "users"),
    (["marc"],                                      "marc-authority"),  # generic fallback
]

FALLBACK_AREAS = [
    "inventory", "orders", "invoices", "requests", "mediated-requests",
    "check-out", "check-in", "finance", "marc-authority", "marc-bib-quickmarc",
    "bulk-edit", "eholdings", "consortium-manager", "receiving",
    "data-import", "data-export", "lists", "organizations", "patron-notices",
    "users", "fees-fines", "circulation-settings", "course-reserves",
    "oai-pmh", "circulation-log",
]

# Maximum number of areas to assign to a single project key.
MAX_AREAS = 3


def fetch_areas(token):
    req = urllib.request.Request(GITHUB_API_URL)
    req.add_header("Accept", "application/vnd.github.v3+json")
    req.add_header("User-Agent", "gen-project-area-map")
    if token:
        req.add_header("Authorization", "token " + token)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            files = json.loads(resp.read())
            return [f["name"][:-3] for f in files if f["name"].endswith(".md")]
    except urllib.error.HTTPError as e:
        print("Warning: GitHub API returned {}. Using fallback area list.".format(e.code), file=sys.stderr)
        return []
    except Exception as e:
        print("Warning: Could not reach GitHub ({}). Using fallback area list.".format(e), file=sys.stderr)
        return []


def match_areas(project_name, available_areas):
    """Return up to MAX_AREAS distinct areas that match the project name."""
    name = project_name.lower()
    matched = []
    for keywords, area in HEURISTICS:
        if len(matched) >= MAX_AREAS:
            break
        if area in available_areas and any(k in name for k in keywords):
            if area not in matched:
                matched.append(area)
    return matched


def main():
    token = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("GITHUB_TOKEN")

    script_dir = os.path.dirname(os.path.abspath(__file__))
    projects_path = os.path.join(script_dir, "projects.json")

    if not os.path.exists(projects_path):
        print("Error: projects.json not found at", projects_path, file=sys.stderr)
        print("Copy the Jira projects export next to this script and re-run.", file=sys.stderr)
        sys.exit(1)

    with open(projects_path, encoding="utf-8") as f:
        projects = json.load(f)
    print("Loaded {} projects from projects.json.".format(len(projects)))

    print("Fetching context files from {} ...".format(FOLIO_AI_REPO))
    areas = fetch_areas(token)
    if areas:
        print("Found {} context files: {}\n".format(len(areas), ", ".join(sorted(areas))))
    else:
        areas = FALLBACK_AREAS
        print("Using fallback list of {} areas.\n".format(len(areas)))

    by_key = {}
    skipped = []

    for project in projects:
        key = project.get("key", "")
        name = project.get("name", "")
        matched = match_areas(name, areas)
        if matched:
            by_key[key] = matched
        else:
            skipped.append((key, name))

    entries = sorted(by_key.items())

    # -----------------------------------------------------------------------
    # Output
    # -----------------------------------------------------------------------
    print("=" * 72)
    print("WHERE TO PASTE")
    print("=" * 72)
    print("""
File:
  factory-flow-cypress/src/main/java/org/folio/factory/cypressfactory/
  worker/ContextLoaderWorker.java

Steps:
  1. Open the file in your editor.

  2. Find this line (around line 34):

         private static final Map<String, List<String>> PROJECT_KEY_AREAS = Map.ofEntries(

  3. Select and DELETE everything between the opening '(' on that line
     and the closing ');'  — that is, all the Map.entry(...) lines inside.
     Leave the 'Map.ofEntries(' and the final ');' exactly as they are.

  4. Paste the block from the "ENTRIES" section below in their place.

  5. Save the file.

The result should look like this (abbreviated):

    private static final Map<String, List<String>> PROJECT_KEY_AREAS = Map.ofEntries(
            Map.entry("AE", List.of("eholdings")),
            Map.entry("CIRC", List.of("check-out", "check-in", "circulation-settings")),
            ...
            Map.entry("UIU", List.of("users"))   <- NO comma on the very last line
    );

Note: Java does not allow a trailing comma after the last Map.entry — the
script already omits it. The map type is Map<String, List<String>> — make
sure the field declaration matches (the script regenerates entries only,
not the field declaration line itself).
""")

    print("=" * 72)
    print("ENTRIES  ({} project keys, {} with multiple areas)".format(
        len(entries), sum(1 for _, a in entries if len(a) > 1)))
    print("=" * 72)
    print()
    for i, (key, area_list) in enumerate(entries):
        comma = "," if i < len(entries) - 1 else ""
        areas_str = ", ".join('"{}"'.format(a) for a in area_list)
        print('        Map.entry("{}", List.of({})){}'.format(key, areas_str, comma))
    print()

    print("=" * 72)
    relevant_skipped = [
        (k, n) for k, n in skipped
        if any(n.lower().startswith(p) for p in ("ui-", "mod-", "app-", "edge-", "stripes-"))
        and "archived" not in n.lower()
    ]
    print("SKIPPED  ({} projects had no matching context file)".format(len(skipped)))
    print("=" * 72)
    print("These fall through to keyword matching or 'generic' at runtime.")
    print("If you've just added a new context file, scan this list for its projects:\n")
    for k, n in sorted(relevant_skipped):
        print("  {:<20} {}".format(k, n))
    if len(skipped) > len(relevant_skipped):
        print("\n  ({} non-module projects omitted from this list)".format(
            len(skipped) - len(relevant_skipped)))


if __name__ == "__main__":
    main()
