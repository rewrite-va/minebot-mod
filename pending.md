# Pending: mc/1.21.1 port

**Status: not started.** This branch was cut from `master` @ `0966acd` and
has had zero port work since — it's `master` plus the handoff notes file.
If you're picking this up, start here.

Handoff notes (what's known, what's unverified, recommended first steps):
→ `PORT_TO_1-21-1_NOTES.md` (this branch, same directory)

The plan this branch implements a version of Part 2 for:
→ `/home/colaila/.claude/plans/lets-add-support-for-quirky-yao.md`
(written for 1.20.1 — adapt Part 2's approach, don't copy its specifics;
1.21.1's actual API surface is unverified, see the notes file).

Sibling branch `mc/1.20.1` is in the same not-yet-ported state — see its own
`pending.md` / `MC-1.20.1-WIP.md` if useful as a reference for the general
shape of a version port, but 1.21.1's specifics differ and need their own
verification (mapping provider, Java version, Locator Bar / Gizmos presence,
entity package layout, DamageTypes set).
