package minebot.mod.config;

/**
 * In-memory mod settings, editable from the ModMenu-provided config screen
 * (gui.ConfigScreen). Not persisted to disk -- resets to the default every
 * game launch, same as VillagerHelper's Configs (mods/VillagerHelper), the
 * pattern this is copied from. Add a config-file loader here if that ever
 * becomes worth the complexity.
 */
public final class Configs {
    private Configs() {
    }

    /** Selects which Messages dictionary (assets/minebot-mod/messages/*.json) the bot's own chat lines are drawn from. */
    public static volatile String botLanguage = "en";
}
