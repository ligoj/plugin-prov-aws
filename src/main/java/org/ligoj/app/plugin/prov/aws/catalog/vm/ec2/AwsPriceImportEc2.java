/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.ec2;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.prov.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.prov.aws.catalog.AwsPriceImportBase;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.aws.catalog.vm.AbstractAwsPriceImportVmOs;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvInstanceType;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.ligoj.app.plugin.prov.model.ProvTenancy;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.bootstrap.core.SpringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

/**
 * The provisioning price service for AWS. Manage install or update of prices.
 */
@Slf4j
@Component
public class AwsPriceImportEc2 extends
		AbstractAwsPriceImportVmOs<ProvInstanceType, ProvInstancePrice, AwsEc2Price, ProvQuoteInstance, LocalEc2Context, CsvForBeanEc2> {

	/**
	 * Service code.
	 */
	private static final String SERVICE_CODE = "AmazonEC2";

	/**
	 * The EC2 spot price end-point, a JSON file. Contains the prices for all regions.
	 */
	private static final String EC2_PRICES_SPOT = "https://spot-price.s3.amazonaws.com/spot.js";

	/**
	 * API name of this service.
	 */
	private static final String API = "ec2";
	private static final String API_SPOT = API + "-spot";

	/**
	 * Configuration key used for {@link #EC2_PRICES_SPOT}
	 */
	public static final String CONF_URL_EC2_PRICES_SPOT = String.format(AwsPriceImportBase.CONF_URL_TMP_PRICES,
			API_SPOT);

	/**
	 * Configuration key used for enabled OS pattern names. When value is <code>null</code>, no restriction.
	 */
	public static final String CONF_OS = ProvAwsPluginResource.KEY + ":os";

	/**
	 * Configuration key used for enabled instance type pattern names. When value is <code>null</code>, no restriction.
	 */
	public static final String CONF_ITYPE = ProvAwsPluginResource.KEY + ":instance-type";

	/**
	 * Mapping from AWS software to standard form.
	 */
	private final Map<String, String> mapSoftware = new HashMap<>();

	@Override
	public void install(final UpdateContext context) throws IOException {
		context.setValidOs(Pattern.compile(configuration.get(CONF_OS, ".*"), Pattern.CASE_INSENSITIVE));
		context.setValidInstanceType(Pattern.compile(configuration.get(CONF_ITYPE, ".*"), Pattern.CASE_INSENSITIVE));

		// Install OnDemand and reserved prices
		installPrices(context, API, SERVICE_CODE, TERM_ON_DEMAND, TERM_RESERVED);

		// Install the SPOT EC2 prices
		nextStep(context, API_SPOT, null, 0);
		installJsonPrices(context, API_SPOT, configuration.get(CONF_URL_EC2_PRICES_SPOT, EC2_PRICES_SPOT),
				SpotPrices.class, r -> newProxy().installSpotPrices(context, r));

		nextStep(context, API_SPOT, null, 1);
	}

	@Override
	protected void installPrice(final LocalEc2Context context, final AwsEc2Price csv) {
		if (csv.getFamily().startsWith("Compute Instance")) {
			if (!handlePartialCost(context, csv)) {
				// No up-front, cost is fixed
				final var price = newPrice(context, csv);
				final var cost = csv.getPricePerUnit() * context.getHoursMonth();
				saveAsNeeded(context, price, cost, context.getPRepository());
			}
		} else {
			// Check the volume API
			final var type = context.getStorageTypes().get(context.getMapStorageToApi().getOrDefault(csv.getFamily(),
					StringUtils.trimToEmpty(csv.getVolume())));
			if (type == null) {
				log.info("Ignore unkonown volume type {}/{}", csv.getFamily(), csv.getVolume());
				return;
			}

			if (!csv.getFamily().startsWith("Storage")) {
				log.info("Ignore unkonown storage price type {}/{}", csv.getFamily(), csv.getVolume());
				return;
			}

			// = csv.getSku()
			final var code = context.getRegion().getName() + "-" + type.getName();
			final var price = context.getPreviousStorage().computeIfAbsent(code, c -> {
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

	@Override
	protected boolean isEnabled(final LocalEc2Context context, final AwsEc2Price csv) {
		return !csv.getFamily().startsWith("Compute Instance")
				|| super.isEnabled(context, csv) && "Used".equals(csv.getCapacityStatus()) && !"NA".equals(csv.getOs())
						&& !"Red Hat Enterprise Linux with HA".equals(csv.getOs());
	}

	@Override
	public AwsPriceImportEc2 newProxy() {
		return SpringUtils.getBean(AwsPriceImportEc2.class);
	}

	/**
	 * Create a new transactional (READ_UNCOMMITTED) process for spot prices in a specific region.
	 *
	 * @param gContext The current global context.
	 * @param r        The spot region.
	 */
	@Transactional(propagation = Propagation.SUPPORTS, isolation = Isolation.READ_UNCOMMITTED)
	public void installSpotPrices(final UpdateContext gContext, final SpotRegion r) {
		nextStep(gContext, "ec2 (spot)", r.getRegion(), 0);
		final var region = locationRepository.findByName(gContext.getNode().getId(), r.getRegion());

		// Get previous prices for this location
		final var context = newContext(gContext, region, TERM_SPOT, TERM_SPOT);
		final var term = newSpotInstanceTerm(context);
		r.getInstanceTypes().stream().flatMap(t -> t.getSizes().stream())
				.filter(t -> isEnabledType(gContext, t.getName())).forEach(t -> {
					if (context.getLocalTypes().containsKey(t.getName())) {
						installSpotPrices(context, t, term);
					} else {
						// Unavailable instances type of spot are ignored
						log.warn("Instance {} is referenced from spot but not available", t.getName());
					}
				});

		// Purge the SKUs
		purgePrices(context);
		nextStep(gContext, "ec2 (spot)", r.getRegion(), 0);
	}

	/**
	 * EC2 spot installer. Install the instance type (if needed), the instance price type (if needed) and the price.
	 *
	 * @param context The update context.
	 * @param json    The current JSON entry.
	 * @param term    The related AWS Spot instance price term.
	 */
	private void installSpotPrices(final LocalEc2Context context, final AwsEc2SpotPrice json,
			final ProvInstancePriceTerm term) {
		final var region = context.getRegion();
		final var type = context.getLocalTypes().get(json.getName());
		final var baseCode = TERM_SPOT_CODE + "-" + region.getName() + "-" + type.getName() + "-";
		json.getOsPrices().stream().filter(op -> !StringUtils.startsWithIgnoreCase(op.getPrices().get("USD"), "N/A"))
				.peek(op -> op.setOs(op.getName().equals("mswin") ? VmOs.WINDOWS : VmOs.LINUX))
				.filter(op -> isEnabledOs(context, op.getOs())).forEach(op -> {

					// Build the key for this spot
					final var price = context.getLocals().computeIfAbsent(baseCode + op.getOs(), c -> {
						final var p = context.newPrice(c);
						p.setType(type);
						p.setTerm(term);
						p.setTenancy(ProvTenancy.SHARED);
						p.setOs(op.getOs());
						p.setLocation(region);
						p.setPeriod(0);
						return p;
					});

					// Update the price as needed
					final var cost = Double.parseDouble(op.getPrices().get("USD"));
					saveAsNeeded(context, price, cost * context.getHoursMonth(), ipRepository);
				});
	}

	@Override
	protected CsvForBeanEc2 newReader(final BufferedReader reader) throws IOException {
		return new CsvForBeanEc2(reader);
	}

	@Override
	protected void copy(final AwsEc2Price csv, final ProvInstancePrice p) {
		super.copy(csv, p);
		final var software = ObjectUtils.defaultIfNull(csv.getSoftware(), "");
		p.setSoftware(StringUtils.trimToNull(mapSoftware.computeIfAbsent(software, String::toUpperCase)));
		p.setTenancy(ProvTenancy.valueOf(StringUtils.upperCase(csv.getTenancy())));
	}

	@Override
	protected void copySavingsPlan(final ProvInstancePrice odPrice, final ProvInstancePrice p) {
		super.copySavingsPlan(odPrice, p);
		p.setSoftware(odPrice.getSoftware());
		p.setTenancy(odPrice.getTenancy());
	}

	/**
	 * Read the EC2 software name from AWS to standard name.
	 *
	 * @throws IOException When the JSON mapping file cannot be read.
	 */
	@PostConstruct
	public void initSoftwareNormalize() throws IOException {
		mapSoftware.putAll(toMap("aws-software.json", MAP_STR));
	}

	@Override
	protected LocalEc2Context newContext(final UpdateContext gContext, final ProvLocation region, final String term1,
			final String term2) {
		return new LocalEc2Context(gContext, iptRepository, itRepository, ipRepository, qiRepository, region, term1,
				term2);
	}
}
