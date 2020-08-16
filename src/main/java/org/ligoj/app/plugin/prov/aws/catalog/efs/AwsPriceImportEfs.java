/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.efs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
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
	public void install(final UpdateContext context) throws IOException {
		log.info("AWS EFS prices ...");
		importCatalogResource.nextStep(context.getNode().getId(), t -> t.setPhase("efs"));

		// Track the created instance to cache partial costs
		final var efs = stRepository.findAllBy(BY_NODE, context.getNode(), new String[] { "name" }, "efs").get(0);
		final var previous = spRepository.findByTypeName(context.getNode().getId(), "efs").stream()
				.collect(Collectors.toMap(ProvStoragePrice::getLocation, Function.identity()));

		var priceCounter = 0;
		// Get the remote prices stream
		try (var reader = new BufferedReader(
				new InputStreamReader(new URL(configuration.get(CONF_URL_EFS_PRICES, EFS_PRICES)).openStream()))) {
			// Pipe to the CSV reader
			final var csvReader = new CsvForBeanEfs(reader);

			// Build the AWS instance prices from the CSV
			var csv = csvReader.read();
			while (csv != null) {
				final var location = getRegionByHumanName(context, csv.getLocation());
				if (location != null) {
					// Supported location
					installEfsPrice(context, efs, previous, csv, location);
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

	private void installEfsPrice(final UpdateContext context, final ProvStorageType efs,
			final Map<ProvLocation, ProvStoragePrice> previous, AwsCsvPrice csv, final ProvLocation location) {
		// Update the price as needed
		final var price = previous.computeIfAbsent(location, r -> {
			final var p = new ProvStoragePrice();
			p.setLocation(r);
			p.setType(efs);
			p.setCode(csv.getSku());
			return p;
		});
		price.setCode(csv.getSku());
		saveAsNeeded(context, price, csv.getPricePerUnit(), spRepository);
	}
}
