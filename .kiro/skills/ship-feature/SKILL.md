---
name: ship-feature
description: Use when a Castify feature's code is written, before telling the user it is done. Checklist for build, tests, docs and status.
---

# Ship a feature

1. `./gradlew :domain:test assembleDebug`: both must pass. Fix before going on.
2. Check the clean architecture rules (`.kiro/steering/architecture.md`): no Android
   or SDK import in `:domain`, the UI only calls use cases.
3. Create or update `docs/features/<feature>.md`: behavior, design, limits, how to test.
4. Update `docs/ARCHITECTURE.md` if a module, port or flow changed.
5. Write an ADR in `docs/decisions/` for any non-trivial technical choice.
6. Update `docs/ROADMAP.md` (done items, parked Nice to have).
7. Update `docs/STATUS.md`: done, next step, known issues, date (MM/DD/YYYY).
8. Tell the user (in French) the outcome and the exact manual check to do on
   phone/TV. Offer to commit; never commit without being asked.
