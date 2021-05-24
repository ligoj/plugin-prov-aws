/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.rds;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.prov.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.aws.catalog.vm.AbstractAwsPriceImportVm;
import org.ligoj.app.plugin.prov.catalog.AbstractUpdateContext;
import org.ligoj.app.plugin.prov.dao.ProvQuoteDatabaseRepository;
import org.ligoj.app.plugin.prov.model.AbstractCodedEntity;
import org.ligoj.app.plugin.prov.model.ProvDatabasePrice;
import org.ligoj.app.plugin.prov.model.ProvDatabaseType;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvQuoteDatabase;
import org.ligoj.app.plugin.prov.model.ProvStorageOptimized;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.app.plugin.prov.model.Rate;
import org.ligoj.bootstrap.core.SpringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The provisioning price service for RDS AWS. Manage install or update of prices.
 */
@Component
public class AwsPriceImportRds extends
		AbstractAwsPriceImportVm<ProvDatabaseType, ProvDatabasePrice, AwsRdsPrice, ProvQuoteDatabase, LocalRdsContext, CsvForBeanRds> {

	/**
	 * Service code.
	 */
	private static final String SERVICE_CODE = "AmazonRDS";

	/**
	 * API name of this service.
	 */
	private static final String API = "rds";

	/**
	 * Configuration key used for enabled database type pattern names. When value is <code>null</code>, no restriction.
	 */
	public static final String CONF_DTYPE = ProvAwsPluginResource.KEY + ":database-type";

	/**
	 * Configuration key used for enabled database engine pattern names. When value is <code>null</code>, no
	 * restriction.
	 */
	public static final String CONF_ETYPE = ProvAwsPluginResource.KEY + ":database-engine";

	@Autowired
	protected ProvQuoteDatabaseRepository qbRepository;

	@Override
	public void install(final UpdateContext context) throws IOException {
		nextStep(context, API, null, 0);
		context.setDatabaseTypes(dtRepository.findAllBy(BY_NODE, context.getNode()).stream()
				.collect(Collectors.toConcurrentMap(AbstractCodedEntity::getCode, Function.identity())));
		context.setValidDatabaseType(Pattern.compile(configuration.get(CONF_DTYPE, ".*")));
		context.setValidDatabaseEngine(Pattern.compile(configuration.get(CONF_ETYPE, ".*")));
		installPrices(context, API, SERVICE_CODE, TERM_ON_DEMAND, TERM_RESERVED);
	}

	@Override
	public AwsPriceImportRds newProxy() {
		return SpringUtils.getBean(AwsPriceImportRds.class);
	}

	@Override
	protected boolean isEnabled(final LocalRdsContext context, final AwsRdsPrice csv) {
		return !"Database Instance".equals(csv.getFamily())
				|| (isEnabledType(context, csv.getInstanceType()) && isEnabledEngine(context, csv.getEngine()));
	}

	@Override
	protected void installPrice(final LocalRdsContext context, final AwsRdsPrice csv) {
		if ("Database Instance".equals(csv.getFamily())) {
			// Up-front management
			if (handlePartialCost(context, csv)) {
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
	private ProvStorageType installStorageType(final LocalRdsContext context, final AwsRdsPrice csv) {
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

	@Override
	protected LocalRdsContext newContext(final UpdateContext gContext, final ProvLocation region, final String term1,
			final String term2) {
		return new LocalRdsContext(gContext, iptRepository, dtRepository, dpRepository, qdRepository, region, term1,
				term2);
	}

	@Override
	protected boolean isEnabledType(final AbstractUpdateContext context, final String type) {
		return isEnabledDatabaseType(context, type);
	}

	@Override
	protected CsvForBeanRds newReader(BufferedReader reader) throws IOException {
		return new CsvForBeanRds(reader);
	}
}
