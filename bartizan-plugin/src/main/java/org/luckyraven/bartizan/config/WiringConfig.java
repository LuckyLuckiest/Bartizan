package org.luckyraven.bartizan.config;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.BartizanApiImpl;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.BartizanApi;
import org.luckyraven.bartizan.api.combat.CombatEligibility;
import org.luckyraven.bartizan.api.item.WeaponItemApi;
import org.luckyraven.bartizan.api.npc.NpcWeaponFactory;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.raytrace.WeaponVisualSpawner;
import org.luckyraven.bartizan.api.weapon.modifiers.BlockDamageManager;
import org.luckyraven.bartizan.bootstrap.DefaultListenerService;
import org.luckyraven.bartizan.configuration.WeaponAddon;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.file.BartizanSettings;
import org.luckyraven.bartizan.file.WeaponBlockRegenerationSettings;
import org.luckyraven.bartizan.fire.PluginFireRegistry;
import org.luckyraven.bartizan.npc.NpcWeaponFactoryImpl;
import org.luckyraven.bartizan.raytrace.WeaponRaytracerImpl;
import org.luckyraven.bartizan.status.StatusEffectService;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.wearable.WearableAddon;
import org.luckyraven.bartizan.wearable.WearableService;
import org.luckyraven.keystone.bean.Bean;
import org.luckyraven.keystone.bean.Configuration;
import org.luckyraven.keystone.bean.SettingsLookup;
import org.luckyraven.keystone.bean.autowire.DependencyContainer;
import org.luckyraven.keystone.command.CommandManager;

import java.util.Random;

/**
 * CONFIG-phase wiring for the weapon system — the standalone-plugin twin of Gangland's {@code WeaponModuleConfig}
 * (bartizan.md §1.3): the weapon/wearable managers and the seams Bartizan itself needs a bean for
 * ({@code BartizanApi}, {@code NpcWeaponFactory}, the {@code CombatEligibility} holder, the {@code ListenerService}
 * and {@code CommandManager} instances). Every bean Gangland's version fed to a now-deleted seam
 * ({@code MetricsContributor}, {@code DataCleanupTask}, {@code NbtTagCatalog}, {@code ShopDisplayNameProvider},
 * {@code DeathMessageContributor}, the sign contributions, {@code WearableEquipService}, {@code RecoilCompatibility})
 * is dropped — those seams don't exist outside Gangland.
 */
@Configuration
public final class WiringConfig {

	private final Bartizan bartizan;

