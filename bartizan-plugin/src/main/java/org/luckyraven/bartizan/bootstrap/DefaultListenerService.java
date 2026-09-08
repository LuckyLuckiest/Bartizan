package org.luckyraven.bartizan.bootstrap;

import org.bukkit.plugin.java.JavaPlugin;
import org.luckyraven.keystone.bean.SettingsLookup;
import org.luckyraven.keystone.bean.autowire.DependencyContainer;
import org.luckyraven.keystone.bean.listener.ListenerService;

/**
 * Keystone ships only the abstract {@link ListenerService} base — every consumer plugin writes this one-method
 * concrete subclass itself (bartizan.md §2 B8; shape verbatim from {@code oriel-plugin-skeleton.md} §2).
 */
public final class DefaultListenerService extends ListenerService {

	private final SettingsLookup settings;

	public DefaultListenerService(JavaPlugin plugin, DependencyContainer dependencyContainer, SettingsLookup settings) {
		super(plugin, dependencyContainer);
		this.settings = settings;
	}

	@Override
	public boolean invokeMethod(String condition) {
		return settings.isEnabled(condition);
	}

}
