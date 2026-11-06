---
name: feature-kickoff
description: Use before starting any new Castify feature or version (v0, v1…). Produces the understanding artifact the user must validate before any code is written.
---

# Feature kickoff

1. Read `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/ARCHITECTURE.md` and any related
   `docs/features/*.md`.
2. Ask the user (in French) about every product choice the request leaves open: what
   is shown, where, labels, behavior, edge cases. Never invent them; propose options
   if it helps. Short rounds. Write the artifact only once the answers are in.
3. Create a markdown artifact (`create_artifact`, in French since it is for the user)
   with exactly these sections:
   - **Demande** – one sentence.
   - **Must have / Nice to have** – a two-column table.
   - **Ce que tu verras** – user steps and screens, including empty, error and
     "nothing detected" states.
   - **Approche technique** – per layer (domain / data / app / receiver): new
     entities, use cases, ports, adapters. A mermaid diagram if the flow changes.
   - **Risques & inconnues**.
   - **Docs touchées** – files to create/update.
   - **Vérification** – automated tests + the manual check on phone/TV.
4. Stop. Wait for an explicit "ok"/"go". Any scope change → update the artifact.
