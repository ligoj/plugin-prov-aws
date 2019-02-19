/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.rds;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Formatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.aws.catalog.vm.AbstractAwsPriceImportVm;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AbstractAwsEc2Price;
import org.ligoj.app.plugin.prov.model.ProvDatabasePrice;
import org.ligoj.app.plugin.prov.model.ProvDatabaseType;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvStorageOptimized;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.app.plugin.prov.model.Rate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * The provisioning price service for RDS AWS. Manage install or update of prices.
 */
@Slf4j
@Component
public class AwsPriceImportRds extends AbstractAwsPriceImportVm {

	/**
	 * The RDS reserved and on-demand price end-point, a CSV file, accepting the region code with {@link Formatter}
	 */
	private static final String RDS_PRICES = "https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonRDS/current/%s/index.csv";

	/**
	 * Configuration key used for {@link #RDS_PRICES}
	 */
	public static final String CONF_URL_RDS_PRICES = String.format(CONF_URL_API_PRICES, "rds");

	@Override
	public void install(final UpdateContext context) throws IOException, URISyntaxException {
		context.setDatabaseTypes(dtRepository.findAllBy(BY_NODE, context.getNode()).stream()
				.collect(Collectors.toMap(ProvDatabaseType::getName, Function.identity())));
		importCatalogResource.nextStep(context.getNode().getId(), t -> t.setPhase("rds"));
		context.getRegions().values().forEach(region -> {
			nextStep(context.getNode(), region.getName(), 1);
			// Get previous RDS storage and instance prices for this location
			context.setPreviousDatabase(dpRepository.findAll(context.getNode().getId(), region.getName()).stream()
					.collect(Collectors.toMap(ProvDatabasePrice::getCode, Function.identity())));
			context.setPreviousStorage(spRepository.findAll(context.getNode().getId(), region.getName()).stream()
					.collect(Collectors.toMap(ProvStoragePrice::getCode, Function.identity())));
			installRdsPrices(context, region);
		});
		context.getDatabaseTypes().clear();
		context.getPreviousDatabase().clear();
	}

