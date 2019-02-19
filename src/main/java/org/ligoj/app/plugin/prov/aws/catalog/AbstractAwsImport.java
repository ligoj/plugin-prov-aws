/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.model.Node;
import org.ligoj.app.plugin.prov.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.prov.catalog.AbstractImportCatalogResource;
import org.ligoj.app.plugin.prov.model.AbstractPrice;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.ligoj.bootstrap.core.INamableBean;
import org.ligoj.bootstrap.core.dao.csv.CsvForJpa;
import org.ligoj.bootstrap.core.model.AbstractNamedEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.core.type.TypeReference;

/**
 * The provisioning price service for AWS. Manage install or update of prices.
 */
public abstract class AbstractAwsImport extends AbstractImportCatalogResource {

	protected static final TypeReference<Map<String, String>> MAP_STR = new TypeReference<>() {
		// Nothing to extend
	};

	protected static final String BY_NODE = "node";

	/**
	 * Configuration key used for AWS URL prices.
	 */
	public static final String CONF_URL_API_PRICES = ProvAwsPluginResource.KEY + ":%s-prices-url";

	protected static final double HOUR_TO_MONTH = 24 * 30.5;

	@Autowired
	protected CsvForJpa csvForBean;

	/**
	 * Install or update prices.
	 *
	 * @param context
	 *            The update context.
	 * @throws IOException
	 *             When CSV or XML files cannot be read.
	 * @throws URISyntaxException
	 *             When CSV or XML files cannot be read.
	 */
	public abstract void install(final UpdateContext context) throws IOException, URISyntaxException;

	protected Double toPercent(String raw) {
		if (StringUtils.endsWith(raw, "%")) {
			return Double.valueOf(raw.substring(0, raw.length() - 1));
		}

		// Not a valid percent
		return null;
	}

	/**
	 * Return the {@link ProvLocation} matching the human name.
	 *
	 * @param context
	 *            The update context.
	 * @param humanName
	 *            The required human name.
	 * @return The corresponding {@link ProvLocation} or <code>null</code>.
	 */
	protected ProvLocation getRegionByHumanName(final UpdateContext context, final String humanName) {
		return context.getRegions().values().stream().filter(r -> isEnabledRegion(context, r))
				.filter(r -> humanName.equals(r.getDescription())).findAny().orElse(null);
	}

	/**
	 * Update the statistics
	 */
	protected void nextStep(final Node node, final String location, final int step) {
		importCatalogResource.nextStep(node.getId(), t -> {
			importCatalogResource.updateStats(t);
			t.setWorkload(t.getNbLocations() * 3 + 4); // NB region (for EC2 + Spot + RDS) + 3 (S3+EBS+EFS)
			t.setDone(t.getDone() + step);
			t.setLocation(location);
		});
	}

	/**
	 * Update the statistics
	 */
	protected void nextStep(final UpdateContext context, final String location, final int step) {
		nextStep(context.getNode(), location, step);
	}

	/**
	 * Install a new region.
	 */
	protected ProvLocation installRegion(final UpdateContext context, final String region) {
		final ProvLocation entity = context.getRegions().computeIfAbsent(region, r -> {
			final ProvLocation newRegion = new ProvLocation();
			newRegion.setNode(context.getNode());
			newRegion.setName(r);
			return newRegion;
		});

		// Update the location details as needed
		if (context.getRegionsMerged().add(region)) {
			final ProvLocation regionStats = context.getMapRegionToName().getOrDefault(region, new ProvLocation());
			entity.setContinentM49(regionStats.getContinentM49());
			entity.setCountryA2(regionStats.getCountryA2());
			entity.setCountryM49(regionStats.getCountryM49());
			entity.setPlacement(regionStats.getPlacement());
			entity.setRegionM49(regionStats.getRegionM49());
			entity.setSubRegion(regionStats.getSubRegion());
			entity.setLatitude(regionStats.getLatitude());
			entity.setLongitude(regionStats.getLongitude());
			entity.setDescription(regionStats.getName());
			locationRepository.saveAndFlush(entity);
		}
		return entity;
	}

	protected Integer toInteger(final String value) {
		return Optional.ofNullable(StringUtils.trimToNull(value))
				.map(v -> StringUtils.replaceEach(v, new String[] { "GB", "TB", " " }, new String[] { "", "", "" }))
				.map(Integer::valueOf).map(v -> value.contains("TB") ? v * 1024 : v).orElse(null);
	}

	protected <A extends Serializable, N extends AbstractNamedEntity<A>, T extends AbstractPrice<N>> T saveAsNeeded(
			final T entity, final double oldCost, final double newCost, final DoubleConsumer updateCost,
			final Consumer<T> c) {
		if (oldCost != newCost) {
			updateCost.accept(newCost);
			c.accept(entity);
		}
		return entity;
	}

	protected ProvStoragePrice saveAsNeeded(final ProvStoragePrice entity, final double newCostGb,
			final Consumer<ProvStoragePrice> c) {
		return saveAsNeeded(entity, entity.getCostGb(), newCostGb, entity::setCostGb, c);
	}

	/**
	 * Round up to 3 decimals the given value.
	 */
	protected double round3Decimals(final double value) {
		return Math.round(value * 1000d) / 1000d;
	}

	/**
	 * Indicate the given region is enabled.
	 *
	 * @param context
	 *            The update context.
	 * @param region
	 *            The region API name to test.
	 * @return <code>true</code> when the configuration enable the given region.
	 */
	protected boolean isEnabledRegion(final UpdateContext context, final AwsRegionPrices region) {
		return isEnabledRegion(context, region.getRegion());
	}

	/**
	 * Indicate the given region is enabled.
	 *
	 * @param context
	 *            The update context.
	 * @param region
	 *            The region API name to test.
	 * @return <code>true</code> when the configuration enable the given region.
	 */
	protected boolean isEnabledRegion(final UpdateContext context, final ProvLocation region) {
		return isEnabledRegion(context, region.getName());
	}

	/**
	 * Indicate the given region is enabled.
	 *
	 * @param context
	 *            The update context.
	 * @param region
	 *            The region API name to test.
	 * @return <code>true</code> when the configuration enable the given region.
	 */
	protected boolean isEnabledRegion(final UpdateContext context, final String region) {
		return context.getValidRegion().matcher(region).matches();
	}

	protected <T> Map<String, T> toMap(final String path, final TypeReference<Map<String, T>> type) throws IOException {
		return objectMapper.readValue(
				IOUtils.toString(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8), type);
	}

	/**
	 * Convert the JSON name to the API name and check this storage is exists
	 *
	 * @param context
	 *            The update context.
	 * @param storage
	 *            The storage to evaluate.
	 * @return <code>true</code> when the storage is valid.
	 */
	protected <T extends INamableBean<?>> boolean containsKey(final UpdateContext context, final T storage) {
		storage.setName(context.getMapStorageToApi().getOrDefault(storage.getName(), storage.getName()));
		return context.getStorageTypes().containsKey(storage.getName());
	}
}
