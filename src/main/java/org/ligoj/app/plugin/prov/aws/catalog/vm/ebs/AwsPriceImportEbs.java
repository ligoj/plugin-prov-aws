/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.ebs;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.math.NumberUtils;
import org.ligoj.app.model.Node;
import org.ligoj.app.plugin.prov.aws.catalog.AwsPrice;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.aws.catalog.vm.AbstractAwsPriceImportVm;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.bootstrap.core.INamableBean;
import org.springframework.stereotype.Component;

/**
 * The provisioning EBS price service for AWS. Manage install or update of prices.
 */
@Component
public class AwsPriceImportEbs extends AbstractAwsPriceImportVm {

	/**
	 * The EBS prices end-point. Contains the prices for all regions.
	 */
	private static final String EBS_PRICES = "https://a0.awsstatic.com/pricing/1/ebs/pricing-ebs.js";

	/**
	 * Configuration key used for {@link #EBS_PRICES}
	 */
	public static final String CONF_URL_EBS_PRICES = String.format(CONF_URL_API_PRICES, "ebs");

	@Override
	public void install(final UpdateContext context) throws IOException, URISyntaxException {
		// The previously installed storage types cache. Key is the storage name
		final Node node = context.getNode();
		context.setStorageTypes(installStorageTypes(context));
		context.getMapSpotToNewRegion().putAll(toMap("spot-to-new-region.json", MAP_STR));

		// Install EBS prices
		installJsonPrices(context, "ebs", configuration.get(CONF_URL_EBS_PRICES, EBS_PRICES), EbsPrices.class,
				(r, region) -> {
					// Get previous prices for this location
					final Map<Integer, ProvStoragePrice> previous = spRepository.findAll(node.getId(), region.getName())
							.stream().collect(Collectors.toMap(p -> p.getType().getId(), Function.identity()));
					return (int) r
							.getTypes().stream().filter(t -> containsKey(context, t)).filter(
									t -> t.getValues().stream().filter(j -> !"perPIOPSreq".equals(j.getRate()))
											.anyMatch(j -> installStorageType(j,
													context.getStorageTypes().get(t.getName()), region, previous)))
							.count();
				});
	}

	private Map<String, ProvStorageType> installStorageTypes(final UpdateContext context) throws IOException {
		final Map<String, ProvStorageType> previous = stRepository.findAllBy(BY_NODE, context.getNode()).stream()
				.collect(Collectors.toMap(INamableBean::getName, Function.identity()));
		csvForBean.toBean(ProvStorageType.class, "csv/aws-prov-storage-type.csv").forEach(t -> {
			final ProvStorageType entity = previous.computeIfAbsent(t.getName(), n -> {
				t.setNode(context.getNode());
				return t;
			});

			// Merge the storage type details
			entity.setDescription(t.getDescription());
			entity.setInstanceCompatible(t.isInstanceCompatible());
			entity.setIops(t.getIops());
			entity.setLatency(t.getLatency());
			entity.setMaximal(t.getMaximal());
			entity.setMinimal(t.getMinimal());
			entity.setOptimized(t.getOptimized());
			entity.setThroughput(t.getThroughput());
			stRepository.save(entity);

		});
		return previous;
	}

	/**
	 * Install the EBS/S3 price using the related storage type.
	 *
	 * @param json
	 *            The current JSON entry.
	 * @param type
	 *            The related storage type.
	 * @param region
	 *            The target region.
	 * @return The amount of installed prices. Only for the report.
	 */
	private <T extends AwsPrice> boolean installStorageType(final T json, final ProvStorageType type,
			final ProvLocation region, final Map<Integer, ProvStoragePrice> previous) {
		return Optional.ofNullable(json.getPrices().get("USD")).filter(NumberUtils::isParsable)
				.map(usd -> installStorageType(type, region, previous, usd)).isPresent();
	}

	private ProvStoragePrice installStorageType(final ProvStorageType type, final ProvLocation region,
			final Map<Integer, ProvStoragePrice> previous, String usd) {
		final ProvStoragePrice price = previous.computeIfAbsent(type.getId(), s -> {
			final ProvStoragePrice p = new ProvStoragePrice();
			p.setType(type);
			p.setCode(region.getName() + "-" + type.getName());
			p.setLocation(region);
			return p;
		});
		price.setCode(region.getName() + "-" + type.getName());

		// Update the price as needed
		return saveAsNeeded(price, Double.valueOf(usd), spRepository::save);
	}
}
