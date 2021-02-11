/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.ec2;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Formatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.model.Node;
import org.ligoj.app.plugin.prov.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.aws.catalog.vm.AbstractAwsPriceImportVmOs;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvInstanceType;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;
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
	 * The EC2 reserved and on-demand price end-point, a CSV file, accepting the region code with {@link Formatter}
	 */
	private static final String EC2_PRICES = "https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/%s/index.csv";

	/**
	 * The EC2 spot price end-point, a JSON file. Contains the prices for all regions.
	 */
	private static final String EC2_PRICES_SPOT = "https://spot-price.s3.amazonaws.com/spot.js";

	/**
	 * Configuration key used for {@link #EC2_PRICES}
	 */
	public static final String CONF_URL_EC2_PRICES = String.format(CONF_URL_API_PRICES, "ec2");

	/**
	 * Configuration key used for {@link #EC2_PRICES_SPOT}
	 */
	public static final String CONF_URL_EC2_PRICES_SPOT = String.format(CONF_URL_API_PRICES, "ec2-spot");

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
		importCatalogResource.nextStep(context.getNode().getId(), t -> t.setPhase("ec2"));
		context.setValidOs(Pattern.compile(configuration.get(CONF_OS, ".*"), Pattern.CASE_INSENSITIVE));
		context.setValidInstanceType(Pattern.compile(configuration.get(CONF_ITYPE, ".*"), Pattern.CASE_INSENSITIVE));
		installEc2(context, context.getNode(), "ec2", configuration.get(CONF_URL_EC2_PRICES, EC2_PRICES),
				configuration.get(CONF_URL_EC2_PRICES_SPOT, EC2_PRICES_SPOT));
	}

	private void installEc2(final UpdateContext context, final Node node, final String api, final String apiPrice,
			final String apiSpotPrice) throws IOException {

		// Install the EC2 (non spot) prices
		final var indexes = getSavingsPlanUrls(context);
		newStream(context.getRegions().values()).filter(region -> isEnabledRegion(context, region)).map(region -> {
			// Install OnDemand and reserved prices
			newProxy().installEC2Prices(context, region, api, apiPrice, indexes);
			return region;
		}).reduce((region1, region2) -> {
			nextStep(node, region2.getName(), 1);
			return region1;
		});

		// Install the SPOT EC2 prices
		installJsonPrices(context, api + "-spot", apiSpotPrice, SpotPrices.class,
				r -> newProxy().installSpotPrices(context, r));
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
		nextStep(gContext.getNode(), r.getRegion(), 1);
		final var region = locationRepository.findByName(gContext.getNode().getId(), r.getRegion());

		// Get previous prices for this location
		final var context = newContext(gContext, region, TERM_SPOT, TERM_SPOT);
		final var spotPriceType = newSpotInstanceTerm(context);
		r.getInstanceTypes().stream().flatMap(t -> t.getSizes().stream())
				.filter(t -> isEnabledType(gContext, t.getName())).forEach(t -> {
					if (context.getLocalTypes().containsKey(t.getName())) {
						installSpotPrices(context, t, spotPriceType);
					} else {
						// Unavailable instances type of spot are ignored
						log.warn("Instance {} is referenced from spot but not available", t.getName());
					}
				});

		// Purge the SKUs
		purgePrices(context);
	}

	/**
	 * EC2 spot installer. Install the instance type (if needed), the instance price type (if needed) and the price.
	 *
	 * @param context       The update context.
	 * @param json          The current JSON entry.
	 * @param spotPriceType The related AWS Spot instance price type.
	 */
	private void installSpotPrices(final LocalEc2Context context, final AwsEc2SpotPrice json,
			final ProvInstancePriceTerm spotPriceType) {
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
						p.setTerm(spotPriceType);
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
