package org.luckyraven.bartizan.config;

import lombok.CustomLog;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.item.AmmunitionConverter;
import org.luckyraven.bartizan.item.AmmunitionItemRefresher;
import org.luckyraven.bartizan.item.AmmunitionItemSerializer;
import org.luckyraven.bartizan.item.BartizanItemVocabulary;
import org.luckyraven.bartizan.item.WeaponConverter;
import org.luckyraven.bartizan.item.WeaponItemApiImpl;
import org.luckyraven.bartizan.item.WeaponItemSerializer;
import org.luckyraven.bartizan.item.WeaponRefresher;
import org.luckyraven.bartizan.item.WearableConverter;
import org.luckyraven.bartizan.item.WearableItemSerializer;
import org.luckyraven.bartizan.item.WearableRefresher;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.wearable.WearableService;
import org.luckyraven.bartizan.api.item.WeaponItemApi;
import org.luckyraven.keystone.bean.Bean;
import org.luckyraven.keystone.bean.Configuration;
import org.luckyraven.keystone.item.spi.ItemVocabulary;

/**
 * CONFIG-phase item framework wiring — replaces Gangland's {@code weaponConverter} … {@code weaponItemRegistrations}
 * beans (bartizan.md §1.3). The nine converter/serializer/refresher beans move over unchanged in shape;
 * {@code weaponItemRegistrations(...)}, which registered directly into the core's shared registries, is replaced
 * by a single {@link BartizanItemVocabulary} bean published on the {@code ServicesManager} — Gangland pulls every
 * registered {@code ItemVocabulary} and folds it into its own registries (R6/R7).
 */
@CustomLog
@Configuration
public final class ItemConfig {

	private final Bartizan bartizan;

	public ItemConfig(Bartizan bartizan) {
		this.bartizan = bartizan;
	}

	@Bean
	public WeaponConverter weaponConverter(WeaponService weaponService) {
		return new WeaponConverter(weaponService);
	}

	@Bean
	public AmmunitionConverter ammunitionConverter(AmmunitionManager ammunitionManager) {
		return new AmmunitionConverter(ammunitionManager);
	}

	@Bean
	public WearableConverter wearableConverter(WearableService wearableService) {
		return new WearableConverter(wearableService);
	}

	@Bean
	public WeaponItemSerializer weaponItemSerializer() {
		return new WeaponItemSerializer();
	}

	@Bean
	public AmmunitionItemSerializer ammunitionItemSerializer() {
		return new AmmunitionItemSerializer();
	}

	@Bean
	public WearableItemSerializer wearableItemSerializer() {
		return new WearableItemSerializer();
	}

	@Bean
	public WeaponRefresher weaponRefresher(WeaponService weaponService) {
		return new WeaponRefresher(weaponService);
	}

	@Bean
	public WearableRefresher wearableRefresher(WearableService wearableService) {
		return new WearableRefresher(wearableService);
	}

	@Bean
	public AmmunitionItemRefresher ammunitionItemRefresher(AmmunitionManager ammunitionManager) {
		return new AmmunitionItemRefresher(ammunitionManager);
	}

	/**
	 * {@link WeaponItemApi} — the cross-plugin build/compare/display-name helpers (bartizan.md §1.6(9)).
	 */
	@Bean
	public WeaponItemApi weaponItemApi(WeaponService weaponService) {
		return new WeaponItemApiImpl(weaponService);
	}

	/**
	 * Publishes {@link BartizanItemVocabulary} on the {@code ServicesManager} (namespace {@code "bartizan"}) so
	 * {@code weapon:} / {@code ammo:} / {@code wearable:} item strings keep resolving inside Gangland's loot chests,
	 * shops and signs after the split (R6). The priorities baked into {@code BartizanItemVocabulary.contribute(...)}
	 * are load-bearing (§C.3 / R7) — weapon and wearable refreshers at 10 outrank Gangland's own
	 * {@code uniqueItemRefresher} (0), ammunition sits behind it at 0.
	 */
	@Bean
	public ItemVocabulary bartizanItemVocabulary(WeaponConverter weaponConverter,
	                                             AmmunitionConverter ammunitionConverter,
	                                             WearableConverter wearableConverter,
	                                             WeaponItemSerializer weaponItemSerializer,
	                                             AmmunitionItemSerializer ammunitionItemSerializer,
	                                             WearableItemSerializer wearableItemSerializer,
	                                             WeaponRefresher weaponRefresher,
	                                             WearableRefresher wearableRefresher,
	                                             AmmunitionItemRefresher ammunitionItemRefresher) {
		BartizanItemVocabulary vocabulary = new BartizanItemVocabulary(weaponConverter, ammunitionConverter,
		                                                               wearableConverter, weaponItemSerializer,
		                                                               ammunitionItemSerializer, wearableItemSerializer,
		                                                               weaponRefresher, wearableRefresher,
		                                                               ammunitionItemRefresher);
		Bukkit.getServicesManager().register(ItemVocabulary.class, vocabulary, bartizan, ServicePriority.Normal);
		log.info("Item vocabulary published: {}", vocabulary.namespace());
		return vocabulary;
	}
}
