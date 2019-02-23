/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.efs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.ligoj.app.plugin.prov.aws.catalog.AbstractAwsImport;
import org.ligoj.app.plugin.prov.aws.catalog.AwsCsvPrice;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.catalog.ImportCatalog;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * The provisioning EFS price service for AWS. Manage install or update of prices.
 */
@Slf4j
@Component
public class AwsPriceImportEfs extends AbstractAwsImport implements ImportCatalog<UpdateContext> {

	/**
	 * The EFS price end-point, a CSV file. Multi-region.
	 */
	private static final String EFS_PRICES = "https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEFS/current/index.csv";

	/**
	 * Configuration key used for {@link #EFS_PRICES}
	 */
	public static final String CONF_URL_EFS_PRICES = String.format(CONF_URL_API_PRICES, "efs");

	@Override
	public void install(final UpdateContext context) throws IOException, URISyntaxException {
		log.info("AWS EFS prices ...");
		importCatalogResource.nextStep(context.getNode().getId(), t -> t.setPhase("efs"));

		// Track the created instance to cache partial costs
		final ProvStorageType efs = stRepository.findAllBy(BY_NODE, context.getNode(), new String[] { "name" }, "efs")
				.get(0);
		final Map<ProvLocation, ProvStoragePrice> previous = spRepository.findAllBy("type", efs).stream()
				.collect(Collectors.toMap(ProvStoragePrice::getLocation, Function.identity()));

		int priceCounter = 0;
		// Get the remote prices stream
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				new URI(configuration.get(CONF_URL_EFS_PRICES, EFS_PRICES)).toURL().openStream()))) {
			// Pipe to the CSV reader
			final CsvForBeanEfs csvReader = new CsvForBeanEfs(reader);

			// Build the AWS instance prices from the CSV
			AwsCsvPrice csv = csvReader.read();
			while (csv != null) {
				final ProvLocation location = getRegionByHumanName(context, csv.getLocation());
				if (location != null) {
					// Supported location
					instalEfsPrice(efs, previous, csv, location);
					priceCounter++;
				}
				// Read the next one
				csv = csvReader.read();
			}
		} finally {
			// Report
			log.info("AWS EFS finished : {} prices", priceCounter);
			nextStep(context, null, 1);
		}
	}

	private void instalEfsPrice(final ProvStorageType efs, final Map<ProvLocation, ProvStoragePrice> previous,
			AwsCsvPrice csv, final ProvLocation location) {
		// Update the price as needed
		final ProvStoragePrice price = previous.computeIfAbsent(location, r -> {
			final ProvStoragePrice p = new ProvStoragePrice();
			p.setLocation(r);
			p.setType(efs);
			p.setCode(csv.getSku());
			return p;
		});
		price.setCode(csv.getSku());
		saveAsNeeded(price, csv.getPricePerUnit(), spRepository::save);
	}
}