	/**
	 * Download and install EC2 prices from AWS server.
	 *
	 * @param context
	 *            The update context.
	 * @param region
	 *            The region to fetch.
	 * @return The amount installed EC2 instances.
	 */
	private int installRdsPrices(final UpdateContext context, final ProvLocation region) {
		// Track the created instance to cache partial costs
		context.setPartialCostRds(new HashMap<>());
		final String endpoint = configuration.get(CONF_URL_RDS_PRICES, RDS_PRICES).replace("%s", region.getName());
		log.info("AWS RDS OnDemand/Reserved import started for region {}@{} ...", region, endpoint);
		int priceCounter = 0;

		// Get the remote prices stream
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(new URI(endpoint).toURL().openStream()))) {
			// Pipe to the CSV reader
			final CsvForBeanRds csvReader = new CsvForBeanRds(reader);

			// Build the AWS instance prices from the CSV
			AwsRdsPrice csv = csvReader.read();
			while (csv != null) {
				// Complete the region human name associated to the API one
				region.setDescription(csv.getLocation());

				// Persist this price
				priceCounter += installRds(context, csv, region);

				// Read the next one
				csv = csvReader.read();
			}
		} catch (final IOException | URISyntaxException use) {
			// Something goes wrong for this region, stop for this region
			log.info("AWS RDS OnDemand/Reserved import failed for region {}", region.getName(), use);
		} finally {
			// Report
			log.info("AWS RDS OnDemand/Reserved import finished for region {} : {} instance, {} price types, {} prices",
					region.getName(), context.getInstanceTypes().size(), context.getPriceTerms().size(), priceCounter);
		}

		// Return the available instances types
		return priceCounter;
	}

	/**
	 * Install the install the instance type (if needed), the instance price type (if needed) and the price.
	 *
	 * @param context
	 *            The update context.
	 * @param csv
	 *            The current CSV entry.
	 * @param region
	 *            The current region.
	 * @return The amount of installed prices. Only for the report.
	 */
	private int installRds(final UpdateContext context, final AwsRdsPrice csv, final ProvLocation region) {
		// Up-front, partial or not
		int priceCounter = 0;
		if (UPFRONT_MODE.matcher(StringUtils.defaultString(csv.getPurchaseOption())).find()) {
			// Up-front ALL/PARTIAL
			final Map<String, AwsRdsPrice> partialCost = context.getPartialCostRds();
			final String code = csv.getSku() + csv.getOfferTermCode() + region.getName();
			if (partialCost.containsKey(code)) {
				handleUpfront(newRdsPrice(context, csv, region), csv, partialCost.get(code), dpRepository);

				// The price is completed, cleanup
				partialCost.remove(code);
				priceCounter++;
			} else {
				// First time, save this entry for a future completion
				partialCost.put(code, csv);
			}
		} else if ("Database Instance".equals(csv.getFamily())) {
			// No up-front, cost is fixed
			priceCounter++;
			final ProvDatabasePrice price = newRdsPrice(context, csv, region);
			final double cost = csv.getPricePerUnit() * HOUR_TO_MONTH;
			saveAsNeeded(price, round3Decimals(cost), p -> {
				p.setCostPeriod(round3Decimals(cost * p.getTerm().getPeriod()));
				dpRepository.save(p);
			});
		} else {
			// Database storage
			priceCounter++;
			final ProvStorageType type = installRdsStorageTypeAsNeeded(context, csv);
			final ProvStoragePrice price = context.getPreviousStorage().computeIfAbsent(csv.getSku(), s -> {
				final ProvStoragePrice p = new ProvStoragePrice();
				p.setType(type);
				p.setCode(csv.getSku());
				p.setLocation(region);
				return p;
			});

			// Update the price as needed
			saveAsNeeded(price, csv.getPricePerUnit(), spRepository::save);
		}
		return priceCounter;
	}

	/**
	 * Install the RDS storage type as needed, and return it.
	 */
	private final ProvStorageType installRdsStorageTypeAsNeeded(final UpdateContext context, final AwsRdsPrice csv) {
		// RDS Storage type is composition of
		final String name;
		final String engine;
		if ("General Purpose-Aurora".equals(csv.getVolume())) {
			if ("Aurora PostgreSQL".equals(csv.getEngine())) {
				name = "gp-aurora-postgresql";
				engine = "Aurora PostgreSQL";
			} else {
				name = "gp-aurora-mysql";
				engine = "Aurora MySQL";
			}
		} else {
			engine = null;
			if ("General Purpose".equals(csv.getVolume())) {
				name = "gp-rds";
			} else if ("Provisioned IOPS".equals(csv.getVolume())) {
				name = "io-rds";
			} else {
				name = "magnetic-rds";
			}
		}

		return context.getStorageTypes().computeIfAbsent(name, n -> {
			final ProvStorageType entity = new ProvStorageType();
			final boolean ssd = "SSD".equals(csv.getStorage());
			entity.setNode(context.getNode());
			entity.setName(n);
			entity.setDescription(csv.getVolume());
			entity.setMinimal(toInteger(csv.getSizeMin()));
			entity.setMaximal(toInteger(csv.getSizeMax()));
			entity.setEngine(engine == null ? null : engine.toUpperCase(Locale.ENGLISH));
			entity.setDatabaseCompatible(true);
			entity.setOptimized(ssd ? ProvStorageOptimized.IOPS : null);
			entity.setLatency(ssd ? Rate.BEST : Rate.MEDIUM);
			stRepository.save(entity);
			return entity;
		});
	}

	/**
	 * Install or update a RDS price
	 */
	private ProvDatabasePrice newRdsPrice(final UpdateContext context, final AwsRdsPrice csv,
			final ProvLocation region) {
		final ProvDatabaseType type = installInstanceType(context, csv, context.getDatabaseTypes(),
				ProvDatabaseType::new, dtRepository);
		return context.getPreviousDatabase().computeIfAbsent(toCode(csv), c -> {
			final ProvDatabasePrice p = new ProvDatabasePrice();
			copy(context, csv, region, c, p, type);
			p.setEngine(StringUtils.trimToNull(csv.getEngine().toUpperCase(Locale.ENGLISH)));
			p.setEdition(StringUtils.trimToNull(StringUtils.trimToEmpty(csv.getEdition()).toUpperCase(Locale.ENGLISH)));
			return p;
		});
	}

	@Override
	protected Rate getRate(final String type, final AbstractAwsEc2Price csv, final String name) {
		return super.getRate(type, csv, StringUtils.replaceOnce(name, "db\\.", ""));
	}
}
