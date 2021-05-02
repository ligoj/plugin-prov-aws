/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.s3;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.prov.aws.catalog.AbstractAwsImport;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.catalog.ImportCatalog;
import org.ligoj.app.plugin.prov.model.AbstractCodedEntity;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvStorageOptimized;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.app.plugin.prov.model.Rate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * The provisioning S3 price service for AWS. Manage install or update of prices.
 */
@Slf4j
@Component
public class AwsPriceImportS3 extends AbstractAwsImport implements ImportCatalog<UpdateContext> {

	/**
	 * The S3 price end-point, a CSV file. Multi-region.
	 */
	private static final String S3_PRICES = "https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonS3/current/index.csv";

	/**
	 * Configuration key used for {@link #S3_PRICES}
	 */
	public static final String CONF_URL_S3_PRICES = String.format(CONF_URL_API_PRICES, "s3");

	@Override
	public void install(final UpdateContext context) throws IOException {
		log.info("AWS S3 prices ...");
		nextStep(context, "s3", null, 0);

		// Track the created instance to cache partial costs
		final var previous = spRepository.findByLocation(context.getNode().getId(), null).stream()
				.filter(p -> p.getType().getCode().startsWith("s3") || "glacier".equals(p.getType().getCode()))
				.collect(Collectors.toMap(p2 -> p2.getLocation().getName() + p2.getType().getCode(),
						Function.identity()));
		context.setPreviousStorage(previous);
		context.setStorageTypes(previous.values().stream().map(ProvStoragePrice::getType).distinct()
				.collect(Collectors.toMap(AbstractCodedEntity::getCode, Function.identity())));

		var priceCounter = 0;
		// Get the remote prices stream
		try (var reader = new BufferedReader(
				new InputStreamReader(new URL(configuration.get(CONF_URL_S3_PRICES, S3_PRICES)).openStream()))) {
			// Pipe to the CSV reader
			final var csvReader = new CsvForBeanS3(reader);

			// Build the AWS storage prices from the CSV
			var csv = csvReader.read();
			while (csv != null) {
				final var location = getRegionByHumanName(context, csv.getLocation());
				if (location != null) {
					// Supported location
					installS3Price(context, csv, location);
					priceCounter++;
				}

				// Read the next one
				csv = csvReader.read();
			}
		} finally {
			// Report
			log.info("AWS S3 finished : {} prices", priceCounter);
			nextStep(context, "s3", null, 1);
		}
	}

	private void installS3Price(final UpdateContext context, final AwsS3Price csv, final ProvLocation location) {
		// Resolve the type
		final var name = context.getMapStorageToApi().get(csv.getVolumeType());
		if (name == null) {
			log.warn("Unknown storage type {}, ignored", csv.getVolumeType());
			return;
		}

		final var type = context.getStorageTypes().computeIfAbsent(name, n2 -> {
			// New storage type
			final var newType = new ProvStorageType();
			newType.setCode(n2);
			newType.setNode(context.getNode());
			return newType;
		});

		copyAsNeeded(context, type, t -> {
			// Update storage details
			t.setName(t.getCode());
			t.setAvailability(toPercent(csv.getAvailability()));
			t.setDurability9(StringUtils.countMatches(StringUtils.defaultString(csv.getDurability()), '9'));
			t.setOptimized(ProvStorageOptimized.DURABILITY);
			t.setNetwork("443/tcp");
			t.setLatency(name.equals("glacier") ? Rate.WORST : Rate.MEDIUM);
			t.setDescription("{\"class\":\"" + csv.getStorageClass() + "\",\"type\":\"" + csv.getVolumeType() + "\"}");
		}, stRepository);

		// Update the price as needed
		final var price = context.getPreviousStorage().computeIfAbsent(location.getName() + name, r -> {
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
