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
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.catalog.ImportCatalog;
import org.ligoj.app.plugin.prov.model.AbstractCodedEntity;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * The provisioning EFS price service for AWS. Manage install or update of prices.
 * 
 * @see https://docs.aws.amazon.com/efs/latest/ug/storage-classes.html
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
		nextStep(context, "efs", null, 0);

		// Track the created instance to cache partial costs
		context.setStorageTypes(
				stRepository.findAllBy(BY_NODE, context.getNode()).stream().filter(t -> t.getCode().startsWith("efs"))
						.collect(Collectors.toMap(AbstractCodedEntity::getCode, Function.identity())));

		// See https://github.com/ligoj/plugin-prov-aws/issues/14
		final var previous = spRepository.findByLocation(context.getNode().getId(), null).stream()
				.filter(p -> p.getType().getCode().startsWith("efs")).collect(Collectors
						.toMap(p2 -> p2.getLocation().getName() + p2.getType().getCode(), Function.identity()));
		context.setPreviousStorage(previous);

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
					installEfsPrice(context, previous, csv, location);
					priceCounter++;
				}
				// Read the next one
				csv = csvReader.read();
			}
		} finally {
			// Report
			log.info("AWS EFS finished : {} prices", priceCounter);
			nextStep(context, "efs", null, 1);
		}
	}

	private void installEfsPrice(final UpdateContext context, final Map<String, ProvStoragePrice> previous,
			AwsEfsPrice csv, final ProvLocation location) {
		// Resolve the type
		final var name = context.getMapStorageToApi().get(csv.getStorageClass());
		if (name == null) {
			log.warn("Unknown storage type {}, ignored", csv.getStorageClass());
			return;
		}
		final var type = context.getStorageTypes().get(name);
		if (type == null) {
			log.warn("Unknown storage type {} resolved as {}, ignored", csv.getStorageClass(), name);
			return;
		}

		// Update the price as needed
		final var price = context.getPreviousStorage().computeIfAbsent(location.getName() + type.getCode(), c -> {
			final var p = new ProvStoragePrice();
			p.setCode(csv.getSku());
			return p;
		});

		copyAsNeeded(context, price, p -> {
			p.setLocation(location);
			p.setType(type);
		});
		saveAsNeeded(context, price, csv.getPricePerUnit(), spRepository);
	}
}
