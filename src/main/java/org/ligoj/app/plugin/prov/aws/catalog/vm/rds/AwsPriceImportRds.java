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
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.prov.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.aws.catalog.vm.AbstractAwsPriceImportVm;
import org.ligoj.app.plugin.prov.dao.ProvQuoteDatabaseRepository;
import org.ligoj.app.plugin.prov.model.AbstractCodedEntity;
import org.ligoj.app.plugin.prov.model.ProvDatabasePrice;
import org.ligoj.app.plugin.prov.model.ProvDatabaseType;
import org.ligoj.app.plugin.prov.model.ProvQuoteDatabase;
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
public class AwsPriceImportRds
		extends AbstractAwsPriceImportVm<ProvDatabaseType, ProvDatabasePrice, AwsRdsPrice, ProvQuoteDatabase> {

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
				.collect(Collectors.toConcurrentMap(AbstractCodedEntity::getCode, Function.identity())));
		context.setValidDatabaseType(Pattern.compile(configuration.get(CONF_DTYPE, ".*")));
		final var apiPrice = configuration.get(CONF_URL_RDS_PRICES, RDS_PRICES);
		newStream(context.getRegions().values()).forEach(region -> {
			nextStep(context, region.getName(), 1);
			// Get previous RDS storage and instance prices for this location
			final var localContext = new LocalRdsContext(context, dtRepository, dpRepository, qdRepository, region,
					TERM_ON_DEMAND, TERM_RESERVED);
			localContext.setPreviousStorage(spRepository.findAll(context.getNode().getId(), region.getName()).stream()
					.collect(Collectors.toMap(ProvStoragePrice::getCode, Function.identity())));
			install(localContext, apiPrice);
			localContext.cleanup();
		});
	}

	/**
	 * Download and install EC2 prices from AWS server.
	 *
	 * @param context  The update context.
	 * @param apiPrice The CSV API price URL.
	 */
	private void install(final LocalRdsContext context, final String apiPrice) {
		// Track the created instance to cache partial costs
		final var region = context.getRegion();
		final var endpoint = apiPrice.replace("%s", region.getName());
		final var oldCount = context.getLocals().size();
		log.info("AWS RDS OnDemand/Reserved import started for region {}@{} ...", region, endpoint);

		// Get the remote prices stream
		try (var reader = new BufferedReader(new InputStreamReader(new URI(endpoint).toURL().openStream()))) {
			// Pipe to the CSV reader
			final var csvReader = new CsvForBeanRds(reader);

			// Build the AWS instance prices from the CSV
			var csv = csvReader.read();
			while (csv != null) {
				// Persist this price
				installRds(context, csv);

				// Read the next one
				csv = csvReader.read();
			}
			// Purge the rate codes
			purgePrices(context);
		} catch (final IOException | URISyntaxException use) {
			// Something goes wrong for this region, stop it
			log.warn("AWS RDS OnDemand/Reserved import failed for region {}", region.getName(), use);
		} finally {
			// Report
			log.info("AWS RDS OnDemand/Reserved import finished for region {}: {} prices ({})", region.getName(),
					context.getPrices().size(), String.format("%+d", context.getPrices().size() - oldCount));
		}
	}

	/**
	 * Install the install the database type (if needed), the instance price type (if needed) and the price.
	 *
	 * @param context The update context.
	 * @param csv     The current CSV entry.
	 */
	private void installRds(final LocalRdsContext context, final AwsRdsPrice csv) {
		if ("Database Instance".equals(csv.getFamily())) {
			// Filter type
			if (!isEnabledDatabase(context, csv.getInstanceType())) {
				return;
			}

			// Up-front management
			if (handleUpFront(context, csv)) {
				return;
			}

			// No up-front, cost is fixed
			final var price = newPrice(context, csv);
			final var cost = csv.getPricePerUnit() * context.getHoursMonth();
			saveAsNeeded(context, price, cost, dpRepository);
		} else {
			// Database storage
			final var type = installStorageType(context, csv);
			final var price = context.getPreviousStorage().computeIfAbsent(csv.getSku(), c -> {
				final var p = new ProvStoragePrice();
				p.setCode(c);
				p.setType(type);
				p.setLocation(context.getRegion());
				return p;
			});

			// Update the price as needed
			saveAsNeeded(context, price, csv.getPricePerUnit(), spRepository);
		}
	}

	/**
	 * Install the RDS storage type as needed, and return it.
	 */
	private final ProvStorageType installStorageType(final LocalRdsContext context, final AwsRdsPrice csv) {
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
			newType.setCode(n);
			return newType;
		});

		// Merge the updated statistics
		return copyAsNeeded(context, type, t -> {
			final var ssd = "SSD".equals(csv.getStorage());
			t.setName(type.getCode());
			t.setDescription(csv.getVolume());
			t.setMinimal(toInteger(csv.getSizeMin()));
			t.setMaximal(toInteger(csv.getSizeMax()));
			t.setEngine(engine == null ? null : engine.toUpperCase(Locale.ENGLISH));
			t.setDatabaseType("%");
			t.setOptimized(ssd ? ProvStorageOptimized.IOPS : null);
			t.setLatency(ssd ? Rate.BEST : Rate.MEDIUM);
		}, stRepository);
	}

	@Override
	protected void copy(final AwsRdsPrice csv, final ProvDatabasePrice p) {
		p.setEngine(StringUtils.trimToNull(csv.getEngine().toUpperCase(Locale.ENGLISH)));
		p.setEdition(StringUtils.trimToNull(StringUtils.trimToEmpty(csv.getEdition()).toUpperCase(Locale.ENGLISH)));
	}

	@Override
	protected Rate getRate(final String type, final AwsRdsPrice csv, final String name) {
		return super.getRate(type, csv, StringUtils.replaceOnce(name, "db\\.", ""));
	}
}
