package net.kroet.timberella.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/**
 * The usage line and tab completion only offer subcommands the sender may use.
 */
class UsageSubcommandsTest {

    private static CommandSender senderWith(String... permissions) {
        Set<String> granted = Set.of(permissions);
        return (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission") && args[0] instanceof String node) {
                        return granted.contains(node);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void senderWithoutPermissionsOnlySeesVersion() {
        assertEquals(List.of("version"), TimberellaCommand.availableSubcommands(senderWith()));
    }

    @Test
    void defaultPlayerSeesVersionAndToggle() {
        assertEquals(List.of("version", "toggle"),
                TimberellaCommand.availableSubcommands(senderWith("timberella.toggle")));
    }

    @Test
    void adminSeesAllSubcommands() {
        assertEquals(List.of("reload", "version", "toggle"),
                TimberellaCommand.availableSubcommands(senderWith("timberella.admin")));
    }
}
