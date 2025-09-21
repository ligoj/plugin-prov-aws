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
import org.apache.commons.lang3.Strings;
import org.ligoj.app.plugin.prov.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.aws.catalog.vm.AbstractAwsPriceImportVm;
import org.ligoj.app.plugin.prov.catalog.AbstractUpdateContext;
import org.ligoj.app.plugin.prov.catalog.Co2Data;
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
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * The provisioning price service for RDS AWS. Manage installation or update of prices.
 */
@Component
@Slf4j
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

	/**
	 * <code>db.r5b.2xlarge</code> instance type like. See
	 * <a href="https://aws.amazon.com/about-aws/whats-new/2021/09/amazon-rds-r5b-mysql-postgresql-databases/">RDS</a>
	 */
	private static final Pattern RDS_B_INSTANCE_TYPE = Pattern.compile("db\\.[^.]+b\\..*$");

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
			if (type == null) {
				// Ignore this type
				return;
			}

			log.info("Add Database storage sku={}, type={}, location={}", csv.getSku(), type.getName(),
					context.getRegion().getName());
			syncAdd(context.getPreviousStorage(), csv.getSku(), c -> {
				final var p = new ProvStoragePrice();
				p.setCode(c);
				p.setType(type);
				p.setLocation(context.getRegion());
				return p;
			}, p ->

					// Update the price as needed
					saveAsNeeded(context, p, csv.getPricePerUnit(), spRepository));
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
			} else { /* Any */
				name = "rds-gp-aurora-mysql";
				engine = "Aurora MySQL";
			}
		} else {
			final String nameSuffix;
			if ("Any".equals(StringUtils.defaultIfBlank(csv.getEngine(), "Any"))) {
				engine = null;
				nameSuffix = "";
			} else {
				engine = csv.getEngine();
				nameSuffix = "-" + engine.toLowerCase(Locale.ENGLISH).replace(' ', '-');
			}
			if ("General Purpose".equals(csv.getVolume())) {
				name = "rds-gp" + nameSuffix;
			} else if ("Provisioned IOPS".equals(csv.getVolume())) {
				name = "rds-io" + nameSuffix;
			} else if ("Magnetic".equals(csv.getVolume())) {
				name = "rds-magnetic" + nameSuffix;
			} else {
				log.error("Unknown RDS storage type {}/{}/{}", csv.getVolume(), csv.getEngine(), csv.getSku());
				return null;
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
			final var ssd = Strings.CS.contains(csv.getStorage(), "SSD");
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
		return super.getRate(type, csv, Strings.CS.replaceOnce(name, "db\\.", ""));
	}

	@Override
	protected Co2Data getCo2(final AbstractUpdateContext context, final String type) {
		if (!context.getCo2DataSet().containsKey(type)) {
			// Try to resolve the type without the "db." prefix
			return super.getCo2(context, Strings.CS.removeStart(type, "db."));
		}
		return super.getCo2(context, type);
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
	protected CsvForBeanRds newReader(final BufferedReader reader) throws IOException {
		return new CsvForBeanRds(reader);
	}

	@Override
	protected void copy(final LocalRdsContext context, final AwsRdsPrice csv, final ProvDatabaseType t) {
		super.copy(context, csv, t);
		if (RDS_B_INSTANCE_TYPE.matcher(t.getName()).matches()) {
			// Override storage rate for r5b like type instances
			t.setStorageRate(Rate.BEST);
		}
	}

	@Override
	protected boolean priceMatchConstraintButType(final ProvDatabasePrice p1, ProvDatabasePrice p2) {
		return super.priceMatchConstraintButType(p1, p2)
				&& Strings.CS.equals(p1.getEngine(), p2.getEngine());
	}

}
