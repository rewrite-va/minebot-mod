package minebot.mod;

import minebot.mod.statemachine.Blackboard;
import minebot.mod.statemachine.BlackboardKey;
import minebot.mod.statemachine.StateMachine;
import minebot.mod.statemachine.playerintention.CombatEngagement;
import minebot.mod.statemachine.playerintention.NavIntent;
import minebot.mod.statemachine.hands.HandsEatNode;
import minebot.mod.statemachine.legs.LegsNavigateNode;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Small always-on HUD line showing whether the mod's control channel is
 * currently connected to the Python backend -- added after live testing
 * made clear that "is anything actually happening" was otherwise only
 * visible by digging through the game log. Green/connected or
 * red/disconnected, top-left corner, drop-shadowed for readability
 * against any background.
 *
 * Includes BuildInfo.BUILT_AT (reformatted the same readable way as
 * minebot's own mod_version._format_built_at, in Pacific time to match
 * the user's own local clock -- confirmed live that a first attempt at
 * this rendered in UTC read as "incorrect" at a glance) so a
 * stale-deployed-but-not-yet-restarted client is visible at a glance in
 * this same on-screen line, not just in the backend's own log --
 * reported live repeatedly that this line still just said "minebot:
 * connected" with no version of any kind after the backend-log-side
 * format was changed, since that was a completely separate string from
 * this one.
 *
 * Also renders one line per registered StateMachine (see
 * STATE_MACHINE.md) showing its current state -- e.g. "playerintention:
 * FOLLOW" -- directly below the connection line, so a state machine's
 * real live behavior is visible at a glance the same way connection
 * status already is, without needing to read the log. `stateMachines` is
 * a fixed list handed in at construction (today: PlayerIntention/Legs/
 * Head/Hands). The "playerintention: " label half of each line renders
 * in plain white (COLOR_SM_NAME); the state name half (e.g. "FOLLOW")
 * renders in a color unique to that specific node name (see
 * nodeColor()) -- lets a glance tell nodes apart from each other, not
 * just SMs from each other.
 *
 * Below the SM-state lines, renders one line per entry in
 * BLACKBOARD_KEYS_TO_DISPLAY -- the actual cross-SM data values (e.g.
 * CombatEngagement.TARGET_ENTITY_ID, NavIntent.NAV_TARGET), not just
 * which state each SM is in. Added per explicit request: knowing
 * PlayerIntention is in KILL doesn't say WHO it's fighting or how far
 * away -- these
 * values are exactly the facts every node's own onTick already publishes
 * for other SMs to read, just also surfaced here for a human. A fixed,
 * explicit list (like stateMachines) rather than iterating
 * Blackboard.data's full contents automatically -- BlackboardKey
 * deliberately carries no notion of "should this be user-visible" (some
 * future key might be too noisy/internal for the HUD), and an explicit
 * list keeps this in one obvious place to update when a key worth
 * watching is added, the same reasoning nodeColor()'s own docstring
 * gives for not needing a per-state color list.
 */
public final class StatusHud {
    private static final int COLOR_CONNECTED = 0xFF55FF55;
    private static final int COLOR_DISCONNECTED = 0xFFFF5555;
    // The "playerintention: "/"legs: "/etc. label itself -- plain white so the
    // node name (color-coded per node, see nodeColor()) is the only
    // thing drawing the eye's attention on each line, per explicit
    // request.
    private static final int COLOR_SM_NAME = 0xFFFFFFFF;
    // Blackboard-value labels render dimmer than SM-state labels (which
    // stay bright white) -- a deliberate visual demotion so the two
    // sections read as "current states" (primary) vs. "supporting data"
    // (secondary) at a glance, not as two equally-important lists.
    private static final int COLOR_BLACKBOARD_LABEL = 0xFFAAAAAA;
    private static final int COLOR_BLACKBOARD_VALUE = 0xFFDDDDDD;
    // Confirmed live: the drop shadow alone (see the `true` dropShadow
    // arg on each text() call below) wasn't enough to stay readable
    // against a bright sky -- light-colored text with a dark shadow still
    // washes out against light blue. A panel behind every line fixes
    // that regardless of background brightness/color, the same fix
    // vanilla's own GuiGraphicsExtractor.textWithBackdrop uses for
    // chat/subtitle text. Semi-transparent (not opaque) so this still
    // reads as a HUD overlay, not an opaque box sitting on the screen.
    private static final int COLOR_PANEL_BACKGROUND = 0x90000000;
    private static final int PANEL_PADDING = 3;
    private static final int LINE_HEIGHT = 10;
    private static final DateTimeFormatter BUILT_AT_FORMAT =
        DateTimeFormatter.ofPattern("'v'yyyyMMdd HH.mm.ss").withZone(ZoneId.of("America/Los_Angeles"));

