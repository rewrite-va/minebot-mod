package minebot.mod;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Reads the git commit hash + build timestamp baked into the jar at
 * build time (see build.gradle's generateBuildInfo task) -- lets the
 * running mod report exactly what code it's actually running, so a live
 * "why isn't my fix working" report can be answered by comparing this
 * against the mod repo's current `git rev-parse HEAD` instead of
 * guessing whether the deployed jar is stale (found live: it was, more
 * than once, and there was no way to tell from the backend's logs alone).
 */
public final class BuildInfo {
    public static final String COMMIT;
    public static final String BUILT_AT;

    static {
        Properties props = new Properties();
        try (InputStream stream = BuildInfo.class.getResourceAsStream("/minebot-mod-build-info.properties")) {
            if (stream != null) {
                props.load(stream);
            }
        } catch (IOException e) {
            MinebotMod.LOGGER.warn("failed to load build info: {}", e.toString());
        }
        COMMIT = props.getProperty("commit", "unknown");
        BUILT_AT = props.getProperty("built_at", "unknown");
    }

    private BuildInfo() {
    }
}
