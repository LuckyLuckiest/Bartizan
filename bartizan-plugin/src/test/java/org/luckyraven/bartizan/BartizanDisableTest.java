package org.luckyraven.bartizan;

import org.bukkit.Server;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.keystone.nms.PacketBridge;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-NU-01: {@code PacketBridge.reset()} is global on a Keystone older than 1.11.2 ({@code adapter =
 * NoOpAdapter.INSTANCE}, one field shared by every Keystone-powered plugin), so {@code Bartizan.onDisable()} calls it
 * only where the probe finds the 1.11.2+ per-classloader install list. Bartizan compiles and tests against Keystone
 * 1.13.0 since 0.6.0, so on this classpath the probe reads true and onDisable removes its own install - skipping it
 * there would pin the dead PluginClassLoader on every disable/enable.
 */
class BartizanDisableTest {

	@Test
	@DisplayName("the owner-scoped-reset probe reads true on the compiled Keystone 1.13.0")
	void packetBridgeProbe_trueOnKeystone113() {
		assertTrue(Bartizan.packetBridgeResetIsOwnerScoped());
	}

	@Test
	@DisplayName("onDisable resets its own owner-scoped PacketBridge install")
	void onDisableResetsOwnPacketBridgeInstall() {
		Bartizan bartizan = mock(Bartizan.class);
		doCallRealMethod().when(bartizan).onDisable();

		Server           server           = mock(Server.class);
		ServicesManager  servicesManager  = mock(ServicesManager.class);
		when(server.getServicesManager()).thenReturn(servicesManager);
		when(bartizan.getServer()).thenReturn(server);

		try (MockedStatic<PacketBridge> packetBridge = mockStatic(PacketBridge.class)) {
			bartizan.onDisable();

			packetBridge.verify(PacketBridge::reset, times(1));
		}

		verify(servicesManager).unregisterAll(bartizan);
	}

}
