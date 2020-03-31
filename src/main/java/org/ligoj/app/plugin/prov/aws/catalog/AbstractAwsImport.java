/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import java.io.IOException;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.prov.catalog.AbstractImportCatalogResource;
import org.ligoj.app.plugin.prov.model.ImportCatalogStatus;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.bootstrap.core.INamableBean;
import org.ligoj.bootstrap.core.curl.CurlProcessor;
import org.springframework.beans.factory.annotation.Autowired;

import com.hazelcast.util.function.BiConsumer;

import lombok.extern.slf4j.Slf4j;

/**
 * The provisioning price service for AWS. Manage install or update of prices.
 */
@Slf4j
public abstract class AbstractAwsImport extends AbstractImportCatalogResource {

	@Autowired
	private ProvResource provResource;

	/**
	 * Configuration key used for AWS URL prices.
	 */
	public static final String CONF_URL_API_PRICES = ProvAwsPluginResource.KEY + ":%s-prices-url";

	protected Double toInteger(final String value) {
		return Optional.ofNullable(StringUtils.trimToNull(value))
				.map(v -> StringUtils.replaceEach(v, new String[] { "GB", "TB", " " }, new String[] { "", "", "" }))
				.map(Double::valueOf).map(v -> value.contains("TB") ? v * 1024 : v).orElse(null);
	}

	/**
	 * Return a parallel stream if allowed.
	 * 
	 * @param <T>        The stream item type.
	 * @param collection The collection to stream.
	 * @return The parallel or sequential stream.
	 */
	protected <T> Stream<T> newStream(Collection<T> collection) {
		return provResource.newStream(collection);
	}

	/**
	 * Convert the JSON name to the API name and check this storage is exists
	 * 
	 * @param <T>     The storage type.
	 * @param context The update context.
	 * @param storage The storage to evaluate.
	 * @return <code>true</code> when the storage is valid.
	 */
	protected <T extends INamableBean<?>> boolean containsKey(final UpdateContext context, final T storage) {
		storage.setName(context.getMapStorageToApi().getOrDefault(storage.getName(), storage.getName()));
		return context.getStorageTypes().containsKey(storage.getName());
	}

	@Override
	protected int getWorkload(ImportCatalogStatus status) {
		// NB regions * 4 (EC2 + Spot + RDS + Savings Plan prices)
		// + 3 global prices (S3+EBS+EFS)
		// + 1 (spot configuration)
		// + 1 (savings plan configuration)
		// + 1 flush
		return status.getNbLocations() * 4 + 4 + 1;
	}

	/**
	 * Indicate the given region is enabled.
	 *
	 * @param context The update context.
	 * @param region  The region API name to test.
	 * @return <code>true</code> when the configuration enable the given region.
	 */
	protected boolean isEnabledRegion(final UpdateContext context, final AwsRegionPrices region) {
		return isEnabledRegion(context, region.getRegion());
	}

	/**
	 * Install AWS prices from a JSON file.
	 * 
	 * @param <R>      The region prices wrapper type.
	 * @param <J>      The region price type.
	 *
	 * @param context  The update context.
	 * @param api      The API name, only for log.
	 * @param endpoint The prices end-point JSON URL.
	 * @param apiClass The mapping model from JSON at region level.
	 * @param mapper   The mapping function from JSON at region level to JPA entity.
	 * @throws IOException When JSON content cannot be parsed.
	 */
	protected <R extends AwsRegionPrices, J extends AwsPrices<R>> void installJsonPrices(final UpdateContext context,
			final String api, final String endpoint, final Class<J> apiClass, final BiConsumer<R, ProvLocation> mapper)
			throws IOException {
		log.info("AWS {} prices...", api);
		try (var curl = new CurlProcessor()) {
			// Get the remote prices stream
			final var rawJson = StringUtils.defaultString(curl.get(endpoint), "any({\"config\":{\"regions\":[]}});");

			// All regions are considered
			final var configIndex = rawJson.indexOf('{');
			final var configCloseIndex = rawJson.lastIndexOf('}');
			final var prices = objectMapper.readValue(rawJson.substring(configIndex, configCloseIndex + 1), apiClass);

			// Install the enabled region as needed
			final var eRegions = prices.getConfig().getRegions().stream()
					.peek(r -> r.setRegion(context.getMapSpotToNewRegion().getOrDefault(r.getRegion(), r.getRegion())))
					.filter(r -> isEnabledRegion(context, r)).collect(Collectors.toList());
			eRegions.forEach(r -> installRegion(context, r.getRegion()));
			nextStep(context, null, 1);

			// Install the prices for each region
			newStream(eRegions).forEach(r -> mapper.accept(r, context.getRegions().get(r.getRegion())));
		} finally {
			// Report
			log.info("AWS {} import finished", api);
			nextStep(context, null, 1);
		}
	}

}
