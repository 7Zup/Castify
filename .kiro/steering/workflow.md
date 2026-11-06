# Workflow: how every feature is built

The user vibe codes: they never edit code. The agent writes, builds, tests and
documents everything. These steps are mandatory for any new feature (anything beyond
a bug fix or a one-line tweak).

## 1. Ask, don't imagine (blocking)

For every feature, **ask the user** about each product choice that is not explicit in
their request (what is shown, where, labels, behavior, edge cases) before writing the
artifact. Do not fill the gaps with your own ideas: offer options if useful, but let the
user pick. Keep each round short (the few questions that block the artifact). Technical
choices with no visible effect stay the agent's call.

## 2. Understanding artifact (blocking)

Once the answers are in, produce a `create_artifact` (markdown) that
states what you understood:

- **Request**: the feature in one sentence, in the user's terms.
- **Must have (this iteration)** vs **Nice to have (later)**.
- **User-visible behavior**: what the user does, what they see, edge/error cases.
- **Technical approach**: layers touched (domain / data / presentation / receiver),
  new entities or use cases, external APIs or SDKs involved.
- **Risks & unknowns**: what could fail, what needs a spike.
- **Docs impact**: which files under `docs/` will be created or updated.
- **How we'll verify it**: build, tests, and the manual check the user will do on
  the phone / TV.

Then **stop and wait for explicit validation**. Do not start coding in the same turn.
If the user changes the scope, update the artifact and re-validate.

## 3. Implement

- Follow `architecture.md` (clean architecture is non-negotiable).
- Keep scope to the validated Must have. If something new comes up mid-way, say so
  and offer to park it in `docs/ROADMAP.md` instead of building it silently.

## 4. Verify

- Build and run the tests before claiming anything works.
- List the exact manual check the user must do (device, steps, expected result).
  The agent cannot see the TV: ask the user for the outcome when it matters.

## 5. Document (same turn as the code)

Follow `documentation.md`. A feature is not done until `docs/STATUS.md` reflects it.

## 6. Commit

Only when the user asks. Small commits, English messages, one feature per branch.
