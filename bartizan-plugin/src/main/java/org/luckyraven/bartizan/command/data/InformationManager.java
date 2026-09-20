package org.luckyraven.bartizan.command.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.CustomLog;
import lombok.Getter;
import org.luckyraven.bartizan.Bartizan;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code /bartizan} help index: one flat map from command key ({@code weapon_give}) to usage + description.
 * Copy of Gangland's {@code command.data.InformationManager} (bartizan.md §1.4) with the module-merging loop
 * dropped — Bartizan ships no modules, so {@link #processCommands()} is the only load path: the plugin's own
 * bundled {@code commands.json}, read through the plugin classloader.
 */
@CustomLog
@Getter
public final class InformationManager {

	/** Jar-root resource name of the help fragment. */
	public static final String COMMANDS_RESOURCE = "commands.json";

	private final Map<String, CommandInformation> commands;

	public InformationManager() {
		commands = new HashMap<>();
	}

	/** Load Bartizan's bundled {@code commands.json} (read through the plugin classloader). */
	public void processCommands() {
		InputStream stream = Objects.requireNonNull(Bartizan.class.getResourceAsStream("/" + COMMANDS_RESOURCE),
		                                            COMMANDS_RESOURCE + " is missing from the plugin jar");
		int added = merge("bartizan", new InputStreamReader(stream, StandardCharsets.UTF_8));
		log.debug("Help index: {} command(s)", added);
	}

	public int merge(String source, byte[] json) {
		return merge(source, new InputStreamReader(new ByteArrayInputStream(json), StandardCharsets.UTF_8));
	}

	public int merge(String source, Reader json) {
		try {
			// Gson 2.8.0 API on purpose: Spigot 1.16.5 (the compile floor) bundles that Gson, where the static
			// JsonParser.parseReader (2.8.6+) and JsonObject.keySet (2.8.1+) do not exist yet.
			JsonElement root = new JsonParser().parse(json);
			if (!root.isJsonObject()) {
				log.warn("Help index: {} {} is not a JSON object; skipped", source, COMMANDS_RESOURCE);
				return 0;
			}

			JsonObject object = root.getAsJsonObject();
			int        added  = 0;
			for (Map.Entry<String, JsonElement> member : object.entrySet()) {
				String     key   = member.getKey();
				JsonObject entry = member.getValue().getAsJsonObject();
				commands.put(key, new CommandInformation(entry.get("usage").getAsString(),
				                                         entry.get("description").getAsString()));
				added++;
			}
			return added;
		} catch (RuntimeException exception) {
			log.warn("Help index: {} {} could not be parsed: {}", source, COMMANDS_RESOURCE, exception.getMessage());
			return 0;
		}
	}

}
