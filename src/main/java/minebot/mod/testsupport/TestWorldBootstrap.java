package minebot.mod.testsupport;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorPresets;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dev-only, one-shot: generates the disposable in-game-test world save (see
 * minebot-mod's own TESTING.md) by calling Minecraft's own real world-
 * creation flow (WorldOpenFlows.createFreshLevel -- the exact method
 * CreateWorldScreen's own "Create New World" button calls) directly,
 * headlessly, with no GUI confirmation click needed -- confirmed via the
 * decompiled bytecode that createFreshLevel already blocks synchronously
 * on data-pack/world-data loading and then calls Minecraft.doWorldLoad
 * itself, unlike CreateWorldScreen.testWorld()/openFresh(), which both
 * still only ever open a real CreateWorldScreen for a human to confirm.
 *
 * Gated behind the `minebot.bootstrapTestWorld` system property (a world
 * NAME, e.g. "minebot-test-world") so this NEVER activates during normal
 * play -- onInitializeClient only registers this hook when the property is
 * actually set, the same "only during a real, standalone Loom runClient
 * invocation for this exact purpose" reasoning ControlClient's own
 * dev-vs-prod split doesn't need (it's always active), but this genuinely
 * destructive, one-shot action does.
 *
 * World generation rules, per explicit direction: game mode creative,
 * difficulty peaceful, allow commands true (the test harness drives world
 * setup via real "/" chat commands, see TESTING.md), world type
 * superflat, customized as "the void" (a single minecraft:air layer over
 * the minecraft:the_void biome -- vanilla's own built-in "The Void"
 * preset, FlatLevelGeneratorPresets.THE_VOID, the same one
 * CreateWorldScreen's "World Type: Superflat" -> "Customize" -> preset
 * dropdown offers), structure generation off, bonus chest off. Built by
 * taking WorldPresets.createFlatWorldDimensions's normal flat
 * WorldDimensions (still needed for a correctly-configured nether/end,
 * not just the overworld) and replacing its overworld generator with a
 * fresh FlatLevelSource built from THE_VOID's own settings via
 * WorldDimensions.replaceOverworldGenerator -- there's no public preset
 * constant for "flat + void layers" as a single WorldPreset the way
 * WorldPresets.FLAT is, so the void layers have to be substituted in
 * after the fact rather than selected as a preset directly.
 *
 * Runs once on the first real client tick (world creation needs a
 * loaded HolderLookup/registry access, not available before then) via
 * ClientTickEvents rather than directly in onInitializeClient, then
 * unregisters itself (ClientTickEvents has no one-shot variant) so it never
 * fires again even if somehow left wired up for longer than one tick.
 */
public final class TestWorldBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger("minebot-mod-testsupport");
    private static final String PROPERTY = "minebot.bootstrapTestWorld";

    private TestWorldBootstrap() {
    }

    /** No-op unless the system property is set -- see this class's own docstring. */
    public static void registerIfRequested() {
        String worldName = System.getProperty(PROPERTY);
        if (worldName == null || worldName.isBlank()) {
            return;
        }
        LOGGER.warn("minebot test-support: will bootstrap disposable world '{}' on the first client tick, then quit", worldName);
        ClientTickEvents.END_CLIENT_TICK.register(new OneShotCreator(worldName));
    }

    private static final class OneShotCreator implements ClientTickEvents.EndTick {
        private final String worldName;
        private boolean fired;

        OneShotCreator(final String worldName) {
            this.worldName = worldName;
        }

        @Override
        public void onEndTick(final Minecraft minecraft) {
            if (fired) {
                return;
            }
            fired = true;
            LOGGER.warn("minebot test-support: creating world '{}'", worldName);
            WorldOpenFlows flows = new WorldOpenFlows(minecraft, minecraft.getLevelSource());
            LevelSettings settings = new LevelSettings(
                worldName,
                GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                true, // allowCommands -- the test harness drives world setup via real "/" chat commands, see TESTING.md
                WorldDataConfiguration.DEFAULT
            );
            // A fixed seed (not defaultWithRandomSeed()) so the checked-in
            // save this bootstraps is reproducible if ever regenerated --
            // structures/generateBonusChest are both irrelevant for a flat
            // world, left at WorldOptions' own defaults via the same
            // 2-arg-false shape testWorldWithRandomSeed() uses, just with a
            // literal seed instead of a random one.
            WorldOptions options = new WorldOptions(0L, false, false);
            flows.createFreshLevel(
                worldName,
                settings,
                options,
                OneShotCreator::createVoidWorldDimensions,
                minecraft.screen
            );
            LOGGER.warn("minebot test-support: createFreshLevel returned -- world should now be loading/loaded");
        }

        /**
         * Superflat + THE_VOID preset's layers (a single minecraft:air
         * layer over minecraft:the_void) -- see this class's own docstring
         * for why this substitutes the void layers into a normal flat
         * WorldDimensions rather than selecting a preset directly (no
         * public WorldPreset combines "flat" with "void layers" as one
         * selectable unit the way WorldPresets.FLAT does for the classic
         * flat layers).
         */
        private static WorldDimensions createVoidWorldDimensions(final HolderLookup.Provider registries) {
            WorldDimensions flatDimensions = WorldPresets.createFlatWorldDimensions(registries);
            var voidSettings = registries.lookupOrThrow(Registries.FLAT_LEVEL_GENERATOR_PRESET)
                .getOrThrow(FlatLevelGeneratorPresets.THE_VOID)
                .value()
                .settings();
            return flatDimensions.replaceOverworldGenerator(registries, new FlatLevelSource(voidSettings));
        }
    }
}
