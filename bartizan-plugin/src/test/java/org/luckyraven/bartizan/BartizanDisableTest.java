package org.luckyraven.bartizan;

import org.bukkit.Server;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.keystone.nms.PacketBridge;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-NU-01: {@code PacketBridge.reset()} is test-only/global on the Keystone version Bartizan actually compiles
 * and ships against (1.9.0, pom.xml {@code keystone.version} - README.md/migration.md tell admins to deploy
 * {@code Keystone-1.9.0.jar}) - {@code adapter = NoOpAdapter.INSTANCE}, one field shared by every Keystone-powered
 * plugin on the shared classloader. {@code Bartizan.onDisable()} must never call it there, or disabling/reloading
 * Bartizan silently downgrades recoil/packet handling to a no-op for every OTHER plugin still running. (Keystone
 * 1.11.2+ scopes reset() per plugin classloader, and there onDisable does call it - not on this test classpath.)
 */
class BartizanDisableTest {

	@Test
	@DisplayName("the owner-scoped-reset probe reads false on the compiled Keystone 1.9.0")
	void packetBridgeProbe_falseOnKeystone190() {
		assertFalse(Bartizan.packetBridgeResetIsOwnerScoped());
	}

	@Test
	@DisplayName("onDisable never calls the server-global PacketBridge.reset()")
	void onDisableDoesNotResetPacketBridge() {
		Bartizan bartizan = mock(Bartizan.class);
		doCallRealMethod().when(bartizan).onDisable();

		Server           server           = mock(Server.class);
		ServicesManager  servicesManager  = mock(ServicesManager.class);
		when(server.getServicesManager()).thenReturn(servicesManager);
		when(bartizan.getServer()).thenReturn(server);

		try (MockedStatic<PacketBridge> packetBridge = mockStatic(PacketBridge.class)) {
			bartizan.onDisable();

			packetBridge.verify(PacketBridge::reset, never());
		}

		verify(servicesManager).unregisterAll(bartizan);
	}

}