    /** Every Blackboard data key worth showing on the HUD -- see this class's own docstring for why this is an explicit list, not every key Blackboard.data happens to hold. */
    private static final List<BlackboardKey<?>> BLACKBOARD_KEYS_TO_DISPLAY = List.of(
        CombatEngagement.TARGET_ENTITY_ID,
        CombatEngagement.SELECTED_WEAPON,
        NavIntent.NAV_TARGET,
        NavIntent.NAV_ARRIVED,
        HandsEatNode.NEEDS_HEAL,
        LegsNavigateNode.WAYPOINT_COORDINATES,
        DeathWatcher.DEATH_POSITION
    );

    private final ControlClient controlClient;
    private final List<StateMachine<?>> stateMachines;
    private final Blackboard blackboard;

    public StatusHud(final ControlClient controlClient, final List<StateMachine<?>> stateMachines, final Blackboard blackboard) {
        this.controlClient = controlClient;
        this.stateMachines = stateMachines;
        this.blackboard = blackboard;
    }

    public void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("minebot-mod", "status"), this::extractRenderState);
    }

    private void extractRenderState(final GuiGraphicsExtractor guiGraphics, final DeltaTracker deltaTracker) {
        boolean connected = controlClient.isOpen();
        String connectionLine = "backend: " + (connected ? "connected" : "disconnected") + " (" + formatBuiltAt(BuildInfo.BUILT_AT) + ")";

        // "playerintention: " and "FOLLOW" kept as separate strings (not one
        // formatted line) since each half renders in its own color --
        // see the loop below.
        List<String> smLabels = stateMachines.stream().map(stateMachine -> stateMachine.name() + ": ").toList();
        List<String> nodeNames = stateMachines.stream().map(stateMachine -> stateMachine.currentState().toString()).toList();

        List<String> blackboardLabels = BLACKBOARD_KEYS_TO_DISPLAY.stream().map(key -> key.name() + ": ").toList();
        List<String> blackboardValues = BLACKBOARD_KEYS_TO_DISPLAY.stream().map(this::formatBlackboardValue).toList();

        Font font = Minecraft.getInstance().font;
        int widestLine = font.width(connectionLine);
        for (int i = 0; i < smLabels.size(); i++) {
            widestLine = Math.max(widestLine, font.width(smLabels.get(i)) + font.width(nodeNames.get(i)));
        }
        for (int i = 0; i < blackboardLabels.size(); i++) {
            widestLine = Math.max(widestLine, font.width(blackboardLabels.get(i)) + font.width(blackboardValues.get(i)));
        }
        int totalLines = 1 + smLabels.size() + blackboardLabels.size();
        guiGraphics.fill(
            4 - PANEL_PADDING,
            4 - PANEL_PADDING,
            4 + widestLine + PANEL_PADDING,
            4 + (totalLines - 1) * LINE_HEIGHT + font.lineHeight + PANEL_PADDING,
            COLOR_PANEL_BACKGROUND
        );

        int color = connected ? COLOR_CONNECTED : COLOR_DISCONNECTED;
        guiGraphics.text(font, connectionLine, 4, 4, color, true);

        int y = 4 + LINE_HEIGHT;
        for (int i = 0; i < smLabels.size(); i++) {
            String label = smLabels.get(i);
            String nodeName = nodeNames.get(i);
            guiGraphics.text(font, label, 4, y, COLOR_SM_NAME, true);
            guiGraphics.text(font, nodeName, 4 + font.width(label), y, nodeColor(nodeName), true);
            y += LINE_HEIGHT;
        }
        for (int i = 0; i < blackboardLabels.size(); i++) {
            String label = blackboardLabels.get(i);
            String value = blackboardValues.get(i);
            guiGraphics.text(font, label, 4, y, COLOR_BLACKBOARD_LABEL, true);
            guiGraphics.text(font, value, 4 + font.width(label), y, COLOR_BLACKBOARD_VALUE, true);
            y += LINE_HEIGHT;
        }
    }

    /**
     * Formats one Blackboard value for display -- generic String.valueOf
     * for most values, with two exceptions: CombatEngagement.
     * TARGET_ENTITY_ID gets the live entity's own readable name appended
     * in parentheses (a player's real name via getScoreboardName() -- the
     * same accessor MinebotMod's own broadcastEntityEvent already uses --
     * or a mob's registry type name otherwise) -- a bare numeric entity id
     * on its own says nothing useful at a glance, per explicit request;
     * and any Vec3-shaped value (NAV_TARGET's own position, DEATH_POSITION)
     * gets each coordinate trimmed to 1 decimal place -- Vec3's own
     * toString()/a record's auto-generated one print full double
     * precision (a dozen-plus meaningless digits), which is real noise on
     * a HUD meant for an at-a-glance read, per explicit request ("trim
     * positions to a 1 decimal place only").
     */
    private String formatBlackboardValue(final BlackboardKey<?> key) {
        Object value = blackboard.get(key);
        if (key == CombatEngagement.TARGET_ENTITY_ID && value instanceof Integer entityId) {
            ClientLevel level = Minecraft.getInstance().level;
            Entity entity = level != null ? level.getEntity(entityId) : null;
            if (entity == null) {
                return String.valueOf(entityId);
            }
            String name = entity instanceof Player player ? player.getScoreboardName() : BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            return entityId + " (" + name + ")";
        }
        if (value instanceof Vec3 position) {
            return formatPosition(position);
        }
        if (value instanceof NavIntent.Target target) {
            return formatPosition(target.position()) + " (stop=" + target.stopDistance() + ")";
        }
        return String.valueOf(value);
    }

    /** Each coordinate rounded to 1 decimal place -- see formatBlackboardValue's own docstring for why. */
    private static String formatPosition(final Vec3 position) {
        return String.format("(%.1f, %.1f, %.1f)", position.x(), position.y(), position.z());
    }

    /**
     * A stable, unique-per-node-name color, derived from a hash of the
     * name itself rather than a hardcoded per-state palette -- avoids
     * having to remember to add a color every time a new state gets
     * added to any of the 4+ growing SMs (PlayerIntention alone already has 6:
     * IDLE/FOLLOW/DEFEND/KILL/DEAD/GO_TO_DEATH_POSITION/PICKUP_ITEMS).
     * Fixed saturation/lightness (only hue varies) so every generated
     * color stays readably bright against the dark panel background,
     * never landing on a near-black or near-white hash result the way a
     * hash-the-whole-RGB-value approach could.
     */
    private static int nodeColor(final String nodeName) {
        int hue = Math.floorMod(nodeName.hashCode(), 360);
        return 0xFF000000 | (java.awt.Color.HSBtoRGB(hue / 360f, 0.65f, 1.0f) & 0xFFFFFF);
    }

    private static String formatBuiltAt(final String builtAt) {
        try {
            return BUILT_AT_FORMAT.format(Instant.parse(builtAt));
        } catch (DateTimeParseException e) {
            return builtAt;
        }
    }
}
