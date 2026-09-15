package org.luckyraven.bartizan.api.weapon.recoil;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.RecoilData;
import org.luckyraven.bartizan.api.weapon.dto.ScopeData;
import org.luckyraven.keystone.nms.PacketAdapter;
import org.luckyraven.keystone.nms.PacketBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins bartizan.md {@code §1.6(1)}: {@code RecoilManager.recoil(...)} must call
 * {@code PacketBridge.adapter().relativeCameraRotation(player, -yaw + 1, pitch - 1)} - the exact transform every
 * NMS {@code Recoil_1_xx_Ry} adapter and the {@code RecoilCompatibility} Bukkit fallback applied before the split
 * - plus the sneak / sneak+scoped dampening and the recoil-pattern index advance.
 *
 * <p><b>Divisor note:</b> reading {@code RecoilManager.applyDefaultRecoil} / {@code applyRecoil} directly (source
 * of truth, not the checklist's parenthetical shorthand), sneaking+scoped divides the base amount by 2 and
 * sneaking-without-scope divides it by 4. This test pins that real arithmetic.
 */
class RecoilManagerTest {

	private CapturingPacketAdapter adapter;
	private Player                 player;

	@BeforeEach
	void setUp() {
		adapter = new CapturingPacketAdapter();
		PacketBridge.install(adapter);
		player = mock(Player.class);
	}

	@AfterEach
	void tearDown() {
		PacketBridge.reset();
	}

	@Test
	void defaultRecoil_notSneaking_usesFullAmount() {
		when(player.isSneaking()).thenReturn(false);
		Weapon        weapon  = weaponWithRecoil(4.0, null);
		RecoilManager manager = new RecoilManager(weapon);

		manager.applyRecoil(player);

		assertRotation(-4f + 1, 4f - 1);
	}

	@Test
	void defaultRecoil_sneakingNotScoped_quartersAmount() {
		when(player.isSneaking()).thenReturn(true);
		Weapon        weapon  = weaponWithRecoil(4.0, false);
		RecoilManager manager = new RecoilManager(weapon);

		manager.applyRecoil(player);

		float expected = 4f / 4;
		assertRotation(-expected + 1, expected - 1);
	}

	@Test
	void defaultRecoil_sneakingAndScoped_halvesAmount() {
		when(player.isSneaking()).thenReturn(true);
		Weapon        weapon  = weaponWithRecoil(4.0, true);
		RecoilManager manager = new RecoilManager(weapon);

		manager.applyRecoil(player);

		float expected = 4f / 2;
		assertRotation(-expected + 1, expected - 1);
	}

	@Test
	void patternRecoil_advancesIndexAndWrapsAround() {
		when(player.isSneaking()).thenReturn(false);
		List<String[]> pattern = new ArrayList<>();
		pattern.add(new String[]{"2.0", "1.0"});
		pattern.add(new String[]{"3.0", "1.5"});
		Weapon        weapon  = weaponWithPattern(pattern);
		RecoilManager manager = new RecoilManager(weapon);

		manager.applyRecoil(player);
		assertRotation(-2f + 1, 1f - 1);

		manager.applyRecoil(player);
		assertRotation(-3f + 1, 1.5f - 1);

		manager.applyRecoil(player); // wraps back to pattern index 0
		assertRotation(-2f + 1, 1f - 1);
	}

	@Test
	@DisplayName("Recoil.Random: gaussian yaw/pitch from the injected Random, not sneaking")
	void randomRecoil_notSneaking_usesGaussianMeanAndVariance() {
		when(player.isSneaking()).thenReturn(false);
		Weapon weapon = weaponWithRandom(new RecoilData.RecoilRandom(2.0, 1.0, 4.0, 1.0), null);
		RecoilManager manager = new RecoilManager(weapon, new FixedGaussianRandom(1.0));

		manager.applyRecoil(player);

		// nextGaussian() pinned to 1.0 -> Variance_X/Y are used directly as sigma (not sqrt'd) -> yaw = 2.0 +
		// 1.0*4.0 = 6.0; pitch = 1.0 + 1.0*1.0 = 2.0
		assertRotation(-6f + 1, 2f - 1);
	}

	@Test
	@DisplayName("Recoil.Random: sneaking+scoped halves the gaussian result, same as the pattern branch")
	void randomRecoil_sneakingAndScoped_halvesAmount() {
		when(player.isSneaking()).thenReturn(true);
		Weapon weapon = weaponWithRandom(new RecoilData.RecoilRandom(2.0, 1.0, 4.0, 1.0), true);
		RecoilManager manager = new RecoilManager(weapon, new FixedGaussianRandom(1.0));

		manager.applyRecoil(player);

		// Same sigma-not-sqrt gaussian as the previous test (yaw 6.0, pitch 2.0) before the sneak+scoped halving.
		float expectedYaw   = 6f / 2;
		float expectedPitch = 2f / 2;
		assertRotation(-expectedYaw + 1, expectedPitch - 1);
	}

	@Test
	@DisplayName("Recoil.Random takes precedence over Recoil.Pattern when both are configured")
	void randomRecoil_takesPrecedenceOverPattern() {
		when(player.isSneaking()).thenReturn(false);
		Weapon         weapon         = mock(Weapon.class);
		RecoilData     data           = new RecoilData();
		List<String[]> mustNotBeRead = new ArrayList<>();
		mustNotBeRead.add(new String[]{"99", "99"});
		data.setPattern(mustNotBeRead);
		data.setRandom(new RecoilData.RecoilRandom(2.0, 1.0, 0.0, 0.0));
		when(weapon.getRecoilData()).thenReturn(data);
		when(weapon.getScopeData()).thenReturn(null);
		RecoilManager manager = new RecoilManager(weapon, new FixedGaussianRandom(0.0));

		manager.applyRecoil(player);

		assertRotation(-2f + 1, 1f - 1);
	}

	private void assertRotation(float expectedYaw, float expectedPitch) {
		assertEquals(player, adapter.lastViewer);
		assertEquals(expectedYaw, adapter.lastDeltaYaw, 0.0001f);
		assertEquals(expectedPitch, adapter.lastDeltaPitch, 0.0001f);
	}

	private Weapon weaponWithRecoil(double amount, Boolean scoped) {
		Weapon     weapon     = mock(Weapon.class);
		RecoilData recoilData = new RecoilData();
		recoilData.setAmount(amount);
		recoilData.setPattern(new ArrayList<>());
		when(weapon.getRecoilData()).thenReturn(recoilData);

		if (scoped == null) {
			when(weapon.getScopeData()).thenReturn(null);
		} else {
			ScopeData scopeData = new ScopeData();
			scopeData.setScoped(scoped);
			when(weapon.getScopeData()).thenReturn(scopeData);
		}

		return weapon;
	}

	private Weapon weaponWithRandom(RecoilData.RecoilRandom randomConfig, Boolean scoped) {
		Weapon     weapon     = mock(Weapon.class);
		RecoilData recoilData = new RecoilData();
		recoilData.setRandom(randomConfig);
		when(weapon.getRecoilData()).thenReturn(recoilData);

		if (scoped == null) {
			when(weapon.getScopeData()).thenReturn(null);
		} else {
			ScopeData scopeData = new ScopeData();
			scopeData.setScoped(scoped);
			when(weapon.getScopeData()).thenReturn(scopeData);
		}

		return weapon;
	}

	private Weapon weaponWithPattern(List<String[]> pattern) {
		Weapon     weapon     = mock(Weapon.class);
		RecoilData recoilData = new RecoilData();
		recoilData.setAmount(99); // must not be read on the pattern path
		recoilData.setPattern(pattern);
		when(weapon.getRecoilData()).thenReturn(recoilData);
		when(weapon.getScopeData()).thenReturn(null);
		return weapon;
	}

	/**
	 * Fake {@link Random} whose {@code nextGaussian()} always returns the same fixed value, so the
	 * {@code Recoil.Random} branch's arithmetic is fully deterministic without depending on {@link Random}'s
	 * actual (if reproducible) algorithm.
	 */
	private static final class FixedGaussianRandom extends Random {

		private final double value;

		FixedGaussianRandom(double value) {
			this.value = value;
		}

		@Override
		public double nextGaussian() {
			return value;
		}
	}

	private static final class CapturingPacketAdapter implements PacketAdapter {

		private Player lastViewer;
		private float  lastDeltaYaw;
		private float  lastDeltaPitch;

		@Override
		public void relativeCameraRotation(Player viewer, float deltaYaw, float deltaPitch) {
			this.lastViewer     = viewer;
			this.lastDeltaYaw   = deltaYaw;
			this.lastDeltaPitch = deltaPitch;
		}

		@Override
		public void updateInventoryTitle(Player viewer, String legacyColored) {
		}

		@Override
		public void resendInventoryContents(Player viewer, Inventory inventory) {
		}

		@Override
		public void resendCarriedItem(Player viewer) {
		}

		@Override
		public int openFakeWindow(Player viewer, FakeWindowType type, String legacyColoredTitle) {
			return -1;
		}

		@Override
		public void setProperty(Player viewer, int windowId, int property, int value) {
		}
	}
}
