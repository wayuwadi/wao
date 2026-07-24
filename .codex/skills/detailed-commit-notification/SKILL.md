---
name: detailed-commit-notification
description: Draft detailed Conventional Commit messages and ensure GitHub Actions Feishu notifications include the change summary, rationale, and validation steps. Use before committing, pushing, preparing a pull request, or modifying WAO CI notification workflows.
---

# Detailed Commit Notification

Use a Conventional Commit subject followed by a body with exactly these labelled sections:

```text
feat(scope): concise outcome

Changes:
- <observable change>

Why:
- <business or technical reason>

Validation:
- <command or manual acceptance step and result>
```

Write concrete facts. Name affected API paths, screens, migrations, configuration, or deployment behavior. Do not put secrets, webhook URLs, credentials, or speculative claims in the message.

Before a push, inspect the staged diff and run relevant checks. If a check was not run, state `Not run: <reason>` under Validation; never imply that it passed.

Keep the first line at 72 characters or fewer. Preserve the body because the WAO CI workflow reads both subject and body and posts them to Feishu. For merge commits or pushes containing multiple commits, summarize the head commit and provide the commit/run URL so recipients can inspect the complete range.
