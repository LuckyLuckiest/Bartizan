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
import org.luckyraven.bartizan.hud.HudService;
import org.luckyraven.bartizan.hud.PlaceholderApiSupport;
import org.luckyraven.bartizan.npc.NpcWeaponFactoryImpl;
import org.luckyraven.bartizan.raytrace.ExplosionHandler;
import org.luckyraven.bartizan.raytrace.WeaponRaytracerImpl;
import org.luckyraven.bartizan.scope.SpyglassScopeTask;
import org.luckyraven.bartizan.stats.StatsService;
import org.luckyraven.bartizan.status.StatusEffectService;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.wearable.WearableAddon;
import org.luckyraven.bartizan.wearable.WearableEffectsService;
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
	public WeaponRaytracerImpl weaponRaytracer(WearableAddon wearableAddon, BlockDamageManager blockDamageManager,
	                                           WeaponVisualSpawner weaponVisualSpawner, EffectRunner effectRunner) {
		WeaponRaytracerImpl raytracer = new WeaponRaytracerImpl(wearableAddon, blockDamageManager,
		                                                        weaponVisualSpawner, effectRunner);
		Bukkit.getServicesManager().register(WeaponRaytracer.class, raytracer, bartizan, ServicePriority.Normal);
		return raytracer;
	}

	/**
	 * Gate {@code HI-a}: the unified AOE explosion path for both guns (rockets) and throwables (grenades). Neither
	 * {@code SteppedProjectileTask} nor {@code ThrowableAction} is constructed through this bean graph (the
	 * former's constructor belongs to gate {@code HI-b}; the latter is manually {@code new}'d per-throw by
	 * {@code WeaponInteract}), so both reach this singleton via {@code ExplosionHandler.get()} instead of
	 * constructor injection — see that method's javadoc.
	 */
	@Bean
	public ExplosionHandler explosionHandler(WeaponRaytracer raytracer, BlockDamageManager blockDamageManager,
	                                         EffectRunner effectRunner) {
		return new ExplosionHandler(bartizan, raytracer, blockDamageManager, effectRunner);
	}

	@Bean
	public WearableService wearableService(WearableAddon wearableAddon) {
		return wearableAddon;
	}

	/**
	 * {@code Scope.Type: spyglass}'s scope-out poll and scoped-{@code F}-fire bookkeeping (weapons-roadmap.md gate
	 * {@code HP}) - shared by {@code WeaponInteract} (registers a player on scope-in) and
	 * {@code WeaponSelectiveFireChangeListener} (the scoped {@code F} fire trigger).
	 */
	@Bean
	public SpyglassScopeTask spyglassScopeTask(WeaponService weaponService, WeaponRaytracer raytracer,
	                                           EffectRunner effectRunner) {
		SpyglassScopeTask task = new SpyglassScopeTask(bartizan, weaponService, raytracer, effectRunner);
		task.start();
		return task;
	}

	@Bean
	public PluginFireRegistry pluginFireRegistry() {
		return new PluginFireRegistry();
	}

	/**
	 * Worn-wearable {@code Effects_While_Worn} + {@code On_Equip}/{@code On_Unequip} ticker (weapons-roadmap.md
	 * gate {@code HL}, §5).
	 */
	@Bean
	public WearableEffectsService wearableEffectsService(WearableService wearableService, EffectRunner effectRunner) {
		WearableEffectsService service = new WearableEffectsService(bartizan, wearableService, effectRunner);
		service.start();
		return service;
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

	/**
	 * Continuous per-player weapon HUD (weapons-roadmap.md gate {@code HD}): action bar/boss bar ticker for a
	 * weapon's {@code HUD:} block. Also the registration point for the PlaceholderAPI expansion — see
	 * {@link PlaceholderApiSupport} for why that call, not the expansion itself, lives directly in this method
	 * rather than behind its own bean.
	 */
	@Bean
	public HudService hudService(WeaponService weaponService) {
		HudService service = new HudService(bartizan, weaponService);
		service.start();

		PlaceholderApiSupport.registerIfPresent(bartizan, weaponService);

		return service;
	}

	/**
	 * Per-player weapon statistics (weapons-roadmap.md gate {@code HK}) - Gson flat files under
	 * {@code plugins/Bartizan/stats/}, no database. Same wall-clock-derived tick-equivalent clock
	 * {@code statusEffectService} uses.
	 */
	@Bean
	public StatsService statsService() {
		StatsService service = new StatsService(bartizan, () -> System.currentTimeMillis() / 50L);
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
		BartizanApiImpl api = new BartizanApiImpl(bartizan, weaponManager, wearableAddon, ammunitionManager,
		                                      npcWeaponFactory, weaponItemApi);
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
