package org.luckyraven.bartizan.bootstrap;

import lombok.CustomLog;
import lombok.Getter;
import org.bukkit.command.PluginCommand;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.keystone.bean.BeanFactory;
import org.luckyraven.keystone.bean.BeanLifecycle;
import org.luckyraven.keystone.bean.Phase;
import org.luckyraven.keystone.bean.SettingsLookup;
import org.luckyraven.keystone.bean.autowire.DependencyContainer;
import org.luckyraven.keystone.command.CommandManager;
import org.luckyraven.keystone.command.CommandTabCompleter;
import org.luckyraven.keystone.command.brigadier.BrigadierTabRegistrar;
import org.luckyraven.keystone.persistence.FileManager;
import org.luckyraven.keystone.persistence.repository.IRepository;
import org.luckyraven.keystone.persistence.repository.RepositoryRegistry;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Single root for Bartizan's wiring — the standalone-plugin twin of Gangland's {@code GanglandContext} with every
 * module concern removed (bartizan.md §2 B8): no {@code ModuleLoader}, no module scans, no {@code hostApi}, no
 * module loop in the repository-republish hook. Owns the only {@link DependencyContainer} and {@link BeanFactory}
 * that exist at runtime and runs the post-bootstrap listener + command scans.
 *
 * <p>The bootstrap pipeline mirrors {@code GanglandContext}'s: {@code KernelConfig} (KERNEL) produces every
 * bootstrap-critical singleton, {@link #bootstrap()} scans {@code org.luckyraven.bartizan.config} for
 * {@code @Configuration} classes and drives {@link BeanFactory#instantiate()} through
 * KERNEL → FILE → DATABASE → CONFIG → LIFECYCLE → LISTENER → COMMAND, then runs the listener and command scans.
 */
@CustomLog
public final class BartizanContext {

	private static final String CONFIG_PACKAGE   = "org.luckyraven.bartizan.config";
	private static final String LISTENER_PACKAGE = "org.luckyraven.bartizan";
	private static final String COMMAND_PACKAGE  = "org.luckyraven.bartizan.command";

	@Getter
	private final DependencyContainer container;
	@Getter
	private final BeanFactory         beanFactory;

	private final Bartizan bartizan;

	/** Repos already published into the container — guards the per-bean DATABASE hook from double-registering. */
	private final Set<IRepository<?>> publishedRepositories = Collections.newSetFromMap(new IdentityHashMap<>());

	public BartizanContext(Bartizan bartizan) {
		this.bartizan = bartizan;

		// Bartizan ships no @ConditionalOnSetting-gated beans today, so a trivial always-false lookup is enough
		// (same minimal shape Oriel uses, oriel-plugin-skeleton.md §2) rather than a dedicated impl class.
		SettingsLookup settings = key -> false;

		this.container   = new DependencyContainer();
		this.beanFactory = new BeanFactory(container, bartizan, settings);

		container.registerInstance(BartizanContext.class, this);
		container.registerInstance(DependencyContainer.class, container);
		container.registerInstance(org.bukkit.plugin.java.JavaPlugin.class, bartizan);
		container.registerInstance(Bartizan.class, bartizan);
		container.registerInstance(SettingsLookup.class, settings);
		container.registerInstance(BeanFactory.class, beanFactory);
	}

	/**
	 * Convenience accessor for legacy code that needs a bean by raw type.
	 */
	public <T> T get(Class<T> type) {
		return container.getInstance(type);
	}

	/**
	 * Runs the reload lifecycle on all beans implementing {@link BeanLifecycle}.
	 */
	public void reloadBeans() {
		beanFactory.reloadLifecycleBeans();
	}

	/**
	 * Runs graceful shutdown on all beans implementing {@link BeanLifecycle} in reverse topological order.
	 */
	public void shutdownBeans() {
		beanFactory.shutdownLifecycleBeans();
	}

	/**
	 * Drive the phased bean instantiation, then run the listener and command scans. Must be called exactly once.
	 */
	public void bootstrap() {
		// FILE phase: after each file-initializer bean is registered, run FileManager.initializeAll() so the file
		// is loaded before the next FILE-phase bean's @Bean method runs. FileManager is produced by KernelConfig in
		// the KERNEL phase, so it is guaranteed to be in the container.
		// NOTE (gate-GG review m3): this hook does NOT register FileInitializer beans - each FILE-phase bean calls
		// fileManager.registerInitializer(this) itself (FilesConfig). A new FILE bean that forgets that call is never
		// initialised and nothing warns; keep the pattern or move registration into this hook (FileManager keeps a
		// plain list, so never do both).
		beanFactory.setPhaseHook(Phase.FILE, beans -> {
			FileManager fm = container.getInstance(FileManager.class);
			if (fm != null) {
				fm.initializeAll();
			}
		});

		// DATABASE phase: after each database bean, find any RepositoryRegistry in the container and republish
		// every repository into the container by its concrete class. Idempotent via publishedRepositories identity
		// set.
		beanFactory.setPhaseHook(Phase.DATABASE, beans -> publishRepositoriesFromContainer());

		beanFactory.scan(CONFIG_PACKAGE);
		beanFactory.instantiate();

		runListenerPhase();
		runCommandPhase();
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void publishRepositoriesFromContainer() {
		RepositoryRegistry registry = container.getInstance(RepositoryRegistry.class);
		if (registry == null) {
			return;
		}
		for (IRepository<?> repo : registry.getAllRepositories()) {
			if (!publishedRepositories.add(repo)) {
				continue;
			}
			Class repoClass = repo.getClass();
			container.registerInstance(repoClass, repo);
		}
	}

	private void runListenerPhase() {
		DefaultListenerService listenerService = container.getInstance(DefaultListenerService.class);
		if (listenerService == null) {
			throw new IllegalStateException(
					this.getClass().getSimpleName() + ".bootstrap(): " + DefaultListenerService.class.getSimpleName() +
					" bean missing. Add a @Bean method that produces " + DefaultListenerService.class.getSimpleName() +
					" to a CONFIG-phase @Configuration class.");
		}
		listenerService.scanAndRegisterListeners(LISTENER_PACKAGE, bartizan);
		listenerService.registerEvents();
		log.debug("Listener phase complete: {} listener(s) registered", listenerService.getListeners().size());
	}

	private void runCommandPhase() {
		CommandManager commandManager = container.getInstance(CommandManager.class);
		if (commandManager == null) {
			throw new IllegalStateException(
					this.getClass().getSimpleName() + ".bootstrap(): " + CommandManager.class.getSimpleName() +
					" bean missing. Add a @Bean method that produces " + CommandManager.class.getSimpleName() +
					" to a CONFIG-phase @Configuration class.");
		}
		PluginCommand command = bartizan.getCommand(Bartizan.FULL_PREFIX);
		if (command == null) {
			log.warn("Plugin command /{} not declared in plugin.yml — skipping command bind", Bartizan.FULL_PREFIX);
			return;
		}
		// Deviation from the checklist's literal GanglandContext.java:233-277 port (recorded in bartizan.md §7,
		// task B8): Gangland calls ArgumentMessages.install(...) with Messages.COMMAND_NO_PERM /
		// ARGUMENT_NOT_IMPLEMENTED / ARGUMENTS_WRONG, but bartizan.md §1.5's exhaustive 18-entry BartizanMessages
		// table (4 prefixes + 14 content) carries no equivalents for those three argument-framework strings.
		// Rather than inventing three message keys the source-of-truth table does not define, Bartizan leaves the
		// argument tree on Keystone's built-in English defaults (ArgumentMessages' own DEFAULT_* suppliers).
		command.setExecutor(commandManager);
		commandManager.scanAndRegisterCommands(COMMAND_PACKAGE, bartizan.getClass().getClassLoader());

		CommandTabCompleter tabCompleter = new CommandTabCompleter(commandManager);
		command.setTabCompleter(tabCompleter);

		// Client-side Brigadier completion — safe on plain Spigot; on any failure it logs WARN and the server-side
		// tab completer above stays the only path.
		BrigadierTabRegistrar.registerIfSupported(bartizan, command, commandManager);

		log.debug("Command phase complete: {} command(s) registered", commandManager.commandView().size());
	}
	public DependencyContainer getContainer() {
		return container;
	}

}
