"""PR Describe Bot.

Runs when the `describe` label is added to a PR. Fetches the PR diff and
commit messages, asks Claude for a concise statement of the PR's objective,
and posts it as a comment the author can copy into the PR description.

Edge-case handling:
- Re-label (remove + re-add `describe`): the bot finds its previous comment
  via a hidden HTML marker and edits it in place, so there is only ever one
  suggestion comment per PR, always reflecting the latest run.
- Oversized diffs: the diff is truncated to a character budget; if truncated,
  a per-file change summary (paths + additions/deletions) is appended so the
  model still sees the full shape of the change.
- API failure: the script posts a short failure note into the same comment
  slot rather than dying silently, so a missing suggestion is visible.

Required env vars (provided by the workflow):
  GITHUB_TOKEN, ANTHROPIC_API_KEY, REPO, PR_NUMBER, PR_TITLE, BRANCH
"""

import os
import sys

import requests

GITHUB_API = "https://api.github.com"
ANTHROPIC_API = "https://api.anthropic.com/v1/messages"
MARKER = "<!-- describe-bot -->"          # hidden marker to find our comment
DIFF_CHAR_BUDGET = 120_000                # ~30k tokens; well under limits
MODEL = "claude-sonnet-4-6"

REPO = os.environ["REPO"]
PR_NUMBER = os.environ["PR_NUMBER"]
PR_TITLE = os.environ.get("PR_TITLE", "")
BRANCH = os.environ.get("BRANCH", "")

gh = requests.Session()
gh.headers.update(
    {
        "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
        "X-GitHub-Api-Version": "2022-11-28",
    }
)


def fetch_diff() -> str:
    r = gh.get(
        f"{GITHUB_API}/repos/{REPO}/pulls/{PR_NUMBER}",
        headers={"Accept": "application/vnd.github.diff"},
        timeout=30,
    )
    r.raise_for_status()
    return r.text


def fetch_file_summary() -> str:
    """Per-file additions/deletions — cheap fallback context for huge diffs."""
    files, page = [], 1
    while True:
        r = gh.get(
            f"{GITHUB_API}/repos/{REPO}/pulls/{PR_NUMBER}/files",
            params={"per_page": 100, "page": page},
            timeout=30,
        )
        r.raise_for_status()
        batch = r.json()
        if not batch:
            break
        files.extend(batch)
        page += 1
    lines = [
        f"- {f['filename']} ({f['status']}, +{f['additions']}/-{f['deletions']})"
        for f in files
    ]
    return "\n".join(lines)


def fetch_commit_messages() -> str:
    r = gh.get(
        f"{GITHUB_API}/repos/{REPO}/pulls/{PR_NUMBER}/commits",
        params={"per_page": 100},
        timeout=30,
    )
    r.raise_for_status()
    return "\n".join(f"- {c['commit']['message'].splitlines()[0]}" for c in r.json())


def summarize(diff: str, commits: str) -> str:
    truncated = len(diff) > DIFF_CHAR_BUDGET
    if truncated:
        diff = diff[:DIFF_CHAR_BUDGET]

    context = f"""PR title: {PR_TITLE}
Branch: {BRANCH}

Commit messages:
{commits or "(none)"}
"""
    if truncated:
        context += f"""
NOTE: The diff below was truncated. Full per-file change summary:
{fetch_file_summary()}
"""

    prompt = f"""You are writing a pull-request description for the author to \
review and paste into the PR. Based on the context and diff, write:

1. A one-line **Objective** — what this PR accomplishes and why.
2. A short **Changes** section — 2-6 bullets covering the substantive changes \
(skip mechanical noise like import reordering).
3. If the diff shows anything clearly incomplete, stubbed, or deferred, a \
**Deferred** line naming it. Omit this section entirely if nothing qualifies.

Be concrete and specific to this diff. No preamble, no sign-off, output the \
description only, in GitHub-flavored Markdown.

{context}
Diff:
```diff
{diff}
```"""

    r = requests.post(
        ANTHROPIC_API,
        headers={
            "x-api-key": os.environ["ANTHROPIC_API_KEY"],
            "anthropic-version": "2023-06-01",
            "content-type": "application/json",
        },
        json={
            "model": MODEL,
            "max_tokens": 1024,
            "messages": [{"role": "user", "content": prompt}],
        },
        timeout=120,
    )
    r.raise_for_status()
    return "".join(
        block["text"] for block in r.json()["content"] if block["type"] == "text"
    ).strip()


def upsert_comment(body: str) -> None:
    """Edit our existing comment if present (re-label case), else create one."""
    existing_id = None
    page = 1
    while existing_id is None:
        r = gh.get(
            f"{GITHUB_API}/repos/{REPO}/issues/{PR_NUMBER}/comments",
            params={"per_page": 100, "page": page},
            timeout=30,
        )
        r.raise_for_status()
        batch = r.json()
        if not batch:
            break
        for c in batch:
            if MARKER in (c.get("body") or ""):
                existing_id = c["id"]
                break
        page += 1

    if existing_id:
        r = gh.patch(
            f"{GITHUB_API}/repos/{REPO}/issues/comments/{existing_id}",
            json={"body": body},
            timeout=30,
        )
    else:
        r = gh.post(
            f"{GITHUB_API}/repos/{REPO}/issues/{PR_NUMBER}/comments",
            json={"body": body},
            timeout=30,
        )
    r.raise_for_status()


def main() -> int:
    try:
        diff = fetch_diff()
        commits = fetch_commit_messages()
        suggestion = summarize(diff, commits)
        body = f"""{MARKER}
## Suggested PR description

{suggestion}

---
*Copy the section above into the PR description if it looks right. \
Re-add the `describe` label after new pushes to refresh this suggestion.*"""
    except Exception as exc:  # visible failure beats a silent one
        body = f"""{MARKER}
## Suggested PR description

The describe bot failed on this run: `{exc}`

Check the Actions log for details, then re-add the `describe` label to retry."""
        upsert_comment(body)
        print(f"describe bot failed: {exc}", file=sys.stderr)
        return 1

    upsert_comment(body)
    print("Suggestion comment posted/updated.")
    return 0


if __name__ == "__main__":
    sys.exit(main())