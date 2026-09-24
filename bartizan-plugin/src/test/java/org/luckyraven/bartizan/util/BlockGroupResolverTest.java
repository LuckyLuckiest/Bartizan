package org.luckyraven.bartizan.util;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BZ-RT-09: the single-material fallback used a raw {@code Material.valueOf(upperName)}, so a name that only
 * exists under an older/renamed spelling silently resolved to an empty set instead of the block — CLAUDE.md
 * requires version-drifting enums to resolve through XSeries, never raw {@code valueOf}. {@code STAINED_CLAY} is
 * the pre-1.13 flat name for the (now per-color) terracotta blocks: {@code Material.valueOf} throws on it, but
 * XMaterial's alias table still resolves it.
 */
@DisplayName("BlockGroupResolver.resolve — XMaterial fallback for a renamed single material")
class BlockGroupResolverTest {

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		BukkitRegistryFixture.install();
	}

	@Test
	@DisplayName("a current material name still resolves directly")
	void currentMaterialName_resolves() {
		assertEquals(Set.of(Material.STONE), BlockGroupResolver.resolve("STONE"));
	}

	@Test
	@DisplayName("BZ-RT-09: a renamed/legacy material name resolves through XMaterial instead of coming back empty")
	void legacyMaterialName_resolvesThroughXMaterial() {
		Set<Material> resolved = BlockGroupResolver.resolve("STAINED_CLAY");

		assertEquals(Set.of(Material.BLACK_TERRACOTTA), resolved);
	}

	@Test
	@DisplayName("a genuinely unknown name still resolves to an empty set")
	void unknownName_resolvesEmpty() {
		assertTrue(BlockGroupResolver.resolve("THIS_IS_NOT_A_MATERIAL").isEmpty());
	}

}
