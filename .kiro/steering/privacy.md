# Privacy: the repo is public

The GitHub repo is public. Nothing personal goes into a tracked file: code, tests, docs,
mockups, steering, commit messages, PR titles and bodies, issues.

## Never in the repo

- IP addresses of the user's network, device serials, device or TV names.
- Real names of the websites the user watches (and their URLs).
- Names, emails, accounts, tokens, keys, keystores, passwords.

## Where it goes instead

- Personal facts the agent needs: `.kiro/steering/local.md` (gitignored, always loaded).
  Add new facts there.
- In tracked files use placeholders: sites `streamsite.example`, `videosite.example`
  (any `*.example` domain), TV `MyCast`, LAN IPs `10.0.0.x`, `<tv-ip>` in commands.
- Signing keys and their passwords: outside the repo (or a gitignored file), never committed.

## Before every commit

Check the staged diff for anything above (`git diff --cached`). If something personal was
already pushed, tell the user: removing it from history needs a rewrite and their approval.
