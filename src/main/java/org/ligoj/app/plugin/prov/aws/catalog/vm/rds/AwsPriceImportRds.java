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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.prov.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.aws.catalog.vm.AbstractAwsPriceImportVm;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AbstractAwsEc2Price;
import org.ligoj.app.plugin.prov.dao.ProvQuoteDatabaseRepository;
import org.ligoj.app.plugin.prov.model.ProvDatabasePrice;
import org.ligoj.app.plugin.prov.model.ProvDatabaseType;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvStorageOptimized;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.app.plugin.prov.model.Rate;
import org.springframework.beans.factory.annotation.Autowired;
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
	 * Configuration key used for enabled database type pattern names. When value is <code>null</code>, no restriction.
	 */
	public static final String CONF_DTYPE = ProvAwsPluginResource.KEY + ":database-type";

	/**
	 * Configuration key used for {@link #RDS_PRICES}
	 */
	public static final String CONF_URL_RDS_PRICES = String.format(CONF_URL_API_PRICES, "rds");

	@Autowired
	protected ProvQuoteDatabaseRepository qbRepository;

	@Override
	public void install(final UpdateContext context) throws IOException, URISyntaxException {
		importCatalogResource.nextStep(context.getNode().getId(), t -> t.setPhase("rds"));
		context.setDatabaseTypes(dtRepository.findAllBy(BY_NODE, context.getNode()).stream()
				.collect(Collectors.toConcurrentMap(ProvDatabaseType::getName, Function.identity())));
		context.setValidDatabaseType(Pattern.compile(configuration.get(CONF_DTYPE, ".*")));
		final var apiPrice = configuration.get(CONF_URL_RDS_PRICES, RDS_PRICES);
		newStream(context.getRegions().values()).forEach(region -> {
			nextStep(context, region.getName(), 1);
			// Get previous RDS storage and instance prices for this location
			final var localContext = new UpdateContext();
			localContext.setPreviousDatabase(dpRepository.findAll(context.getNode().getId(), region.getName()).stream()
					.collect(Collectors.toMap(ProvDatabasePrice::getCode, Function.identity())));
			localContext.setPreviousStorage(spRepository.findAll(context.getNode().getId(), region.getName()).stream()
					.collect(Collectors.toMap(ProvStoragePrice::getCode, Function.identity())));
			install(context, region, localContext, apiPrice);
			localContext.getPreviousDatabase().clear();
			localContext.getPreviousStorage().clear();
		});
		context.getDatabaseTypes().clear();
	}

	/**
	 * Download and install EC2 prices from AWS server.
	 *
	 * @param context The update context.
	 * @param region  The region to fetch.
	 */
	private void install(final UpdateContext context, final ProvLocation region, final UpdateContext localContext,
			final String apiPrice) {
		// Track the created instance to cache partial costs
		localContext.setPartialCostRds(new HashMap<>());
		final var endpoint = apiPrice.replace("%s", region.getName());
		log.info("AWS RDS OnDemand/Reserved import started for region {}@{} ...", region, endpoint);

		// Get the remote prices stream
		try (var reader = new BufferedReader(new InputStreamReader(new URI(endpoint).toURL().openStream()))) {
			// Pipe to the CSV reader
			final var csvReader = new CsvForBeanRds(reader);

			// Build the AWS instance prices from the CSV
			var csv = csvReader.read();
			while (csv != null) {
				// Complete the region human name associated to the API one
				region.setDescription(csv.getLocation());

				// Persist this price
				installRds(context, csv, region, localContext);

				// Read the next one
				csv = csvReader.read();
			}
			// Purge the SKUs
			purgeSku(context, localContext);
		} catch (final IOException | URISyntaxException use) {
			// Something goes wrong for this region, stop for this region
			log.info("AWS RDS OnDemand/Reserved import failed for region {}", region.getName(), use);
		} finally {
			// Report
			log.info(
					"AWS RDS OnDemand/Reserved import finished for region {} : {} databases, {} price types, {} prices",
					region.getName(), context.getDatabaseTypes().size(), context.getPriceTerms().size(),
					localContext.getActualCodes().size());
		}
	}

	/**
	 * Install the install the database type (if needed), the instance price type (if needed) and the price.
	 *
	 * @param context The update context.
	 * @param csv     The current CSV entry.
	 * @param region  The current region.
	 * @param context The local update context.
	 */
	private void installRds(final UpdateContext context, final AwsRdsPrice csv, final ProvLocation region,
			final UpdateContext localContext) {
		// Filter type
		if (csv.getInstanceType() != null && !isEnabledDatabase(context, csv.getInstanceType())) {
			return;
		}

		// Add this code to the existing SKU codes
		final var code = toCode(csv);
		localContext.getActualCodes().add(code);

		// Up-front, partial or not
		if (UPFRONT_MODE.matcher(StringUtils.defaultString(csv.getPurchaseOption())).find()) {
			// Up-front ALL/PARTIAL
			final Map<String, AwsRdsPrice> partialCost = localContext.getPartialCostRds();
			if (partialCost.containsKey(code)) {
				handleUpfront(context, newRdsPrice(context, csv, region, localContext), csv, partialCost.get(code),
						dpRepository);

				// The price is completed, cleanup
				partialCost.remove(code);
			} else {
				// First time, save this entry for a future completion
				partialCost.put(code, csv);
			}
		} else if ("Database Instance".equals(csv.getFamily())) {
			// No up-front, cost is fixed
			final var price = newRdsPrice(context, csv, region, localContext);
			final var cost = csv.getPricePerUnit() * context.getHoursMonth();
			saveAsNeeded(price, round3Decimals(cost), p -> {
				p.setCostPeriod(round3Decimals(cost * p.getTerm().getPeriod()));
				dpRepository.save(p);
			});
		} else {
			// Database storage
			final var type = installStorageType(context, csv);
			final var price = localContext.getPreviousStorage().computeIfAbsent(csv.getSku(), c -> {
				final ProvStoragePrice p = new ProvStoragePrice();
				p.setType(type);
				p.setCode(c);
				p.setLocation(region);
				return p;
			});

			// Update the price as needed
			saveAsNeeded(price, csv.getPricePerUnit(), spRepository::save);
		}
	}

	/**
	 * Remove SKU that were present in the context and not refresh with this update.
	 * 
	 * @param context      The update context.
	 * @param localContext The local update context.
	 */
	private void purgeSku(final UpdateContext context, final UpdateContext localContext) {
		purgeSku(context, localContext, localContext.getPreviousDatabase(), dpRepository, qbRepository);
	}

	/**
	 * Install the RDS storage type as needed, and return it.
	 */
	private final ProvStorageType installStorageType(final UpdateContext context, final AwsRdsPrice csv) {
		// RDS Storage type is composition of
		final String name;
		final String engine;
		if ("General Purpose-Aurora".equals(csv.getVolume())) {
			if ("Aurora PostgreSQL".equals(csv.getEngine())) {
				name = "rds-gp-aurora-postgresql";
				engine = "Aurora PostgreSQL";
			} else {
				name = "rds-gp-aurora-mysql";
				engine = "Aurora MySQL";
			}
		} else {
			engine = null;
			if ("General Purpose".equals(csv.getVolume())) {
				name = "rds-gp";
			} else if ("Provisioned IOPS".equals(csv.getVolume())) {
				name = "rds-io";
			} else {
				name = "rds-magnetic";
			}
		}

		// Create as needed
		final var type = context.getStorageTypes().computeIfAbsent(name, n -> {
			final var newType = new ProvStorageType();
			newType.setNode(context.getNode());
			newType.setName(n);
			return newType;
		});

		// Merge the updated statistics
		return context.getStorageTypesMerged().computeIfAbsent(name, n -> {
			final var ssd = "SSD".equals(csv.getStorage());
			type.setDescription(csv.getVolume());
			type.setMinimal(toInteger(csv.getSizeMin()));
			type.setMaximal(toInteger(csv.getSizeMax()));
			type.setEngine(engine == null ? null : engine.toUpperCase(Locale.ENGLISH));
			type.setDatabaseType("%");
			type.setOptimized(ssd ? ProvStorageOptimized.IOPS : null);
			type.setLatency(ssd ? Rate.BEST : Rate.MEDIUM);
			stRepository.save(type);
			return type;
		});
	}

	/**
	 * Install or update a RDS price
	 */
	private ProvDatabasePrice newRdsPrice(final UpdateContext context, final AwsRdsPrice csv, final ProvLocation region,
			final UpdateContext localContext) {
		final var type = installInstanceType(context, csv, context.getDatabaseTypes(), ProvDatabaseType::new,
				dtRepository);
		final var term = installInstancePriceTerm(context, csv);
		return localContext.getPreviousDatabase().computeIfAbsent(toCode(csv), c -> {
			final ProvDatabasePrice p = new ProvDatabasePrice();
			copy(context, csv, region, c, p, type, term);
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
