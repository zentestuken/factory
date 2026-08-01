#!/usr/bin/env python3
"""
Delete all TestRail cases tagged with the dedicated label.

Required env vars:
  FACTORY_CONNECTORS_TESTRAIL_BASE_URL    e.g. https://yourorg.testrail.io
  FACTORY_CONNECTORS_TESTRAIL_USERNAME    e.g. user@example.com
  FACTORY_CONNECTORS_TESTRAIL_API_KEY     API key from TestRail account settings
  FACTORY_CONNECTORS_TESTRAIL_PROJECT_ID  numeric project id
  FACTORY_CONNECTORS_TESTRAIL_SUITE_ID    (optional) required for multi-suite projects
  FACTORY_CYPRESS_TESTRAIL_SECTION_ID     (optional) restrict search to one section; omit to scan whole project
  FACTORY_CYPRESS_TESTRAIL_LABEL          (optional) label to filter by; default "cypressFlow-delete"

Usage:
  python scripts/cleanup-cypress-testrail-cases.py [--dry-run]
"""

import os
import sys
import argparse
import base64
import urllib.request
import urllib.error
import json

BASE_URL = os.environ["FACTORY_CONNECTORS_TESTRAIL_BASE_URL"].rstrip("/")
USERNAME = os.environ["FACTORY_CONNECTORS_TESTRAIL_USERNAME"]
API_KEY = os.environ["FACTORY_CONNECTORS_TESTRAIL_API_KEY"]
PROJECT_ID = int(os.environ["FACTORY_CONNECTORS_TESTRAIL_PROJECT_ID"])
SUITE_ID = os.environ.get("FACTORY_CONNECTORS_TESTRAIL_SUITE_ID")
LABEL_TITLE = os.environ.get("FACTORY_CYPRESS_TESTRAIL_LABEL") or "cypressFlow-delete"

_auth = base64.b64encode(f"{USERNAME}:{API_KEY}".encode()).decode()
_headers = {"Authorization": f"Basic {_auth}", "Content-Type": "application/json"}


def api(method: str, path: str, body=None):
    url = f"{BASE_URL}/index.php?/api/v2/{path}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, headers=_headers, method=method)
    try:
        with urllib.request.urlopen(req) as resp:
            body = resp.read()
            return json.loads(body) if body.strip() else {}
    except urllib.error.HTTPError as e:
        print(f"HTTP {e.code} {method} {path}: {e.read().decode()}", file=sys.stderr)
        raise


def find_label_id() -> int:
    resp = api("GET", f"get_labels/{PROJECT_ID}")
    for label in resp.get("labels", []):
        if label.get("name") == LABEL_TITLE:
            return label["id"]
    raise SystemExit(f"Label '{LABEL_TITLE}' not found in project {PROJECT_ID}")


def get_cases(label_id: int) -> list:
    results = []
    offset = 0
    limit = 250
    base = f"get_cases/{PROJECT_ID}&limit={limit}"
    if SUITE_ID:
        base += f"&suite_id={SUITE_ID}"
    base += f"&label_id={label_id}"
    while True:
        resp = api("GET", f"{base}&offset={offset}")
        batch = resp.get("cases", [])
        results.extend(batch)
        if len(batch) < limit:
            break
        offset += limit
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true",
                        help="Print cases that would be deleted without actually deleting them")
    args = parser.parse_args()

    print(f"Looking up label '{LABEL_TITLE}'...")
    label_id = find_label_id()
    print(f"Found label id={label_id}")

    print("Fetching cases...")
    cases = get_cases(label_id)
    print(f"Found {len(cases)} case(s) with label '{LABEL_TITLE}'")

    if not cases:
        print("Nothing to delete.")
        return

    for case in cases:
        cid = case["id"]
        title = case.get("title", "")
        if args.dry_run:
            print(f"  [dry-run] would delete C{cid}: {title}")
        else:
            api("POST", f"delete_case/{cid}")
            print(f"  Deleted C{cid}: {title}")

    if args.dry_run:
        print(f"\nDry run complete — {len(cases)} case(s) would be deleted.")
    else:
        print(f"\nDeleted {len(cases)} case(s).")


if __name__ == "__main__":
    main()
