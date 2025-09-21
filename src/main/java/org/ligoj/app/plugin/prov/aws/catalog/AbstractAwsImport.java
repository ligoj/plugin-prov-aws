/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.catalog.AbstractImportCatalogResource;
import org.ligoj.app.plugin.prov.model.ImportCatalogStatus;
import org.ligoj.bootstrap.core.curl.CurlProcessor;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The provisioning price service for AWS. Manage install or update of prices.
 */
@Slf4j
public abstract class AbstractAwsImport extends AbstractImportCatalogResource {

	@Autowired
	private ProvResource provResource;

	protected Double toInteger(final String value) {
		return Optional.ofNullable(StringUtils.trimToNull(value))
				.map(v -> StringUtils.replaceEach(v, new String[] { "GB", "TB", " " }, new String[] { "", "", "" }))
				.map(Double::valueOf).map(v -> value.contains("TB") ? v * 1024 : v).orElse(0d);
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
	 * Return the full CSV URL from the relative URL
	 * 
	 * @param gContext The current global context.
	 * @param url      The relative JSON URL.
	 * @return The full CSV URL from the relative URL.
	 */
	protected String getCsvUrl(final UpdateContext gContext, final String url) {
		return gContext.getUrl(url).replaceAll("\\.json$", ".csv");
	}

	@Override
	protected int getWorkload(ImportCatalogStatus status) {
		// NB regions * 10 (EC2 + EC2 Scoring + RDS + RDS Scoring + EC2 SP + EC2 SP Scoring + Fargate + Fargate SP + Lambda + Lambda SP)
		// + 2 global prices (S3+EFS)
		// + 3 (EC2 + Fargate + Lambda savings plan configuration)
		// + 2 (EC2 + Fargate spots)
		// + 1 (Support)
		// + 1 (Regions)
		return status.getNbLocations() * 10 + 9;
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
			final String api, final String endpoint, final Class<J> apiClass, final Consumer<R> mapper)
			throws IOException {
		log.info("AWS {} prices...", api);
		try (var curl = new CurlProcessor()) {
			// Get the remote prices stream
			final var rawJson = Objects.toString(curl.get(endpoint), "any({\"config\":{\"regions\":[]}});");

			// All regions are considered
			final var configIndex = rawJson.indexOf('{');
			final var configCloseIndex = rawJson.lastIndexOf('}');
			final var prices = objectMapper.readValue(rawJson.substring(configIndex, configCloseIndex + 1), apiClass);

			// Install the enabled regions as needed
			final var eRegions = prices.getConfig().getRegions().stream()
					.peek(r -> r.setRegion(context.getMapSpotToNewRegion().getOrDefault(r.getRegion(), r.getRegion())))
					.filter(r -> isEnabledRegion(context, r)).toList();
			eRegions.forEach(r -> installRegion(context, r.getRegion()));

			// Install the prices for each region
			newStream(eRegions).forEach(mapper);
		} finally {
			// Report
			log.info("AWS {} import finished", api);
		}
	}

	/**
	 * Return the regional prices of each enabled region.
	 * 
	 * @param context     The update context.
	 * @param api         The API name, only for logging.
	 * @param serviceCode The AWS service code, like <code>AmazonEC2</code>.
	 * @return The regions with the corresponding prices file. The key corresponds to the API region code.
	 * @throws IOException When the index cannot be retrieved.
	 */
	protected Map<String, AwsPriceRegion> getRegionalPrices(final UpdateContext context, final String api,
			final String serviceCode) throws IOException {
		return getRegionalSPPrices(context, api, serviceCode, AwsPriceOffer::getCurrentRegionIndexUrl,
				AwsPriceRegions.class, "OnDemand");
	}

	/**
	 * Return the regional savings plan prices of each enabled region.
	 * 
	 * @param context     The update context.
	 * @param api         The API name, only for logging.
	 * @param serviceCode The AWS service code, like <code>AmazonEC2</code>.
	 * @return The regions with the corresponding savings plan prices file. The key corresponds to the API region code.
	 * @throws IOException When the index cannot be retrieved.
	 */
	protected Map<String, AwsPriceRegion> getRegionalSPPrices(final UpdateContext context, final String api,
			final String serviceCode) throws IOException {
		return getRegionalSPPrices(context, api, serviceCode, AwsPriceOffer::getCurrentSavingsPlanIndexUrl,
				AwsSPPriceRegions.class, "SavingsPlan");
	}

	/**
	 * Return the regional savings plan prices of each enabled region.
	 * 
	 * @param context     The update context.
	 * @param api         The API name, only for logging.
	 * @param serviceCode The AWS service code, like <code>AmazonEC2</code>.
	 * @param toUrl       The URL extractor from the offer configuration.
	 * @param classifier  The kind of prices to retrieve. Only for logging.
	 * @return The regions with the corresponding savings plan prices file. The key corresponds to the API region code.
	 * @throws IOException When the index cannot be retrieved.
	 */
	private Map<String, AwsPriceRegion> getRegionalSPPrices(final UpdateContext context, final String api,
			final String serviceCode, final Function<AwsPriceOffer, String> toUrl,
			final Class<? extends RegionalPrices> clazz, final String classifier)
			throws IOException {
		final var path = toUrl.apply(context.getOffers().get(serviceCode));
		if (path == null) {
			return Collections.emptyMap();
		}
		final var indexUrl = context.getUrl(path);
		log.info("AWS {} import: download regional {} index >{}", api, classifier, indexUrl);
		try (var reader2 = new BufferedReader(new InputStreamReader(URI.create(indexUrl).toURL().openStream()))) {
			return objectMapper.readValue(reader2, clazz).getPRegions().entrySet().stream()
					.filter(e -> isEnabledRegion(context, e.getKey()))
					.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		}
	}

}
