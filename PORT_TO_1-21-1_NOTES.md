Add Minecraft 1.21.1 support to minebot-mod, as a third version branch
alongside the existing multi-version work. Read this whole file before
doing anything.

CONTEXT -- read these first, in order
1. /home/colaila/.claude/plans/lets-add-support-for-quirky-yao.md
   The approved multi-version plan (git-branch-per-MC-version, full parity
   scope, version handshake + CI guardrails). Parts 1-4 apply to 1.21.1
   the same way they apply to 1.20.1 -- this isn't a new plan, it's the
   same plan for a third version.
2. ~/git/mods/minebot-mod/MC-1.20.1-WIP.md (on the mc/1.20.1 branch)
   Status of the sibling 1.20.1 port: branch was cut from master @ 0966acd
   on 2026-09-22, but NO port work has happened on it yet -- it's
   identical to master except for that status file. Check `git log
   mc/1.20.1` and `git status` on that branch before assuming otherwise;
   this file may be stale by the time you read it.
3. ~/git/minebot-backend/CLAUDE.md -- operating notes (rebuild/redeploy
   rules, key paths, the mod-version staleness trap).
4. ~/git/mods/minebot-mod/CLAUDE.md if present, plus STATE_MACHINE.md and
   TESTING.md in that repo.

CURRENT STATE OF THE REPO (as of 2026-09-22)
- `master` = trunk, targets Minecraft 26.1.2 (Mojang mappings, Java 25).
  This is the ONLY version currently confirmed working end-to-end against
  a real client.
- `mc/1.20.1` branch exists but is unported (see MC-1.20.1-WIP.md).
- Part 1 of the plan (pathfinder NavWorld isolation so pathfinding code
  forward-ports cleanly across branches; hello-event mc_version/
  data_version fields; BuildInfo mc_target) has NOT landed on master yet.
  If it still hasn't landed by the time you start, strongly consider doing
  it FIRST and letting both mc/1.20.1 and your new mc/1.21.1 branch fork
  from that better-prepared trunk, rather than porting against raw
  net.minecraft calls in Movements/BlockBreaker/PathTracker/etc. Check
  with the user if this reordering is welcome before doing a large prep
  refactor unprompted.

WHY 1.21.1 IS ITS OWN RESEARCH TASK, NOT A COPY OF THE 1.20.1 NOTES
1.20.1 (Yarn mappings, Java 17, no Locator Bar, no Gizmos debug-draw) and
26.1.2 (Mojang mappings, Java 25, has Locator Bar, has Gizmos) are the two
endpoints we have real data on. 1.21.1 sits chronologically between them
but its actual API shape is NOT yet verified -- no Loom cache for it
exists on this machine yet (`ls ~/.gradle/caches/fabric-loom/` shows only
26.1/26.1.2/26.2/26.2.1). Concretely unknown until you check:
- Mappings: 1.21.1 could still be on Yarn, or could already be on Mojang
  mappings depending on when this repo's mapping-provider choice changed
  upstream in Fabric tooling -- don't assume either way, check
  fabricmc.net/develop or the Loom docs for 1.21.1's actual mapping
  situation before writing gradle.properties.
- Java version: 1.21.1 shipped on Java 21, not 17 (1.20.1) or 25 (26.1.2)
  -- fairly confident on this one (Mojang's own launcher manifest says
  so) but verify via the actual mojang_minecraft_info.json once you fetch
  the Loom cache, the same way CLAUDE.md's decompile-cache section
  describes for 26.1.2.
- Locator Bar / waypoints (WaypointFinder.java, ClientWaypointManager):
  added in 1.21.6 per prior research on this codebase -- so 1.21.1 almost
  certainly does NOT have it, same as 1.20.1. Verify, don't assume; if
  confirmed absent, WaypointFinder needs the same follow-by-name-only
  stub described for the 1.20.1 branch.
- Gizmos debug-draw (net.minecraft.gizmos.Gizmos, used by
  PathVisualizer.java): this is a 26.x-era API per prior research. 1.21.1
  needs the same WorldRenderEvents-based rewrite as 1.20.1's
  PathVisualizer, almost certainly -- verify by checking whether the
  Gizmos class exists in the 1.21.1 client jar
  (`unzip -l <1.21.1-client.jar> | grep -i gizmo`).
- GuiGraphicsExtractor / extractRenderState (StatusHud.java,
  ConfigScreen.java): also flagged as 26.x-only in prior research (the
  render-state extraction API). 1.21.1 likely still uses the older
  DrawContext/`render(DrawContext,...)` pattern like 1.20.1, but this
  needs its own check -- don't copy the 1.20.1 rewrite blind, 1.21.1's
  exact DrawContext/GuiGraphics API shape may differ from 1.20.1's.
- ItemBreakMixin's onEquippedItemBroken signature, the BlockBreaker
  reflection field names on MultiPlayerGameMode/
  ClientPlayerInteractionManager, the DamageTypes constant set (MACE_SMASH
  and SPEAR are absent pre-1.21 per prior research -- but 1.21.1 IS
  1.21.x, so these may actually be present here, unlike on 1.20.1; check
  per-version, don't reuse the 1.20.1 exclusion list unmodified).
- Entity package locations (animal.bee.Bee vs passive.BeeEntity, etc.) --
  the relocation into animal.bee/animal.wolf/animal.polarbear subpackages
  was observed on 26.1.2; unclear which MC version introduced it. Check
  1.21.1's actual package layout via the Loom decompile cache technique
  in CLAUDE.md rather than assuming either the 1.20.1 or 26.1.2 layout.

RECOMMENDED FIRST STEPS
1. Confirm with the user which branch 1.21.1 forks from (master, i.e.
   trunk/26.1.2, per the plan's "branch per MC version" model -- almost
   certainly this, but the mc/1.20.1 precedent also forks from master, so
   confirm rather than assume anything more exotic).
2. Pull the Loom-relevant facts for 1.21.1 first, before writing any
   gradle.properties: mapping provider + version, a compatible
   loader_version and fabric_version from https://fabricmc.net/develop/
   (select Minecraft 1.21.1 in the dropdown), and the Java version from
   Mojang's version manifest.
3. Fetch/extract the 1.21.1 decompiled source the same way CLAUDE.md
   documents for 26.1.2 (`~/.gradle/caches/fabric-loom/decompile/v1.zip`
   is a content-addressed blob store per that doc -- grep blob contents,
   not filenames) to ground-truth every API question above instead of
   guessing from the 1.20.1/26.1.2 endpoints.
4. Create `mc/1.21.1` branch, `MC-1.21.1-WIP.md` status file (same pattern
   as MC-1.20.1-WIP.md), and follow Part 2 of the plan (build config,
   then compiler-driven source port) adapted to whatever you find in step
   2-3, not a blind copy of the 1.20.1 or 26.1.2 branch's specific changes.
5. Once 1.21.1 compiles and the version handshake (Part 1b/3 of the plan)
   is in place, do the same manual feature-parity pass listed in the
   plan's Verification section for mc/1.20.1, against a real 1.21.1
   client.

DO NOT
- Assume 1.21.1 = 1.20.1's API shape, or = 26.1.2's API shape, for
  anything not explicitly verified above. It is a distinct version with
  its own mapping/API surface.
- Skip the branch-trunk question (step 1) the way the original session
  had to stop and ask about minebot-mod's trunk branch before this work
  could start -- confirm the fork point before creating mc/1.21.1.
- Forget Part 0-equivalent hygiene: check `git status` on whatever branch
  you start from and land/stash anything pending before branching, same
  as this session did for the 1.20.1 branch cut.
