# Pending: mc/1.20.1 port

**Status: not started.** This branch was cut from `master` @ `0966acd` on
2026-09-22 and has had zero port work since — it's `master` plus one status
file. If you're picking this up, start here.

Full status, what's done vs. not, and recommended order:
→ `MC-1.20.1-WIP.md` (this branch, same directory)

The plan this branch implements Part 2 of:
→ `/home/colaila/.claude/plans/lets-add-support-for-quirky-yao.md`

One-line summary of the work: port the mod from MC 26.1.2 (Mojang mappings,
Java 25) to MC 1.20.1 (Yarn mappings, Java 17) — build config, a full
mappings-flip source port, several renamed/relocated vanilla APIs, two
render-API rewrites (no Gizmos/GuiGraphicsExtractor pre-26.x), and a
WaypointFinder stub (no Locator Bar pre-1.21.6). See the plan's Part 2b for
the itemized list.

Before starting, check whether Part 1 (pathfinder `NavWorld` isolation +
`hello`-event version fields, meant to land on `master` first) has happened
yet — if not, read `MC-1.20.1-WIP.md`'s recommendation on whether to wait for
it or port ahead of it.
