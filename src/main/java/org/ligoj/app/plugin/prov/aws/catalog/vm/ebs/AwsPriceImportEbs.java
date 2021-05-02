/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.ebs;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.math.NumberUtils;
import org.ligoj.app.plugin.prov.aws.catalog.AbstractAwsImport;
import org.ligoj.app.plugin.prov.aws.catalog.AwsPrice;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.catalog.ImportCatalog;
import org.ligoj.app.plugin.prov.model.AbstractCodedEntity;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.springframework.stereotype.Component;

/**
 * The provisioning EBS price service for AWS. Manage install or update of prices.
 */
@Component
public class AwsPriceImportEbs extends AbstractAwsImport implements ImportCatalog<UpdateContext> {

	/**
	 * The EBS prices end-point. Contains the prices for all regions.
	 */
	private static final String EBS_PRICES = "https://a0.awsstatic.com/pricing/1/ebs/pricing-ebs.js";

	/**
	 * Configuration key used for {@link #EBS_PRICES}
	 */
	public static final String CONF_URL_EBS_PRICES = String.format(CONF_URL_API_PRICES, "ebs");

	@Override
	public void install(final UpdateContext context) throws IOException {
		nextStep(context, "ebs", null, 0);
		// The previously installed storage types cache. Key is the storage name
		final var node = context.getNode();
		context.setStorageTypes(stRepository.findAllBy(BY_NODE, context.getNode()).stream()
				.collect(Collectors.toMap(AbstractCodedEntity::getCode, Function.identity())));
		installStorageTypes(context);
		context.getMapSpotToNewRegion().putAll(toMap("spot-to-new-region.json", MAP_STR));

		// Install EBS prices
		installJsonPrices(context, "ebs", configuration.get(CONF_URL_EBS_PRICES, EBS_PRICES), EbsPrices.class, r -> {
			// Get previous prices for this location
			final var region = locationRepository.findByName(context.getNode().getId(), r.getRegion());
			final var previous = spRepository.findAll(node.getId(), region.getName()).stream()
					.collect(Collectors.toMap(p -> p.getType().getId(), Function.identity()));
			r.getTypes().stream().filter(t -> containsKey(context, t))
					.forEach(t -> t.getValues().stream().filter(j -> !"perPIOPSreq".equals(j.getRate())).findFirst()
							.ifPresent(j -> installStoragePrice(context, j, context.getStorageTypes().get(t.getName()),
									region, previous)));
		});
		nextStep(context, "ebs", null, 1);
	}

	private void installStorageTypes(final UpdateContext context) throws IOException {
		csvForBean.toBean(ProvStorageType.class, "csv/aws-prov-storage-type.csv").forEach(t -> {
			final var entity = context.getStorageTypes().computeIfAbsent(t.getName(), n -> {
				final var newType = new ProvStorageType();
				newType.setNode(context.getNode());
				newType.setCode(n);
				return newType;
			});

			// Merge the storage type details
			entity.setName(entity.getCode());
			entity.setDescription(t.getDescription());
			entity.setInstanceType(t.getInstanceType());
			entity.setDatabaseType(t.getDatabaseType());
			entity.setIops(t.getIops());
			entity.setLatency(t.getLatency());
			entity.setMaximal(t.getMaximal());
			entity.setMinimal(t.getMinimal());
			entity.setOptimized(t.getOptimized());
			entity.setThroughput(t.getThroughput());
			entity.setAvailability(t.getAvailability());
			entity.setDurability9(t.getDurability9());
			entity.setEngine(t.getEngine());
			stRepository.save(entity);
		});
	}

	/**
	 * Install the EBS price using the related storage price.
	 *
	 * @param context The update context.
	 * @param json    The current JSON entry.
	 * @param type    The related storage type.
	 * @param region  The target region.
	 */
	private <T extends AwsPrice> void installStoragePrice(final UpdateContext context, final T json,
			final ProvStorageType type, final ProvLocation region, final Map<Integer, ProvStoragePrice> previous) {
		Optional.ofNullable(json.getPrices().get("USD")).filter(NumberUtils::isParsable)
				.ifPresent(usd -> installStoragePrice(context, type, region, previous, usd));
	}

	private void installStoragePrice(final UpdateContext context, final ProvStorageType type, final ProvLocation region,
			final Map<Integer, ProvStoragePrice> previous, String usd) {
		final var price = previous.computeIfAbsent(type.getId(), s -> {
			final var p = new ProvStoragePrice();
			p.setType(type);
			p.setCode(region.getName() + "-" + type.getName());
			p.setLocation(region);
			return p;
		});
		price.setCode(region.getName() + "-" + type.getName());

		// Update the price as needed
		saveAsNeeded(context, price, Double.parseDouble(usd), spRepository);
	}
}
