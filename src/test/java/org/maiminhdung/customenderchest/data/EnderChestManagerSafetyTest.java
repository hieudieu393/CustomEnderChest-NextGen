package org.maiminhdung.customenderchest.data;

import com.google.common.cache.CacheBuilder;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises production helpers without starting schedulers or a server.
 * Reflection is a temporary seam until persistence is extracted from the manager.
 * These are not full join/quit or Folia integration tests.
 */
@Timeout(10)
class EnderChestManagerSafetyTest {
    private EnderChestManager manager;

    @BeforeEach
    void setup() throws Exception {
        manager = mock(EnderChestManager.class, CALLS_REAL_METHODS);
        set("pendingSaves", new ConcurrentHashMap<UUID, CompletableFuture<Void>>());
        set("liveData", CacheBuilder.newBuilder().build());
    }

    @Test
    void mainWritesForOnePlayerRunInOrder() throws Exception {
        UUID player = UUID.randomUUID();
        CompletableFuture<Void> firstWrite = new CompletableFuture<>();
        AtomicBoolean secondStarted = new AtomicBoolean();
        CompletableFuture<Void> first = enqueue(player, () -> firstWrite);
        CompletableFuture<Void> second = enqueue(player, () -> {
            secondStarted.set(true);
            return CompletableFuture.completedFuture(null);
        });
        assertFalse(secondStarted.get());
        assertFalse(second.isDone());
        firstWrite.complete(null);
        assertTrue(first.isDone());
        assertTrue(secondStarted.get());
        assertTrue(second.isDone());
        assertFalse(second.isCompletedExceptionally());
    }

    @Test
    void aggregateTrackingMustNotBlockItsOwnChildSave() throws Exception {
        UUID player = UUID.randomUUID();
        CompletableFuture<Void> aggregate = new CompletableFuture<>();
        call("trackPendingSave", new Class<?>[]{UUID.class, CompletableFuture.class}, player, aggregate);
        AtomicBoolean started = new AtomicBoolean();
        CompletableFuture<Void> child = enqueue(player, () -> {
            started.set(true);
            return CompletableFuture.completedFuture(null);
        });
        child.whenComplete((ignored, error) -> {
            if (error == null) aggregate.complete(null);
            else aggregate.completeExceptionally(error);
        });
        try {
            assertTrue(started.get(), "A restore aggregate waits for its child, so the child must not wait for the aggregate");
            assertTrue(aggregate.isDone());
        } finally {
            // Break the known cycle after recording the assertion; never hang the runner.
            aggregate.complete(null);
        }
    }

    @Test
    void shutdownMustWaitForPendingSavesEvenWithAnEmptyCache() throws Exception {
        CompletableFuture<Void> pending = new CompletableFuture<>();
        enqueue(UUID.randomUUID(), () -> pending);
        CompletableFuture<?> shutdown = (CompletableFuture<?>) call("shutdownSave", new Class<?>[0]);
        try {
            assertFalse(shutdown.isDone(), "Quit removes cache entries before its database save finishes");
        } finally {
            pending.complete(null);
        }
    }

    @Test
    void snapshotMustNotShareMutableItemsWithTheLiveInventory() throws Exception {
        ItemStack live = mock(ItemStack.class);
        ItemStack copy = mock(ItemStack.class);
        when(live.getType()).thenReturn(Material.STONE);
        when(live.clone()).thenReturn(copy);
        ItemStack[] snapshot = (ItemStack[]) call("cleanInventoryForSave",
                new Class<?>[]{ItemStack[].class}, (Object) new ItemStack[]{live});
        assertNotNull(snapshot[0]);
        assertNotSame(live, snapshot[0]);
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Void> enqueue(UUID player, Supplier<CompletableFuture<Void>> operation) throws Exception {
        return (CompletableFuture<Void>) call("enqueueSave",
                new Class<?>[]{UUID.class, Supplier.class}, player, operation);
    }

    private Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = EnderChestManager.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(manager, args);
    }

    private void set(String name, Object value) throws Exception {
        Field field = EnderChestManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(manager, value);
    }
}
