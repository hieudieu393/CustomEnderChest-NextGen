package org.maiminhdung.customenderchest.data;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.maiminhdung.customenderchest.support.TestPluginContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Timeout(10)
class ItemSerializerSafetyTest {
    private TestPluginContext context;

    @BeforeEach
    void setup() throws Exception { context = new TestPluginContext(); }

    @AfterEach
    void tearDown() throws Exception { if (context != null) context.close(); }

    @Test
    void invalidBase64MustFailInsteadOfBecomingEmpty() {
        assertThrows(IOException.class, () -> ItemSerializer.fromBase64("%invalid-base64%"));
    }

    @Test
    void truncatedHeaderMustFailInsteadOfBecomingEmpty() {
        assertThrows(IOException.class, () -> ItemSerializer.fromBase64("AQID"));
    }

    @Test
    void legacyEmptyValueRemainsReadable() throws Exception {
        assertEquals(0, ItemSerializer.fromBase64("").length);
        assertEquals(0, ItemSerializer.fromBase64(null).length);
        assertEquals("", ItemSerializer.toBase64(new ItemStack[0]));
    }

    @ParameterizedTest
    @ValueSource(ints = {9, 27, 54, 257, 4096})
    void emptySlotArraysRoundTripIncludingLargeOverflow(int slots) throws Exception {
        ItemStack[] expected = new ItemStack[slots];
        assertArrayEquals(expected, ItemSerializer.fromBase64(ItemSerializer.toBase64(expected)));
    }

    @Test
    void writerRejectsTooManySlots() {
        assertThrows(IOException.class, () -> ItemSerializer.toBase64(new ItemStack[4097]));
    }

    @Test
    void truncatedSlotDataMustFailInsteadOfBecomingEmpty() {
        String payload = Base64.getEncoder().encodeToString(ByteBuffer.allocate(4).putInt(9).array());
        assertThrows(IOException.class, () -> ItemSerializer.fromBase64(payload));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1000001})
    void invalidItemLengthMustRejectWholeChest(int bytes) {
        byte[] payload = ByteBuffer.allocate(9).putInt(1).put((byte) 1).putInt(bytes).array();
        assertThrows(IOException.class,
                () -> ItemSerializer.fromBase64(Base64.getEncoder().encodeToString(payload)));
    }

    // Acceptance requirement exposing a known writer/reader mismatch; NOT disabled.
    @Test
    void writerMustRejectAnItemLargerThanTheReadersLimit() {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.STONE);
        when(item.serializeAsBytes()).thenReturn(new byte[1_000_001]);
        assertThrows(IOException.class, () -> ItemSerializer.toBase64(new ItemStack[]{item}));
    }
}
