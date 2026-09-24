package org.luckyraven.bartizan.api.wearable;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.item.nbt.ItemNbtAccessor;
import org.luckyraven.keystone.item.nbt.NbtBridge;
import org.luckyraven.keystone.item.nbt.NbtType;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BZ-WE-05: {@code buildItem} used to stamp a {@code wr_base} tag (the piece's {@code Base_Damage_Reduction}) and
 * one {@code wt_<trait>} tag per trait onto the built item's NBT, but no reader anywhere in either module ever
 * looked them up again - {@code WearableItemSerializer}/{@code WearableRefresher}/{@code BartizanItemPredicates}
 * all key only off {@link Wearable#NBT_KEY} and resolve traits/base reduction live from the registry. Write-only
 * data that silently goes stale the moment {@code wearables.yml} is edited. Pins that {@code buildItem} no longer
 * writes either tag, while the actual identity tag it's read back by ({@link Wearable#NBT_KEY}) still is.
 *
 * <p>Minimal in-memory {@link ItemNbtAccessor} defined locally rather than pulling in a test dependency: both
 * {@code ItemNbtAccessor} and {@code NbtBridge} already ship inside {@code keystone-item}, which {@code bartizan-api}
 * already depends on to build items at all.
 */
@DisplayName("Wearable.buildItem - NBT (BZ-WE-05)")
class WearableBuildItemNbtTest {

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		BukkitRegistryFixture.install();
	}

	@AfterEach
	void tearDown() {
		NbtBridge.reset();
	}

	/** Identity-keyed, same reasoning as {@code bartizan-plugin}'s {@code PerStackNbtAccessor}. */
	private static final class InMemoryNbtAccessor implements ItemNbtAccessor {

		private final Map<ItemStack, Map<String, Object>> tags = new IdentityHashMap<>();

		@Override
		public boolean isAvailable() {
			return true;
		}

		@Override
		public boolean has(ItemStack stack, String tag) {
			Map<String, Object> stackTags = tags.get(stack);
			return stackTags != null && stackTags.containsKey(tag);
		}

		@Override
		@Nullable
		public Object get(ItemStack stack, String tag) {
			Map<String, Object> stackTags = tags.get(stack);
			return stackTags == null ? null : stackTags.get(tag);
		}

		@Override
		@Nullable
		public String getString(ItemStack stack, String tag) {
			Object value = get(stack, tag);
			return value == null ? null : String.valueOf(value);
		}

		@Override
		public int getInt(ItemStack stack, String tag) {
			Object value = get(stack, tag);
			return value instanceof Number number ? number.intValue() : 0;
		}

		@Override
		public void set(ItemStack stack, String tag, NbtType type, @Nullable Object value) {
			tags.computeIfAbsent(stack, ignored -> new HashMap<>()).put(tag, value);
		}

		@Override
		public void remove(ItemStack stack, String tag) {
			Map<String, Object> stackTags = tags.get(stack);
			if (stackTags != null) stackTags.remove(tag);
		}

		@Override
		@Nullable
		public String describe(ItemStack stack) {
			Map<String, Object> stackTags = tags.get(stack);
			return stackTags == null ? "{}" : stackTags.toString();
		}
	}

	@Test
	@DisplayName("buildItem stamps the wearable key but no wr_base / wt_<trait> tags")
	void buildItem_doesNotStampDeadTraitOrBaseReductionTags() {
		NbtBridge.install(new InMemoryNbtAccessor());

		Wearable wearable = Wearable.builder()
		                            .material(Material.IRON_CHESTPLATE)
		                            .wearableKey("test_vest")
		                            .baseDamageReduction(0.10)
		                            .traits(Map.of("reinforced", 2))
		                            .temporary(false)
		                            .build();

		ItemStack item = wearable.buildItem();

		assertTrue(new ItemBuilder(item).hasNBTTag(Wearable.NBT_KEY), "the identity tag must still be stamped");
		assertFalse(new ItemBuilder(item).hasNBTTag("wr_base"), "base-reduction NBT is write-only dead data");
		assertFalse(new ItemBuilder(item).hasNBTTag("wt_reinforced"), "per-trait NBT is write-only dead data");
	}

}
