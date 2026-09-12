package org.maiminhdung.customenderchest.support;

import org.maiminhdung.customenderchest.ConfigHandler;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.utils.DebugLogger;
import java.lang.reflect.Field;
import java.util.logging.Logger;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Minimal plugin services, not a running Paper/Folia server. */
public final class TestPluginContext implements AutoCloseable {
    private final Field instanceField;
    private final Object previous;

    public TestPluginContext() throws ReflectiveOperationException {
        EnderChest plugin = mock(EnderChest.class);
        ConfigHandler config = mock(ConfigHandler.class);
        when(config.getString("storage.table_name", "custom_enderchests"))
                .thenReturn("custom_enderchests");
        when(plugin.config()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CEC-acceptance-test"));
        when(plugin.getDebugLogger()).thenReturn(mock(DebugLogger.class));
        instanceField = EnderChest.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        previous = instanceField.get(null);
        instanceField.set(null, plugin);
    }

    @Override
    public void close() throws IllegalAccessException {
        instanceField.set(null, previous);
    }
}
