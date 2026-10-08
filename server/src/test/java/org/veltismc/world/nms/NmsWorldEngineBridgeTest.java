package org.veltismc.world.nms;

import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.veltismc.world.api.WorldConfig;
import org.veltismc.world.api.WorldEngine;
import org.veltismc.world.api.WorldEngines;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ServerLevel-to-Veltis-world mapping: identity-based, deduplicated, and
 * removable — one level maps to exactly one world, distinct dimensions to
 * distinct worlds, and unbinding only drops the mapping.
 */
class NmsWorldEngineBridgeTest {

    private WorldEngine engine;
    private NmsWorldEngineBridge bridge;

    @BeforeAll
    static void bootstrap() {
        MinecraftTestSupport.bootstrapMinecraft();
    }

    @BeforeEach
    void startEngine() {
        engine = WorldEngines.create(WorldConfig.builder()
                .minWorkers(1)
                .maxWorkers(2)
                .adaptiveWorkers(false)
                .build());
        engine.start();
        bridge = new NmsWorldEngineBridge(engine);
    }

    @AfterEach
    void stopEngine() {
        engine.stop();
    }

    @Test
    void oneLevelMapsToOneWorld() {
        ServerLevel level = MinecraftTestSupport.newServerLevel();

        NmsWorldAdapter first = bridge.bind("minecraft:overworld", level);
        NmsWorldAdapter second = bridge.bind("minecraft:overworld", level);

        assertSame(first, second, "binding the same level twice must reuse the adapter");
        assertSame(level, first.serverLevel());
        assertEquals(1, engine.worlds().size(), "one level must produce exactly one Veltis world");
        assertNotNull(engine.world("minecraft:overworld"));
        assertSame(first.engineWorld(), engine.world("minecraft:overworld"));
        assertSame(first, bridge.adapter(level));
    }

    @Test
    void rebindWithDifferentNameKeepsTheOriginalBinding() {
        ServerLevel level = MinecraftTestSupport.newServerLevel();

        NmsWorldAdapter first = bridge.bind("first", level);
        NmsWorldAdapter again = bridge.bind("second", level);

        assertSame(first, again, "level identity wins: no duplicate binding for one level");
        assertNull(engine.world("second"), "a second name for a bound level must not create a world");
    }

    @Test
    void distinctDimensionsMapToDistinctWorlds() {
        ServerLevel overworld = MinecraftTestSupport.newServerLevel();
        ServerLevel nether = MinecraftTestSupport.newServerLevel();

        NmsWorldAdapter a = bridge.bind("minecraft:overworld", overworld);
        NmsWorldAdapter b = bridge.bind("minecraft:the_nether", nether);

        assertNotSame(a, b, "distinct levels get distinct bindings");
        assertNotSame(a.engineWorld(), b.engineWorld(), "distinct dimensions get distinct worlds");
        assertEquals(2, engine.worlds().size());
        assertSame(a, bridge.adapter(overworld));
        assertSame(b, bridge.adapter(nether));
    }

    @Test
    void distinctLevelsWithTheSameNameShareTheWorldButNotTheAdapter() {
        ServerLevel levelA = MinecraftTestSupport.newServerLevel();
        ServerLevel levelB = MinecraftTestSupport.newServerLevel();

        NmsWorldAdapter a = bridge.bind("shared", levelA);
        NmsWorldAdapter b = bridge.bind("shared", levelB);

        assertNotSame(a, b, "bindings are keyed by level identity, not by name");
        assertSame(a.engineWorld(), b.engineWorld(), "the same name resolves to the same world");
        assertEquals(1, engine.worlds().size());
    }

    @Test
    void unbindRemovesOnlyThatMapping() {
        ServerLevel first = MinecraftTestSupport.newServerLevel();
        ServerLevel second = MinecraftTestSupport.newServerLevel();
        NmsWorldAdapter boundFirst = bridge.bind("w1", first);
        bridge.bind("w2", second);

        NmsWorldAdapter removed = bridge.unbind(first);

        assertSame(boundFirst, removed);
        assertNull(bridge.adapter(first), "unbind must drop the mapping");
        assertNotNull(bridge.adapter(second), "other bindings must survive");
        assertNull(bridge.unbind(first), "unbinding twice is a no-op");
        assertNotNull(engine.world("w1"), "unbind must not close the world; it belongs to the engine");
    }

    @Test
    void clearRemovesEveryBinding() {
        ServerLevel first = MinecraftTestSupport.newServerLevel();
        ServerLevel second = MinecraftTestSupport.newServerLevel();
        bridge.bind("w1", first);
        bridge.bind("w2", second);

        bridge.clear();

        assertNull(bridge.adapter(first));
        assertNull(bridge.adapter(second));
        assertTrue(bridge.adapters().isEmpty());
        assertEquals(2, engine.worlds().size(), "clear must not close worlds");
    }

    @Test
    void adaptersSnapshotIsImmutable() {
        bridge.bind("w1", MinecraftTestSupport.newServerLevel());

        List<NmsWorldAdapter> snapshot = bridge.adapters();

        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }

    @Test
    void invalidBindingsAreRejected() {
        ServerLevel level = MinecraftTestSupport.newServerLevel();

        assertThrows(IllegalArgumentException.class, () -> bridge.bind("   ", level));
        assertThrows(NullPointerException.class, () -> bridge.bind(null, level));
        assertThrows(NullPointerException.class, () -> bridge.bind("w", null));
        assertTrue(bridge.adapters().isEmpty());
    }
}
