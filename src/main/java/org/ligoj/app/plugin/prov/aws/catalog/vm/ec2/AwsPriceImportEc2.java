/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.ec2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Formatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.model.Node;
import org.ligoj.app.plugin.prov.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.aws.catalog.vm.AbstractAwsPriceImportVm;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.SavingsPlanPrice.SavingsPlanRate;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.SavingsPlanPrice.SavingsPlanTerm;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvInstanceType;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;
import org.ligoj.app.plugin.prov.model.ProvTenancy;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.bootstrap.core.SpringUtils;
import org.ligoj.bootstrap.core.curl.CurlProcessor;
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
public class AwsPriceImportEc2
		extends AbstractAwsPriceImportVm<ProvInstanceType, ProvInstancePrice, AwsEc2Price, ProvQuoteInstance> {

	private static final String TERM_SPOT_CODE = "spot-";
	private static final String TERM_SPOT = "Spot";

	private static final String TERM_COMPUTE_SP = "Compute Savings Plan";
	private static final String TERM_EC2_SP = "EC2 Savings Plan";

	/**
	 * The EC2 reserved and on-demand price end-point, a CSV file, accepting the region code with {@link Formatter}
	 */
	private static final String EC2_PRICES = "https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/%s/index.csv";

	/**
	 * The EC2 Savings Plan prices end-point, a JSON file. Contains the URL for each regions.
	 */
	private static final String SAVINGS_PLAN = "https://pricing.us-east-1.amazonaws.com/savingsPlan/v1.0/aws/AWSComputeSavingsPlan/current/region_index.json";

	/**
	 * The EC2 spot price end-point, a JSON file. Contains the prices for all regions.
	 */
	private static final String EC2_PRICES_SPOT = "https://spot-price.s3.amazonaws.com/spot.js";

	/**
	 * Configuration key used for {@link #EC2_PRICES}
	 */
	public static final String CONF_URL_EC2_PRICES = String.format(CONF_URL_API_PRICES, "ec2");

	/**
	 * Configuration key used for AWS URL Savings Plan prices.
	 */
	public static final String CONF_URL_API_SAVINGS_PLAN = String.format(CONF_URL_API_PRICES, "savings-plan");

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
		installEc2(context, context.getNode());
	}

	/**
	 * Create as needed a new {@link ProvInstancePriceTerm} for Spot.
	 */
	private ProvInstancePriceTerm newSpotInstanceTerm(final LocalEc2Context context) {
		final var term = getLocalTerm(context, "spot");

		// Update the properties only once
		return copyAsNeeded(context, term, t -> {
			t.setName(TERM_SPOT);
			t.setVariable(true);
			t.setEphemeral(true);
			t.setCode("spot");
			t.setConvertibleEngine(true);
			t.setConvertibleOs(true);
			t.setConvertibleType(true);
			t.setConvertibleFamily(true);
			t.setConvertibleLocation(true);
			t.setReservation(false);
		});
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
		final var baseCode = TERM_SPOT_CODE + region.getName() + "-" + type.getName() + "-";
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

	/**
	 * Return the savings plan URLs
	 */
	private Map<String, String> getSavingsPlanUrls(final UpdateContext context, final String endpoint)
			throws IOException {
		final var result = new HashMap<String, String>();
		log.info("AWS Savings plan indexes...");
		try (var curl = new CurlProcessor()) {
			// Get the remote prices stream
			final var rawJson = StringUtils.defaultString(curl.get(endpoint), "{\"regions\":[]}");
			final var baseParts = StringUtils.splitPreserveAllTokens(endpoint, "/");
			final var base = baseParts[0] + "//" + baseParts[2];

			// All regions are considered
			Arrays.stream(objectMapper.readValue(rawJson, SavingsPlanIndex.class).getRegions())
					.forEach(rConf -> result.put(rConf.getRegionCode(), base + rConf.getVersionUrl()));
			nextStep(context, null, 0);
		} finally {
			// Report
			log.info("AWS Savings plan indexes: {}", result.size());
			nextStep(context, null, 1);
		}

		return result;
	}

	/**
	 * Return the proxy of this class.
	 * 
	 * @return The proxy of this class.
	 */
	public AwsPriceImportEc2 newProxy() {
		return SpringUtils.getBean(AwsPriceImportEc2.class);
	}

	private void installEc2(final UpdateContext context, final Node node) throws IOException {

		// Install the EC2 (non spot) prices
		final var apiPrice = configuration.get(CONF_URL_EC2_PRICES, EC2_PRICES);
		final var indexes = getSavingsPlanUrls(context, configuration.get(CONF_URL_API_SAVINGS_PLAN, SAVINGS_PLAN));
		newStream(context.getRegions().values()).filter(region -> isEnabledRegion(context, region)).map(region -> {
			// Install OnDemand and reserved prices
			newProxy().installEC2Prices(context, region, apiPrice, indexes);
			return region;
		}).reduce((region1, region2) -> {
			nextStep(node, region2.getName(), 1);
			return region1;
		});

		// Install the SPOT EC2 prices
		installJsonPrices(context, "ec2-spot", configuration.get(CONF_URL_EC2_PRICES_SPOT, EC2_PRICES_SPOT),
				SpotPrices.class, r -> {
					newProxy().installSpotPrices(context, r);
				});
		context.getInstanceTypes().clear();
	}

	/**
	 * Create a new transactional (READ_UNCOMMITTED) process for spot prices in a specific region.
	 * 
	 * @param gContext The current global context.
	 * @param r        The spot region.
	 */
	@Transactional(propagation = Propagation.SUPPORTS, isolation = Isolation.READ_UNCOMMITTED)
	protected void installSpotPrices(final UpdateContext gContext, final SpotRegion r) {
		nextStep(gContext.getNode(), r.getRegion(), 1);
		final var region = locationRepository.findByName(gContext.getNode().getId(), r.getRegion());

		// Get previous prices for this location
		final var context = new LocalEc2Context(gContext, iptRepository, itRepository, ipRepository, qiRepository,
				region, TERM_SPOT, TERM_SPOT);
		final var spotPriceType = newSpotInstanceTerm(context);
		r.getInstanceTypes().stream().flatMap(t -> t.getSizes().stream())
				.filter(t -> isEnabledType(gContext, t.getName())).filter(t -> {
					final var availability = gContext.getInstanceTypes().containsKey(t.getName());
					if (!availability) {
						// Unavailable instances type of spot are ignored
						log.warn("Instance {} is referenced from spot but not available", t.getName());
					}
					return availability;
				}).forEach(j -> installSpotPrices(context, j, spotPriceType));

		// Purge the SKUs
		purgePrices(context);
	}

	/**
	 * Return the rate code without SKU part of the current On Demand session.
	 * 
	 * @param previousOd The previous On Demand prices.
	 * @return The rate code without SKU part of the current On Demand session. <code>null</code> when not found.
	 */
	protected String getOnDemandCode(final Map<String, ProvInstancePrice> previousOd) {
		return previousOd.values().stream()
				.filter(c -> c.getCode().indexOf('.') != -1 && TERM_ON_DEMAND.equals(c.getTerm().getName())).findFirst()
				.map(ProvInstancePrice::getCode).map(c -> c.substring(c.indexOf('.'))).orElse(null);
	}

	/**
	 * Download and install Savings Plan prices from AWS endpoint.
	 */
	private void installSavingsPlan(final LocalEc2Context context, final String endpoint,
			final Map<String, ProvInstancePrice> previousOd) {
		final var region = context.getRegion();
		// Install SavingPlan prices on this region
		if (endpoint == null) {
			// No end-point found for SP/region
			log.info("No Savings Plan prices on region {}", region.getName());
			return;
		}
		final var odCode2 = getOnDemandCode(previousOd);
		if (odCode2 == null) {
			// No OD found for SP/region
			log.warn("No OnDemand prices on region {}, Savings Plan is ignored", region.getName());
			return;
		}

		final var oldCount = context.getLocals().size();
		try (var curl = new CurlProcessor()) {
			// Get the remote prices stream
			final var rawJson = curl.get(endpoint);
			final var sps = objectMapper.readValue(rawJson, SavingsPlanPrice.class).getTerms().getSavingsPlan();
			final var skuErrors = sps.stream().flatMap(sp -> {
				final var term = newSavingsPlanTerm(context, sp);
				return sp.getRates().stream()
						.map(r -> installSavingsPlanTermPrices(context, term, r, previousOd, odCode2));
			}).filter(Objects::nonNull).collect(Collectors.toList());
			if (!skuErrors.isEmpty()) {
				// At least one SKU as not been resolved
				log.warn("AWS EC2 Savings Plan import errors for region {} with {} unresolved SKUs, first : {}",
						region.getName(), skuErrors.size(), skuErrors.get(0));
			}

			// Purge the SKUs
			purgePrices(context);
		} catch (final IOException | IllegalArgumentException use) {
			// Something goes wrong for this region, stop for this region
			log.warn("AWS EC2 Savings Plan import failed for region {}", region.getName(), use);
		} finally {
			// Report
			log.info("AWS EC2 Savings Plan import finished for region {}: {} prices ({})", region.getName(),
					context.getPrices().size(), String.format("%+d", context.getPrices().size() - oldCount));
			context.cleanup();
		}
	}

	/**
	 * Create or update the savings plan term and return it.
	 */
	private ProvInstancePriceTerm newSavingsPlanTerm(final LocalEc2Context context, final SavingsPlanTerm sp) {
		final var term = getLocalTerm(context, sp.getSku());

		// Update the properties only once
		return copyAsNeeded(context, term, t -> {
			var name = sp.getDescription();
			final boolean computePlan;
			if (sp.getDescription().contains(TERM_COMPUTE_SP)) {
				// Sample: "3 year No Upfront Compute Savings Plan"
				// Sample: "1 year All Upfront Compute Savings Plan"
				name = RegExUtils.replaceAll(name, "(\\d+) year\\s+(.*)\\s+Compute Savings Plan",
						TERM_COMPUTE_SP + ", $1yr, $2");
				computePlan = true;
			} else {
				// Sample: "3 year Partial Upfront r5 EC2 Instance Savings Plan in eu-west-3"
				name = RegExUtils.replaceAll(name, "(\\d+) year (.*)\\s+(.+)\\s+EC2 Instance Savings Plan (.*)",
						TERM_EC2_SP + ", $1yr, $2, $3 $4");
				computePlan = false;

				// This term is only available for a specific region
				term.setLocation(context.getRegion());
			}

			term.setName(name);
			term.setReservation(false);
			term.setConvertibleLocation(computePlan);
			term.setConvertibleFamily(computePlan);
			term.setConvertibleType(true);
			term.setConvertibleOs(true);
			term.setConvertibleEngine(false);
			term.setDescription(sp.getDescription());
			term.setPeriod(Math.round(sp.getLeaseContractLength().getDuration() * 12d));
			term.setInitialCost(name.matches(".*(All|Partial) Upfront.*"));
		});
	}

	/**
	 * Install the prices related to a term. The instance price type is reused from the discounted OnDemand price, and
	 * must exists.
	 */
	private String installSavingsPlanTermPrices(final LocalEc2Context context, final ProvInstancePriceTerm term,
			final SavingsPlanRate jsonPrice, final Map<String, ProvInstancePrice> previousOd, final String odCode2) {
		if (jsonPrice.getDiscountedUsageType().contains("Unused")) {
			// Ignore this usage
			return null;
		}

		// Get the related OD Price
		final var odPrice = previousOd.get(jsonPrice.getDiscountedSku() + odCode2);
		if (odPrice == null) {
			return jsonPrice.getDiscountedSku();
		}

		// Add this code to the existing SKU codes
		final var price = newSavingPlanPrice(context, odPrice, jsonPrice, term);
		final var cost = jsonPrice.getDiscountedRate().getPrice() * context.getHoursMonth();

		// Save the price as needed with up-front computation
		context.getPrices().add(price.getCode());
		saveAsNeeded(context, price, price.getCost(), cost, (cR, c) -> {
			price.setCost(cR);
			price.setCostPeriod(round3Decimals(c * Math.max(1, term.getPeriod())));

			if (!term.getInitialCost().booleanValue()) {
				// No up-front
				price.setInitialCost(0d);
			} else if (term.getName().contains("Partial")) {
				// Partial up-front
				price.setInitialCost(round3Decimals(price.getCostPeriod() * 0.5d));
			} else {
				// All up-front
				price.setInitialCost(price.getCostPeriod());
			}
		}, ipRepository::save);

		// No error
		return null;
	}

	/**
	 * Install or update a EC2 price
	 */
	private ProvInstancePrice newSavingPlanPrice(final LocalEc2Context context, final ProvInstancePrice odPrice,
			final SavingsPlanRate jsonPrice, final ProvInstancePriceTerm term) {
		final var type = odPrice.getType();
		final var price = context.getLocals().computeIfAbsent(jsonPrice.getRateCode(), context::newPrice);
		return copyAsNeeded(context, price, p -> {
			p.setLocation(context.getRegion());
			p.setLicense(odPrice.getLicense());
			p.setType(type);
			p.setTerm(term);
			p.setSoftware(odPrice.getSoftware());
			p.setOs(odPrice.getOs());
			p.setTenancy(odPrice.getTenancy());
			p.setPeriod(term.getPeriod());
		});
	}

	/**
	 * Create a new transactional (READ_UNCOMMITTED) process for OnDemand/SPE prices in a specific region.
	 * 
	 * @param gContext  The current global context.
	 * @param gRegion   The EC2 region.
	 * @param apiPrice  The EC2 API price
	 * @param spIndexes The Savings Plan indexes.
	 */
	@Transactional(propagation = Propagation.SUPPORTS, isolation = Isolation.READ_UNCOMMITTED)
	protected void installEC2Prices(final UpdateContext gContext, final ProvLocation gRegion, final String apiPrice,
			final Map<String, String> spIndexes) {
		final var endpoint = apiPrice.replace("%s", gRegion.getName());
		log.info("AWS EC2 OnDemand/Reserved import started for region {}@{} ...", gRegion.getName(), endpoint);
		// Track the created instance to cache partial costs
		final var region = locationRepository.findOne(gRegion.getId());
		final var context = new LocalEc2Context(gContext, iptRepository, itRepository, ipRepository, qiRepository,
				region, TERM_ON_DEMAND, TERM_RESERVED);

		final var oldCount = context.getLocals().size();

		// Get the remote prices stream
		var succeed = false;
		try (var reader = new BufferedReader(new InputStreamReader(new URI(endpoint).toURL().openStream()))) {
			// Pipe to the CSV reader
			final var csvReader = new CsvForBeanEc2(reader);

			// Build the AWS instance prices from the CSV
			var csv = csvReader.read();
			var first = true;
			while (csv != null) {
				if (first) {
					// Complete the region human name associated to the API one
					region.setDescription(csv.getLocation());
					first = false;
				}

				// Persist this price
				installEc2(context, csv);

				// Read the next one
				csv = csvReader.read();
			}

			// Purge the SKUs
			purgePrices(context);
			succeed = true;
		} catch (final IOException | URISyntaxException use) {
			// Something goes wrong for this region, stop for this region
			log.warn("AWS EC2 OnDemand/Reserved import failed for region {}", region.getName(), use);
		} finally {
			// Report
			log.info("AWS EC2 OnDemand/Reserved import finished for region {}: {} prices ({})", region.getName(),
					context.getPrices().size(), String.format("%+d", context.getPrices().size() - oldCount));
		}

		// Savings Plan part: only when OD succeed
		if (succeed) {
			final var spEndpoint = spIndexes.get(region.getName());
			log.info("AWS Savings Plan import started for region {}@{} ...", region, endpoint);
			final var spContext = new LocalEc2Context(gContext, iptRepository, itRepository, ipRepository, qiRepository,
					region, TERM_EC2_SP, TERM_COMPUTE_SP);
			spContext.setLocalTypes(context.getLocalTypes());
			spContext.setRegion(context.getRegion());
			installSavingsPlan(spContext, spEndpoint, context.getLocals());
			spContext.cleanup();
		}
	}

	/**
	 * Install the install the instance type (if needed), the instance price type (if needed) and the price.
	 *
	 * @param context The update context.
	 * @param csv     The current CSV entry.
	 */
	private void installEc2(final LocalEc2Context context, final AwsEc2Price csv) {
		// Filter OS and type
		if (!isEnabledType(context, csv.getInstanceType()) || !isEnabledOs(context, csv.getOs())) {
			return;
		}

		// Up-front, partial or not
		if (!handleUpFront(context, csv)) {
			// No up-front, cost is fixed
			final var price = newPrice(context, csv);
			final var cost = csv.getPricePerUnit() * context.getHoursMonth();
			saveAsNeeded(context, price, cost, ipRepository);
		}
	}

	@Override
	protected void copy(final AwsEc2Price csv, final ProvInstancePrice p) {
		final var software = ObjectUtils.defaultIfNull(csv.getSoftware(), "");
		p.setSoftware(StringUtils.trimToNull(mapSoftware.computeIfAbsent(software, String::toUpperCase)));
		p.setOs(toVmOs(csv.getOs()));
		p.setTenancy(ProvTenancy.valueOf(StringUtils.upperCase(csv.getTenancy())));
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
}
