package minebot.mod;

import java.util.List;
import java.util.Map;

/**
 * Answers "what does collecting X actually give me" (forward) and "what
 * should I collect to get item Y" (reverse) -- backs the `query`
 * command's `drops_from`/`source_for` sub-types, which
 * MiningController.collect (Python side) uses both to confirm a real
 * drop matches what a collect attempt was for, and to resolve a
 * non-minable/non-huntable item request (e.g. "cobblestone", which has
 * no `cobblestone` block or entity of its own) back to what to actually
 * search for and break/kill (stone).
 *
 * KNOWN GAP, not yet resolved: this is a small hardcoded map, not real
 * loot-table data. A genuine "what does this block/entity really drop"
 * answer needs the actual loot table, which is **server-side-only**
 * data in real Minecraft -- confirmed by reading the decompiled source:
 * `Block.getLootTable()` only returns a `ResourceKey<LootTable>`
 * *reference*, and resolving that key to real items requires
 * `level.getServer().reloadableRegistries().getLootTable(...)`, which
 * doesn't exist at all on a `ClientLevel` (there is no `getServer()` on
 * the client side -- this mod is a real client connecting to a remote
 * server, the same as any player, with no local access to that server's
 * loot tables, vanilla or custom/datapack-modified). There is currently
 * no known reliable way for a pure client-side mod to answer this
 * question from real game data. This hardcoded map is a deliberate
 * stopgap covering the common cases well enough for !collect to work in
 * practice -- revisit if a real solution is ever found (a server-side
 * companion plugin exposing this data is the most plausible path, but
 * that's a materially different architecture than "the mod is the only
 * thing talking to the server" this project otherwise commits to).
 */
public final class DropTable {
    private DropTable() {
    }

    // Forward: collect query -> the item(s) actually expected to drop.
    // Deliberately small and only the common, unambiguous cases --
    // anything not listed here falls back to "the query name IS the
    // drop name" (already correct for plenty of blocks: dirt, oak_log,
    // sand, ...).
    private static final Map<String, List<String>> DROPS_FROM = Map.ofEntries(
        Map.entry("stone", List.of("cobblestone")),
        // Deliberately no "cobblestone" -> [...] entry -- already-cobbled
        // stone drops cobblestone under its own name, which the fallback
        // (dropsFrom returns [query] when not in this map) already
        // covers correctly without needing an explicit self-mapping.
        Map.entry("coal_ore", List.of("coal")),
        Map.entry("deepslate_coal_ore", List.of("coal")),
        Map.entry("iron_ore", List.of("raw_iron")),
        Map.entry("deepslate_iron_ore", List.of("raw_iron")),
        Map.entry("gold_ore", List.of("raw_gold")),
        Map.entry("deepslate_gold_ore", List.of("raw_gold")),
        Map.entry("diamond_ore", List.of("diamond")),
        Map.entry("deepslate_diamond_ore", List.of("diamond")),
        Map.entry("redstone_ore", List.of("redstone")),
        Map.entry("deepslate_redstone_ore", List.of("redstone")),
        Map.entry("lapis_ore", List.of("lapis_lazuli")),
        Map.entry("deepslate_lapis_ore", List.of("lapis_lazuli")),
        Map.entry("emerald_ore", List.of("emerald")),
        Map.entry("deepslate_emerald_ore", List.of("emerald")),
        Map.entry("grass_block", List.of("dirt")),
        // Farmland crops -- the real block ids are plural ("carrots",
        // "potatoes", "beetroots") and don't match the singular item name
        // a player naturally asks for ("!collect carrot"). Without an
        // explicit source_for("carrot") -> "carrots" mapping here,
        // !collect carrot searched for a block/entity literally named
        // "carrot" (which doesn't exist) and always reported "no more
        // carrot found nearby", even standing in a fully grown carrot
        // farm. "wheat" itself is already both the block id and the item
        // id, so (like cobblestone above) it needs no entry -- the
        // fallback already covers it correctly. Potato drops occasionally
        // include a poisonous potato (real vanilla randomness, ~2%
        // chance) -- not modeled here, same "known gap" as cow/
        // enderman's own randomness noted above.
        Map.entry("carrots", List.of("carrot")),
        Map.entry("potatoes", List.of("potato")),
        Map.entry("beetroots", List.of("beetroot")),
        Map.entry("cow", List.of("beef", "leather")), // real randomness: 0-2 of each: see class docstring's known gap
        Map.entry("sheep", List.of("mutton", "wool")),
        Map.entry("chicken", List.of("chicken", "feather")),
        Map.entry("pig", List.of("porkchop")),
        Map.entry("rabbit", List.of("rabbit", "rabbit_hide")),
        Map.entry("enderman", List.of("ender_pearl")), // real randomness: not guaranteed every kill
        Map.entry("zombie", List.of("rotten_flesh")),
        Map.entry("skeleton", List.of("bone", "arrow")),
        Map.entry("spider", List.of("string", "spider_eye"))
    );

    // Reverse: desired item -> what to actually !collect for it. Built
    // from DROPS_FROM's own entries so the two can never drift apart --
    // where a drop is ambiguous (e.g. "leather" could come from a cow or
    // a rabbit), the first entry encountered wins; !collect callers that
    // care about a specific source should just ask for that source
    // directly (!collect cow) rather than the item.
    private static final Map<String, String> SOURCE_FOR = buildReverseMap();

    private static Map<String, String> buildReverseMap() {
        Map<String, String> reverse = new java.util.HashMap<>();
        for (Map.Entry<String, List<String>> entry : DROPS_FROM.entrySet()) {
            for (String drop : entry.getValue()) {
                reverse.putIfAbsent(drop, entry.getKey());
            }
        }
        return reverse;
    }

    /** What collecting `query` (a block or entity type, bare id like "stone") actually drops -- falls back to [query] itself if not in the map (correct for most simple 1:1 blocks). */
    public static List<String> dropsFrom(final String query) {
        return DROPS_FROM.getOrDefault(query, List.of(query));
    }

    /** What to !collect to get `item` (bare id like "cobblestone") -- falls back to `item` itself if not in the map (correct when the item IS directly minable/huntable under that same name). */
    public static String sourceFor(final String item) {
        return SOURCE_FOR.getOrDefault(item, item);
    }
}
