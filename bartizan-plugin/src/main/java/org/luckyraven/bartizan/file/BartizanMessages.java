package org.luckyraven.bartizan.file;

import org.bukkit.configuration.file.YamlConfiguration;
import org.luckyraven.bartizan.util.BartizanChatUtil;
import org.luckyraven.keystone.message.MessageProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Bartizan's message catalogue — same shape as Gangland's {@code Messages} enum (bartizan.md §1.5), backed by
 * Keystone's {@link MessageProvider} through {@link org.luckyraven.keystone.persistence.message.LanguageLoader}.
 * 18 entries: 4 prefixes + 14 content members (the exhaustive list from bartizan.md §1.5 — nothing else is added).
 */
public enum BartizanMessages {

	// prefixes
	PREFIX("Normal.Prefix", Type.OTHER),
	COMMAND_PREFIX("Commands.Prefix", Type.OTHER),
	ERROR_PREFIX("Errors.Prefix", Type.OTHER),
	INFORMATION_PREFIX("Information.Prefix", Type.OTHER),

	// commands - argument framework
	ARGUMENTS_MISSING("Commands.Syntax.Missing_Arguments", Type.COMMAND),
	MUST_BE_NUMBERS("Errors.Must_Be_Numbers", Type.ERROR),

	// commands - weapon
	RECEIVED_WEAPON("Commands.Weapon.Received", Type.COMMAND),
	INVALID_WEAPON("Errors.Not_Valid_Weapon", Type.ERROR),
	WEAPON_LIST_HEADER("Commands.Weapon.List_Header", Type.COMMAND),

	// commands - ammo
	RECEIVED_AMMO("Commands.Ammo.Received", Type.COMMAND),
	INVALID_AMMO("Errors.Not_Valid_Ammo", Type.ERROR),
	AMMO_LIST_HEADER("Commands.Ammo.List_Header", Type.COMMAND),

	// commands - wearable
	WEARABLE_GAVE("Commands.Wearable.Gave", Type.COMMAND),
	WEARABLE_LIST_HEADER("Commands.Wearable.List_Header", Type.COMMAND),
	WEARABLE_INVALID("Errors.Wearable.Invalid", Type.PREFIX),
	WEARABLE_NOT_REGISTERED("Errors.Wearable.Not_Registered", Type.PREFIX),
	WEARABLE_NOT_WEARABLE("Errors.Wearable.Not_Wearable", Type.PREFIX),

	// death
	DEAD_USING_WEAPON("Death.Weapon", Type.OTHER, true),
	;

	private static MessageProvider provider;

	private final String path;
	private final Type   type;

	private boolean isList;

	BartizanMessages(String path, Type type) {
		this.path   = path;
		this.type   = type;
		this.isList = false;
	}

	BartizanMessages(String path, Type type, boolean isList) {
		this(path, type);
		this.isList = isList;
	}

	/**
	 * Single static seam wired once at startup by {@code FilesConfig.languageLoader(...)}. The provider is a proper
	 * bean, so other code paths that want typed access can inject {@link MessageProvider} directly instead of going
	 * through the enum.
	 */
	public static void init(MessageProvider messageProvider) {
		provider = messageProvider;
	}

	/**
	 * Walks every declared enum entry and returns the paths that are absent from {@code yaml}. Wired as the
	 * {@code missingKeys} hook on {@code LanguageLoader} so a typo'd/missing key is surfaced by name at startup.
	 */
	public static List<String> findMissingPaths(YamlConfiguration yaml) {
		List<String> missing = new ArrayList<>();

		for (BartizanMessages entry : values()) {
			if (!yaml.contains(entry.path)) missing.add(entry.path);
		}

		return missing;
	}

	@Override
	public String toString() {
		return toString(type);
	}

	public String toString(Type type) {
		String data;

		if (isList) data = convertFromList(provider.getStringList(path));
		else data = provider.getString(path);

		if (data == null) return "<missing: " + path + ">";

		return getValue(type, data);
	}

	public List<String> toStringList() {
		List<String> list = provider.getStringList(path);

		list.replaceAll(data -> getValue(type, data));

		return list;
	}

	private String getValue(Type type, String data) {
		String value;

		switch (type) {
			case PREFIX -> value = BartizanChatUtil.prefixMessage(data);
			case COMMAND -> value = BartizanChatUtil.commandMessage(data);
			case ERROR -> value = BartizanChatUtil.errorMessage(data);
			case INFORMATION -> value = BartizanChatUtil.informationMessage(data);
			case OTHER -> value = BartizanChatUtil.color(data);
			default -> value = data;
		}

		return value;
	}

	private String convertFromList(List<String> data) {
		StringBuilder builder = new StringBuilder();

		for (int i = 0; i < data.size(); i++) {
			builder.append(data.get(i));

			if (i < data.size() - 1) builder.append("\n");
		}

		return builder.toString();
	}

	public enum Type {

		PREFIX,
		COMMAND,
		ERROR,
		INFORMATION,
		OTHER,
		NO_CHANGE

	}

}
