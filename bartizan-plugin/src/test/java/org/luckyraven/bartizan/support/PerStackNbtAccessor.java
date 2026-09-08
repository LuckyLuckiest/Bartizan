package org.luckyraven.bartizan.support;

import org.bukkit.inventory.ItemStack;
import org.luckyraven.keystone.item.nbt.ItemNbtAccessor;
import org.luckyraven.keystone.item.nbt.NbtType;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * An {@link ItemNbtAccessor} that stores tags <em>per stack</em>, keyed by object identity.
 *
 * <p>keystone-testkit's {@code RecordingNbtAccessor} keeps one flat {@code Map<String, Object>} shared by
 * every stack, so all stacks read back the same value for a given tag name — harmless when every stack under
 * test carries the same value for a tag, but wrong the moment two stacks must carry <em>different</em> values
 * for the same tag name (e.g. comparing two independently-built weapon items by their {@code weapon} tag).
 *
 * <p>Copied from Gangland's {@code gangland-item} test support (bartizan.md N-gap follow-up; Keystone follow-up
 * 1.9.1 tracks promoting an identity-keyed accessor into {@code keystone-testkit} itself) — package changed only,
 * no gangland/bartizan-specific logic.
 *
 * <p>Identity, not {@code equals}, is the key on purpose: two freshly built items of the same material compare
 * equal under {@link ItemStack#equals(Object)} before their tags diverge, so an equality map would merge them.
 */
public final class PerStackNbtAccessor implements ItemNbtAccessor {

	private final Map<ItemStack, Map<String, Object>> tags = new IdentityHashMap<>();

	/**
	 * Stamps a tag directly, without going through {@code ItemBuilder} — handy for building a fixture stack
	 * that pretends to be some other plugin's item.
	 *
	 * @param stack the stack to tag
	 * @param key   the tag name
	 * @param value the tag value
	 */
	public void put(ItemStack stack, String key, Object value) {
		tags.computeIfAbsent(stack, ignored -> new HashMap<>()).put(key, value);
	}

	@Override
	public boolean isAvailable() {
		return true;
	}

	@Override
	public boolean has(ItemStack stack, String key) {
		Map<String, Object> stackTags = tags.get(stack);

		return stackTags != null && stackTags.containsKey(key);
	}

	@Override
	public Object get(ItemStack stack, String key) {
		Map<String, Object> stackTags = tags.get(stack);

		return stackTags == null ? null : stackTags.get(key);
	}

	@Override
	public String getString(ItemStack stack, String key) {
		Object value = get(stack, key);

		return value == null ? null : String.valueOf(value);
	}

	@Override
	public int getInt(ItemStack stack, String key) {
		Object value = get(stack, key);

		if (value instanceof Number number) {
			return number.intValue();
		}

		return 0;
	}

	@Override
	public void set(ItemStack stack, String key, NbtType type, Object value) {
		put(stack, key, value);
	}

	@Override
	public void remove(ItemStack stack, String key) {
		Map<String, Object> stackTags = tags.get(stack);

		if (stackTags != null) {
			stackTags.remove(key);
		}
	}

	@Override
	public String describe(ItemStack stack) {
		Map<String, Object> stackTags = tags.get(stack);

		return stackTags == null ? "{}" : stackTags.toString();
	}

}
