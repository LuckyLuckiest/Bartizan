package org.luckyraven.bartizan.bootstrap;

import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.command.WeaponCommand;
import org.luckyraven.bartizan.config.FilesConfig;
import org.luckyraven.bartizan.config.ItemConfig;
import org.luckyraven.bartizan.config.KernelConfig;
import org.luckyraven.bartizan.config.WiringConfig;
import org.luckyraven.bartizan.listener.player.WeaponQuitCleanupListener;
import org.luckyraven.keystone.bean.BeanFactory;
import org.luckyraven.keystone.bean.Configuration;
import org.luckyraven.keystone.bean.Phase;
import org.luckyraven.keystone.persistence.FileManager;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * bartizan.md §3 N-gap: replaces the deleted {@code WeaponModuleTest} (which asserted {@code ModuleRegistrations}
 * contents — Bartizan has no module descriptor, bartizan.md §1.1). Same intent expressed against the new
 * standalone-plugin bootstrap: {@link BartizanContext} scans {@code org.luckyraven.bartizan.config}, every
 * {@code @Configuration} class it finds there carries the phase §1.3 assigns it, the two named classes live
 * under the packages the context scans, and the FILE-phase hook it installs really calls
 * {@code FileManager.initializeAll()}.
 *
 * <p>Does not attempt a full live bean-graph bootstrap (that needs a real Bukkit server — see
 * {@code keystone-plugin}'s {@code BeanBootstrapScenarioTest} for what that costs in fixtures, and phase D's
 * console smoke for the real thing). {@link BartizanContext#bootstrap()} is still called for real by the tests
 * that need its effects — its {@code setPhaseHook(...)} call and its
 * {@code beanFactory.scan(CONFIG_PACKAGE)} call are the unconditional first two statements in the method, so
 * they run (and their effects are asserted below) regardless of whether a later phase throws for lack of a live
 * server, which — with only a bare mocked {@link Bartizan} — it usually will, past KERNEL/FILE.
 */
@DisplayName("BartizanContext — config-package scan, phase assignment and the FILE-phase hook")
class BartizanBootstrapTest {

	@TempDir
	Path tempDir;

	private Bartizan        bartizan;
	private BartizanContext context;

	@BeforeEach
	void setUp() {
		bartizan = mock(Bartizan.class);
		when(bartizan.getDataFolder()).thenReturn(tempDir.toFile());
		when(bartizan.getName()).thenReturn("Bartizan");
		when(bartizan.getDescription()).thenReturn(
				new PluginDescriptionFile("Bartizan", "0.1.0-TEST", "org.luckyraven.bartizan.Bartizan"));
		when(bartizan.getResource(anyString())).thenReturn(null);
		doNothing().when(bartizan).saveResource(anyString(), anyBoolean());
		when(bartizan.isEnabled()).thenReturn(false);

		context = new BartizanContext(bartizan);
	}

	/**
	 * Runs {@link BartizanContext#bootstrap()} for real. It will fail once it reaches a phase that needs a live
	 * Bukkit server ({@code Bukkit.getServer()} is null in this unit test) or real config resources — expected and
	 * irrelevant to what this test class checks (see class javadoc for why the effects under test still happen).
	 */
	private void runBootstrap() {
		try {
			context.bootstrap();
		} catch (Throwable ignored) {
			// expected outside a live server
		}
	}

	@Test
	@DisplayName("BartizanContext.bootstrap() scans org.luckyraven.bartizan.config and discovers all four configs")
	void scansTheConfigPackage() throws Exception {
		assertEquals("org.luckyraven.bartizan.config", configPackageConstant());

		runBootstrap();

		List<Class<?>> discovered = discoveredConfigClasses();
		for (Class<?> expected : List.of(KernelConfig.class, FilesConfig.class, WiringConfig.class, ItemConfig.class)) {
			assertTrue(discovered.contains(expected),
			           expected.getSimpleName() + " must be discovered by the CONFIG_PACKAGE scan, found: " + discovered);
		}
	}

	@Test
	@DisplayName("every discovered @Configuration class carries the phase §1.3 assigns it")
	void configurationClassesCarryTheExpectedPhase() {
		assertEquals(Phase.KERNEL, phaseOf(KernelConfig.class));
		assertEquals(Phase.FILE, phaseOf(FilesConfig.class));
		// WiringConfig/ItemConfig carry no explicit phase() argument — @Configuration defaults to Phase.CONFIG,
		// which is what §1.3 calls for both (bartizan.md line 402/430/1058: "(CONFIG)").
		assertEquals(Phase.CONFIG, phaseOf(WiringConfig.class));
		assertEquals(Phase.CONFIG, phaseOf(ItemConfig.class));
	}

	@Test
	@DisplayName("WeaponCommand and WeaponQuitCleanupListener live under the packages BartizanContext scans")
	void keyClassesLiveUnderTheScannedPackages() throws Exception {
		String commandPackage  = commandPackageConstant();
		String listenerPackage = listenerPackageConstant();

		assertEquals("org.luckyraven.bartizan.command", commandPackage);
		assertEquals(commandPackage, WeaponCommand.class.getPackageName());

		assertEquals("org.luckyraven.bartizan", listenerPackage);
		assertTrue(WeaponQuitCleanupListener.class.getPackageName().startsWith(listenerPackage));
	}

	@Test
	@DisplayName("the FILE-phase hook looks up FileManager in the container and calls initializeAll() on it")
	void filePhaseHookInitializesFiles() throws Exception {
		// Register the mock BEFORE bootstrap() runs: DependencyContainer.getInstance(type) always returns the
		// FIRST instance registered for that type, and KernelConfig's own fileManager() bean (KERNEL phase) will
		// try to register a real FileManager too. Registering first guarantees the hook resolves THIS mock
		// regardless of whether that real bean also runs.
		FileManager fileManager = mock(FileManager.class);
		context.getContainer().registerInstance(FileManager.class, fileManager);

		runBootstrap();

		Consumer<List<Object>> fileHook = filePhaseHook();
		assertNotNull(fileHook, "BartizanContext.bootstrap() must register a FILE-phase hook");

		// The hook may already have fired for real during runBootstrap() if the FILE phase was reached — clear
		// that count and invoke it once more directly so the assertion below is deterministic either way.
		clearInvocations(fileManager);
		fileHook.accept(List.of());

		verify(fileManager, times(1)).initializeAll();
	}

	// --- reflection helpers -------------------------------------------------------------------------------

	private static String configPackageConstant() throws Exception {
		return staticStringField(BartizanContext.class, "CONFIG_PACKAGE");
	}

	private static String commandPackageConstant() throws Exception {
		return staticStringField(BartizanContext.class, "COMMAND_PACKAGE");
	}

	private static String listenerPackageConstant() throws Exception {
		return staticStringField(BartizanContext.class, "LISTENER_PACKAGE");
	}

	private static String staticStringField(Class<?> type, String name) throws Exception {
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		return (String) field.get(null);
	}

	private static Phase phaseOf(Class<?> configClass) {
		return configClass.getAnnotation(Configuration.class).phase();
	}

	@SuppressWarnings("unchecked")
	private List<Class<?>> discoveredConfigClasses() throws Exception {
		Field field = BeanFactory.class.getDeclaredField("configClasses");
		field.setAccessible(true);
		return (List<Class<?>>) field.get(context.getBeanFactory());
	}

	@SuppressWarnings("unchecked")
	private Consumer<List<Object>> filePhaseHook() throws Exception {
		Field field = BeanFactory.class.getDeclaredField("phaseHooks");
		field.setAccessible(true);
		Map<Phase, Consumer<List<Object>>> hooks = (Map<Phase, Consumer<List<Object>>>) field.get(context.getBeanFactory());
		return hooks.get(Phase.FILE);
	}

}
