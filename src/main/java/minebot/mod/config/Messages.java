package minebot.mod.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import minebot.mod.MinebotMod;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Dictionary for the bot's own outgoing chat lines (e.g. FoodEater's "low
 * health, eating" message), separate from Fabric's standard lang-file
 * system under assets/minebot-mod/lang/ -- that one translates *this
 * client's own GUI* (the config screen) based on the player's Minecraft
 * language setting via Component.translatable, which isn't the right tool
 * for text the bot *sends*, since that needs to follow Configs.botLanguage
 * (a settings toggle, not the viewing player's client language) and support
 * {placeholder} substitution for values like the bot's current health.
 *
 * Backed by assets/minebot-mod/messages/{lang}.json, flat string maps
 * (key -> template). Loaded once per language on first use and cached --
 * these files are bundled mod resources, not something that changes at
 * runtime.
 */
public final class Messages {
    private static final Map<String, Map<String, String>> CACHE = new HashMap<>();
    private static final String DEFAULT_LANGUAGE = "en";

    private Messages() {
    }

    /** Looks up {@code key} in the language selected by Configs.botLanguage and substitutes any {@code {name}} placeholders from {@code params} (given as alternating name/value pairs). */
    public static String get(final String key, final Object... params) {
        String template = lookup(Configs.botLanguage, key);
        if (template == null) {
            template = lookup(DEFAULT_LANGUAGE, key);
        }
        if (template == null) {
            return key; // last resort: still send *something* recognizable rather than silently dropping the message
        }

        String result = template;
        for (int i = 0; i + 1 < params.length; i += 2) {
            result = result.replace("{" + params[i] + "}", String.valueOf(params[i + 1]));
        }
        return result;
    }

    private static String lookup(final String language, final String key) {
        return dictionaryFor(language).get(key);
    }

    private static synchronized Map<String, String> dictionaryFor(final String language) {
        return CACHE.computeIfAbsent(language, Messages::load);
    }

    private static Map<String, String> load(final String language) {
        String path = "/assets/minebot-mod/messages/" + language + ".json";
        Map<String, String> result = new HashMap<>();
        try (InputStream stream = Messages.class.getResourceAsStream(path)) {
            if (stream == null) {
                MinebotMod.LOGGER.warn("no message dictionary bundled for language '{}' ({})", language, path);
                return result;
            }
            JsonObject json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : json.entrySet()) {
                result.put(entry.getKey(), entry.getValue().getAsString());
            }
        } catch (IOException | RuntimeException e) {
            MinebotMod.LOGGER.warn("failed to load message dictionary '{}': {}", path, e.toString());
        }
        return result;
    }
}
