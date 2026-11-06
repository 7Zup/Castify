# Documentation: the memory between sessions

Each Kiro session starts with no memory. The repo docs must be enough for a fresh
session to continue the work without asking the user to re-explain anything.

## Start of a session

Read `docs/STATUS.md` first, then the docs relevant to the task.

## Layout

| File | Purpose | Update when |
| --- | --- | --- |
| `docs/STATUS.md` | Where we are: done, in progress, next step, known issues | End of every session that changed something |
| `docs/ROADMAP.md` | Versions (v0, v1, v2…) and the Nice-to-have backlog | Scope decided or parked |
| `docs/ARCHITECTURE.md` | Modules, layers, data flow, diagrams (mermaid) | A layer, module or flow changes |
| `docs/features/<feature>.md` | One per feature: behavior, design, limits, how to test | The feature is built or changed |
| `docs/decisions/NNNN-<title>.md` | ADRs: context, options, decision, consequences | A non-trivial technical choice is made |
| `README.md` | What the project is, how to build/install/run | Setup or commands change |

## Rules

- Update the docs **in the same turn** as the code change, not "later".
- Describe the current state, not the history (history is git + ADRs).
- Keep it short and factual. No filler sections.
- All repo docs are in **English** (US spelling). Chat with the user is in French.
- If code and docs disagree, the code is the truth: fix the doc immediately.
