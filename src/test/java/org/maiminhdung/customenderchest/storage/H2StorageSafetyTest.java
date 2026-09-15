package org.maiminhdung.customenderchest.storage;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.maiminhdung.customenderchest.data.ItemSerializer;
import org.maiminhdung.customenderchest.storage.impl.H2Storage;
import org.maiminhdung.customenderchest.support.TestPluginContext;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/** Real H2 files and production SQL/decoder; plugin services alone are mocked. */
@Timeout(15)
class H2StorageSafetyTest {
    @TempDir Path directory;
    private TestPluginContext context;
    private ExecutorService executor;
    private StorageManager manager;
    private H2Storage storage;
    private String jdbcUrl;

    @BeforeEach
    void setup() throws Exception {
        context = new TestPluginContext();
        jdbcUrl = "jdbc:h2:" + directory.resolve("acceptance").toAbsolutePath();
        executor = Executors.newSingleThreadExecutor();
        manager = mock(StorageManager.class);
        when(manager.getIoExecutor()).thenReturn(executor);
        when(manager.getConnection()).thenAnswer(ignored -> connect());
        storage = new H2Storage(manager);
        storage.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (executor != null) {
                executor.shutdown();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    fail("Test DB worker did not drain");
                }
            }
        } finally {
            if (context != null) context.close();
        }
    }

    @Test
    void missingPlayerIsDifferentFromAnExistingEmptyChest() throws Exception {
        UUID player = UUID.randomUUID();
        assertNull(await(storage.loadEnderChest(player)));
        await(storage.saveEnderChest(player, "TestPlayer", 27, new ItemStack[27]));
        assertEquals(27, await(storage.loadEnderChest(player)).length);
        assertEquals(27, await(storage.loadEnderChestSize(player)).intValue());
    }

    @Test
    void mainReadMustNotRewritePayloadOrTimestamp() throws Exception {
        UUID player = UUID.randomUUID();
        String original = ItemSerializer.toBase64(new ItemStack[27]);
        insertMain(player, original);
        await(storage.loadEnderChest(player));
        try (Connection conn = connect(); var ps = conn.prepareStatement(
                "SELECT chest_data, last_seen, chest_size FROM custom_enderchests WHERE player_uuid = ?")) {
            ps.setString(1, player.toString());
            try (var rows = ps.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(original, rows.getString(1));
                assertEquals(123L, rows.getLong(2));
                assertEquals(27, rows.getInt(3));
            }
        }
    }

    @Test
    void corruptMainMustFailAndLeaveRawDataUntouched() throws Exception {
        UUID player = UUID.randomUUID();
        insertMain(player, "%corrupt-main%");
        assertThrows(ExecutionException.class, () -> await(storage.loadEnderChest(player)));
        assertEquals("%corrupt-main%", raw(player, false));
    }

    @Test
    void oneCorruptPlayerDoesNotPreventAnotherPlayerLoading() throws Exception {
        UUID corrupt = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        insertMain(corrupt, "%corrupt-main%");
        insertMain(healthy, ItemSerializer.toBase64(new ItemStack[27]));
        assertThrows(ExecutionException.class, () -> await(storage.loadEnderChest(corrupt)));
        assertEquals(27, await(storage.loadEnderChest(healthy)).length);
    }

    @Test
    void overflowLargerThan256SlotsSurvivesDatabaseReopen() throws Exception {
        UUID player = UUID.randomUUID();
        await(storage.saveOverflowItems(player, new ItemStack[257]));
        // All connections closed; reopen the same real file through production storage.
        H2Storage reopened = new H2Storage(manager);
        assertEquals(257, await(reopened.loadOverflowItems(player)).length);
    }

    @Test
    void sqlLoadFailureMustNotMasqueradeAsANewPlayer() throws Exception {
        doThrow(new SQLException("Injected DB outage")).when(manager).getConnection();
        assertThrows(ExecutionException.class,
                () -> await(storage.loadEnderChest(UUID.randomUUID())));
    }

    // These acceptance requirements intentionally remain enabled and expose known defects.
    @Test
    void corruptOverflowMustFailRatherThanReturnAnEmptyArray() throws Exception {
        UUID player = UUID.randomUUID();
        try (Connection conn = connect(); var ps = conn.prepareStatement(
                "INSERT INTO custom_enderchests_overflow VALUES (?, ?, ?)")) {
            ps.setString(1, player.toString());
            ps.setString(2, "%corrupt-overflow%");
            ps.setLong(3, 123L);
            ps.executeUpdate();
        }
        assertAll(
                () -> assertThrows(ExecutionException.class, () -> await(storage.loadOverflowItems(player))),
                () -> assertEquals("%corrupt-overflow%", raw(player, true)));
    }

    @Test
    void failedOverflowDeleteMustNotReportSuccess() throws Exception {
        doThrow(new SQLException("Injected DB outage")).when(manager).getConnection();
        assertThrows(ExecutionException.class,
                () -> await(storage.clearOverflowItems(UUID.randomUUID())));
    }

    @Test
    void failedMainDeleteMustNotReportSuccess() throws Exception {
        doThrow(new SQLException("Injected DB outage")).when(manager).getConnection();
        assertThrows(ExecutionException.class,
                () -> await(storage.deleteEnderChest(UUID.randomUUID())));
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, "sa", "");
    }

    private void insertMain(UUID player, String payload) throws SQLException {
        try (Connection conn = connect(); var ps = conn.prepareStatement(
                "INSERT INTO custom_enderchests VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, player.toString());
            ps.setString(2, "TestPlayer");
            ps.setInt(3, 27);
            ps.setString(4, payload);
            ps.setLong(5, 123L);
            ps.executeUpdate();
        }
    }

    private String raw(UUID player, boolean overflow) throws SQLException {
        String sql = overflow
                ? "SELECT overflow_data FROM custom_enderchests_overflow WHERE player_uuid = ?"
                : "SELECT chest_data FROM custom_enderchests WHERE player_uuid = ?";
        try (Connection conn = connect(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            try (var rows = ps.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }
}
