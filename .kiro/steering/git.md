# Git & GitHub workflow

- Remote: `git@github.com:7Zup/Castify.git` (public, see `privacy.md`). GitHub CLI `gh` is logged in.
- `main` is protected by convention: the agent never commits feature code on it.
- **One branch per feature**: `feat/<short-name>` (e.g. `feat/v0-spike`), fixes on
  `fix/<short-name>`.
- **Avoid stacked PRs.** Branch from `origin/main` once the previous PR is merged. If a
  stack is unavoidable, merge it as **one PR from the top branch into `main`**: merging the
  bottom PR with `--delete-branch` closes the PRs above it instead of retargeting them
  (happened on 11/05/2026: #2–#4 were replaced by #5).
- In shell chains, never put `grep` (exit 1 when nothing matches) before `git commit` with
  `&&`: it silently skips the commit.
- **One pull request per feature**, opened by the agent with `gh pr create`:
  title < 70 chars, body = summary, what was tested (automated + what the user must
  check on phone/TV), known limits. Link the issue it closes (`Closes #N`).
- **The user tests on the devices and merges**, or explicitly asks the agent to merge
  (merge commits, `gh pr merge --merge`). The agent never force-pushes, never rewrites `main`.
- Bugs found by the user live in GitHub Issues. No GitHub Projects board: the plan
  is `docs/ROADMAP.md` + `docs/STATUS.md`.
- Commit messages in English, imperative mood, small focused commits.
- Before starting a new feature: `git fetch`, branch from an up-to-date `origin/main`.
- **Releases** (`docs/releasing.md`): after each merge, tell the user how many merged
  changes are unreleased and recommend publishing once one is user-visible (feature or bug
  fix). Publish only when the user asks, with `scripts/release.fish <version>` (its version
  bump PR is merged by the script; that is covered by the user's request).
- The PR numbers in docs written before 11/06/2026 refer to the old private repo (history
  restarted when the repo went public).