	public WiringConfig(Bartizan bartizan) {
		this.bartizan = bartizan;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Weapon system
	// ---------------------------------------------------------------------------------------------------------------

	@Bean
	public WeaponManager weaponManager(WeaponAddon weaponAddon) {
		return new WeaponManager(weaponAddon);
	}

	@Bean
	public WeaponService weaponService(WeaponManager weaponManager) {
		return weaponManager;
	}

	@Bean
	public BlockDamageManager blockDamageManager(WeaponBlockRegenerationSettings settings) {
		return new BlockDamageManager(bartizan, settings);
	}

	@Bean
	public WeaponVisualSpawner weaponVisualSpawner() {
		return new WeaponVisualSpawner();
	}

	/**
	 * The one feedback engine every hook fires through (weapons-roadmap.md gate {@code HA}, §1).
	 * {@code BartizanSettings} is an ordering-only parameter — the bean-graph edge that keeps this after settings
	 * load (house rule {@code feedback_bean_ordering_via_params}); {@link EffectRunner#run} reads
	 * {@code BartizanSettings.getDefaultEffects()} directly, a static getter.
	 */
	@Bean
	public EffectRunner effectRunner(BartizanSettings settings) {
		return new EffectRunner(bartizan);
	}

	/**
	 * Cross-plugin raytracer publishing (bartizan.md §1.6(7)): the {@code ServicesManager} key stays the api
	 * interface — {@link WeaponRaytracer} — so third-party consumers (and Bartizan's own
	 * {@code NpcWeaponControllerImpl}) resolve it exactly as {@code NpcCombatDelegate.java:332} does today. The
	 * bean's own declared return type is the concrete class (house rule: a holder {@code @Bean} declares the
	 * concrete class).
	 */
	@Bean
	public WeaponRaytracerImpl weaponRaytracer(WeaponManager weaponManager, WearableAddon wearableAddon,
	                                           BlockDamageManager blockDamageManager,
	                                           WeaponVisualSpawner weaponVisualSpawner, EffectRunner effectRunner) {
		WeaponRaytracerImpl raytracer = new WeaponRaytracerImpl(weaponManager, wearableAddon, blockDamageManager,
		                                                        weaponVisualSpawner, effectRunner);
		Bukkit.getServicesManager().register(WeaponRaytracer.class, raytracer, bartizan, ServicePriority.Normal);
		return raytracer;
	}

	@Bean
	public WearableService wearableService(WearableAddon wearableAddon) {
		return wearableAddon;
	}

	@Bean
	public PluginFireRegistry pluginFireRegistry() {
		return new PluginFireRegistry();
	}

	/**
	 * Tracks live biological statuses and drives their boss bar/ambient/contagion/expiry feedback
	 * (weapons-roadmap.md gate {@code HB}, §2.2). Spigot has no public "current tick" accessor
	 * (that's Paper-only), so the clock is a wall-clock-derived tick-equivalent — the same 50ms-per-tick conversion
	 * {@code WeaponInteract}'s press-lock gate already relies on.
	 */
	@Bean
	public StatusEffectService statusEffectService(EffectRunner effectRunner, WearableService wearableService) {
		StatusEffectService service = new StatusEffectService(bartizan, effectRunner, wearableService,
		                                                       () -> System.currentTimeMillis() / 50L, new Random());
		service.start();
		return service;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Bartizan's own seams
	// ---------------------------------------------------------------------------------------------------------------

	/**
	 * Registers {@link BartizanApi} on the {@code ServicesManager} — the single discovery point Gangland modules
	 * pull lazily (bartizan.md §C.3 / documentation/bartizan-api.md).
	 */
	@Bean
	public BartizanApiImpl bartizanApi(WeaponManager weaponManager, WearableAddon wearableAddon,
	                               AmmunitionManager ammunitionManager, NpcWeaponFactory npcWeaponFactory,
	                               WeaponItemApi weaponItemApi) {
		BartizanApiImpl api = new BartizanApiImpl(weaponManager, wearableAddon, ammunitionManager, npcWeaponFactory,
		                                      weaponItemApi);
		Bukkit.getServicesManager().register(BartizanApi.class, api, bartizan, ServicePriority.Normal);
		return api;
	}

	@Bean
	public NpcWeaponFactory npcWeaponFactory(WeaponManager weaponManager, EffectRunner effectRunner) {
		return new NpcWeaponFactoryImpl(bartizan, weaponManager, effectRunner);
	}

	/**
	 * Holder bean for {@link CombatEligibility}: replaces the deleted {@code DownedPlayerRegistry} static gate.
	 * Delegates to {@link CombatEligibility#resolve()} on every call so the {@code ServicesManager} lookup stays
	 * lazy — never cached at bean construction (bartizan.md §1.6(5), R8: Bartizan may enable before Gangland).
	 */
	@Bean
	public CombatEligibility combatEligibility() {
		return player -> CombatEligibility.resolve().canBeHit(player);
	}

	@Bean
	public DefaultListenerService listenerService(DependencyContainer container, SettingsLookup settings) {
		return new DefaultListenerService(bartizan, container, settings);
	}

	@Bean
	public CommandManager commandManager(DependencyContainer container, SettingsLookup settings) {
		return new CommandManager(bartizan, container, settings, Bartizan.FULL_PREFIX, Bartizan.FULL_PREFIX);
	}
}
