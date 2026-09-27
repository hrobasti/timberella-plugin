package net.kroet.timberella;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import net.kroet.turtlelib.helper.LocaleFileChecker;
import org.junit.jupiter.api.Test;

/**
 * Every bundled lang/*.yml must match en_US.yml in keys and MiniMessage tags,
 * parse strictly and be translated, and en_US must hold exactly the keys the
 * code uses.
 */
class LocaleConsistencyTest {

    // Placeholders the plugin resolves; any other non-standard tag would be
    // printed literally in-game.
    private static final String[] PLACEHOLDERS = {"backup", "category", "changes", "code", "count", "current",
            "current_ver", "entries", "error", "file", "key", "keys", "label", "latest_ver", "leaves", "list", "mc_version",
            "new", "old", "player", "plugin", "plugins", "provider", "replant", "required_server", "server_name",
            "subcommands", "supported_versions", "timber", "url", "value", "version"};

    @Test
    void bundledLocalesAreConsistentAndMatchTheCode() {
        LocaleFileChecker.Result result = LocaleFileChecker.builder(Path.of("src/main/resources/lang"))
                .placeholders(PLACEHOLDERS)
                .scanSources(Path.of("src/main/java"))
                // Old key names only appear in the rename map, never as used keys, and a
                // new name found only there doesn't count as used either.
                .renames(TimberellaPlugin.LEGACY_LANG_KEY_MIGRATIONS)
                // Values that are the same in every language (placeholders only, or the
                // word "Version", which German shares with English).
                .identicalAllowed("command.version", "log.reload_changes_line", "log.reload_changes_locale_line",
                        "log.update_provider_ok")
                .build()
                .check();
        assertFalse(result.hasProblems(), "Locale problems:\n" + result.describeProblems());
    }
}
