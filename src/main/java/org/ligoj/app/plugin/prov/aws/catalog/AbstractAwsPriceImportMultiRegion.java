/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.ligoj.app.plugin.prov.catalog.ImportCatalog;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.ligoj.app.plugin.prov.model.ProvStorageType;

import lombok.extern.slf4j.Slf4j;

/**
 * The provisioning S3 price service for AWS. Manage install or update of prices.
 */
@Slf4j
public abstract class AbstractAwsPriceImportMultiRegion<C extends AbstractAwsStoragePrice, R extends AbstractAwsCsvForBean<C>>
		extends AbstractAwsImport implements ImportCatalog<UpdateContext> {

	/**
	 * Install the prices from a single CSV file.
	 * 
	 * @param context     The current global catalog context
	 * @param api         The related API code.
	 * @param serviceCode The related service code. ie. <code>AmazonS3</code>.
	 * @throws IOException When the CSV file cannot be read.
	 */
	protected void installPrices(final UpdateContext context, final String api, final String serviceCode)
			throws IOException {
		log.info("AWS {} prices ...", api);
		nextStep(context, api, null, 0);

		// Retrieve the previous storage prices
		// See https://github.com/ligoj/plugin-prov-aws/issues/14
		context.setPreviousStorage(spRepository.findByLocation(context.getNode().getId(), null).stream().collect(
				Collectors.toMap(p2 -> p2.getLocation().getName() + p2.getType().getCode(), Function.identity())));

		var priceCounter = 0;
		// Get the remote prices stream
		final var url = getCsvUrl(context, context.getOffers().get(serviceCode).getCurrentVersionUrl());
		try (var reader = new BufferedReader(new InputStreamReader(new URL(url).openStream()))) {
			// Pipe to the CSV reader
			final var csvReader = newReader(reader);

			// Build the AWS storage prices from the CSV
			var csv = csvReader.read();
			while (csv != null) {
				final var location = getRegionByHumanName(context, csv.getLocation());
				if (location != null) {
					// Supported location
					installPrice(context, api, csv, location);
					priceCounter++;
				}

				// Read the next one
				csv = csvReader.read();
			}
		} finally {
			// Report
			log.info("AWS {} finished : {} prices", api, priceCounter);
			nextStep(context, api, null, 1);
		}
	}

	/**
	 * Return a CSV price reader instance.
	 *
	 * @param reader The input stream reader.
	 * @return A CSV price reader instance
	 * @throws IOException When the content cannot be read.
	 */
	protected abstract R newReader(final BufferedReader reader) throws IOException;

	/**
	 * Copy a CSV price entry to a price entity.
	 *
	 * @param csv The current CSV entry.
	 * @param t   The target type entity.
	 */
	protected abstract void update(final C csv, final ProvStorageType t);

	private void installPrice(final UpdateContext context, final String api, final C csv, final ProvLocation location) {
		// Resolve the type
		final var apis = context.getMapStorageToApi();
		final var name = apis.getOrDefault(api + "-" + csv.getStorageClass(),
				apis.get(api + "-" + csv.getVolumeType()));
		if (name == null) {
			log.warn("Unknown storage type {}, ignored", csv.getStorageClass());
			return;
		}
		final var type = context.getStorageTypes().get(name);
		copyAsNeeded(context, type, t -> update(csv, t), stRepository);

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
