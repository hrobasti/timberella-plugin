package net.kroet.timberella.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

/**
 * A block counts as custom if any hooked plugin claims it; a failing adapter is
 * dropped without breaking the others.
 */
class CustomBlockGuardsTest {

    private static final Logger LOGGER = Logger.getLogger("CustomBlockGuardsTest");
    private static final Block BLOCK = (Block) Proxy.newProxyInstance(Block.class.getClassLoader(),
            new Class<?>[]{Block.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
            });

    private static CustomBlockGuards guards(Map<String, CustomBlockGuard> adapters) {
        return new CustomBlockGuards(adapters, LOGGER);
    }

    @Test
    void noHookedPluginMeansNothingIsCustom() {
        assertFalse(guards(Map.of()).isCustomBlock(BLOCK));
        assertFalse(CustomBlockGuard.NONE.isCustomBlock(BLOCK));
    }

    @Test
    void anyAdapterClaimingTheBlockMakesItCustom() {
        Map<String, CustomBlockGuard> adapters = new LinkedHashMap<>();
        adapters.put("ItemsAdder", block -> false);
        adapters.put("MMOItems", block -> true);
        assertTrue(guards(adapters).isCustomBlock(BLOCK));
        assertFalse(guards(Map.of("ItemsAdder", block -> false)).isCustomBlock(BLOCK));
        assertFalse(guards(Map.of("MMOItems", block -> true)).isCustomBlock(null));
    }

    @Test
    void failingAdapterIsDroppedAndOthersKeepWorking() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, CustomBlockGuard> adapters = new LinkedHashMap<>();
        adapters.put("CraftEngine", block -> {
            calls.incrementAndGet();
            throw new NoSuchMethodError("API changed");
        });
        adapters.put("MythicCrucible", block -> true);
        CustomBlockGuards guards = guards(adapters);

        assertTrue(guards.isCustomBlock(BLOCK));
        assertTrue(guards.isCustomBlock(BLOCK));
        assertEquals(1, calls.get());
        assertEquals(List.of("MythicCrucible"), guards.hookedPlugins());
    }
}
