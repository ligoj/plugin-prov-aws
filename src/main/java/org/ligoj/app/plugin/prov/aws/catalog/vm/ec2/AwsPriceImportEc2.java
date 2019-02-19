/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.ec2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Formatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.model.Node;
import org.ligoj.app.plugin.prov.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.aws.catalog.vm.AbstractAwsPriceImportVm;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvInstanceType;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvTenancy;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * The provisioning price service for AWS. Manage install or update of prices.
 */
@Slf4j
@Component
public class AwsPriceImportEc2 extends AbstractAwsPriceImportVm {

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
	private Map<String, String> mapSoftware = new HashMap<>();

	@Override
	public void install(final UpdateContext context) throws IOException, URISyntaxException {
		context.setValidOs(Pattern.compile(configuration.get(CONF_OS, ".*")));
		context.setValidInstanceType(Pattern.compile(configuration.get(CONF_ITYPE, ".*")));
		final ProvInstancePriceTerm spotPriceType = newSpotInstanceType(context.getNode());
		context.setPriceTerms(iptRepository.findAllBy(BY_NODE, context.getNode()).stream()
				.collect(Collectors.toMap(ProvInstancePriceTerm::getCode, Function.identity())));
		installEc2(context, context.getNode(), spotPriceType);
	}

	/**
	 * Create as needed a new {@link ProvInstancePriceTerm} for Spot.
	 */
	private ProvInstancePriceTerm newSpotInstanceType(final Node node) {
		return Optional.ofNullable(iptRepository.findByName(node.getId(), "Spot")).orElseGet(() -> {
			final ProvInstancePriceTerm spotPriceType = new ProvInstancePriceTerm();
			spotPriceType.setName("Spot");
			spotPriceType.setNode(node);
			spotPriceType.setVariable(true);
			spotPriceType.setEphemeral(true);
			spotPriceType.setCode("spot");
			iptRepository.saveAndFlush(spotPriceType);
			return spotPriceType;
		});
	}

	/**
	 * EC2 spot installer. Install the instance type (if needed), the instance price type (if needed) and the price.
	 *
	 * @param context
	 *            The update context.
	 * @param json
	 *            The current JSON entry.
	 * @param spotPriceType
	 *            The related AWS Spot instance price type.
	 * @param region
	 *            The target region.
	 * @return The amount of installed prices. Only for the report.
	 */
	private int installSpotPrices(final UpdateContext context, final AwsEc2SpotPrice json,
			final ProvInstancePriceTerm spotPriceType, final ProvLocation region) {
		return (int) json.getOsPrices().stream()
				.filter(op -> !StringUtils.startsWithIgnoreCase(op.getPrices().get("USD"), "N/A"))
				.peek(op -> op.setOs(op.getName().equals("mswin") ? VmOs.WINDOWS : VmOs.LINUX))
				.filter(op -> isEnabledOs(context, op.getOs())).map(op -> {
					final ProvInstanceType type = context.getInstanceTypes().get(json.getName());

					// Build the key for this spot
					final String code = "spot-" + region.getName() + "-" + type.getName() + "-" + op.getOs();
					final ProvInstancePrice price = context.getPrevious().computeIfAbsent(code, c -> {
						final ProvInstancePrice p = new ProvInstancePrice();
						p.setCode(c);
						p.setType(type);
						p.setTerm(spotPriceType);
						p.setTenancy(ProvTenancy.SHARED);
						p.setOs(op.getOs());
						p.setLocation(region);
						return p;
					});

					// Update the price as needed
					final double cost = Double.parseDouble(op.getPrices().get("USD"));
					return saveAsNeeded(price, round3Decimals(cost * HOUR_TO_MONTH), p -> {
						p.setCostPeriod(cost);
						ipRepository.save(p);
					});
				}).count();
	}

	private void installEc2(final UpdateContext context, final Node node, final ProvInstancePriceTerm spotPriceType)
			throws IOException {
		// The previously installed instance types cache. Key is the instance name
		context.setInstanceTypes(itRepository.findAllBy(BY_NODE, node).stream()
				.collect(Collectors.toMap(ProvInstanceType::getName, Function.identity())));

		// Install the EC2 (non spot) prices
		importCatalogResource.nextStep(context.getNode().getId(), t -> t.setPhase("ec2"));
		context.getRegions().values().forEach(region -> {
			nextStep(node, region.getName(), 1);
			// Get previous prices for this location
			context.setPrevious(ipRepository.findAll(node.getId(), region.getName()).stream()
					.collect(Collectors.toMap(ProvInstancePrice::getCode, Function.identity())));
			installEC2Prices(context, region);
		});
		context.getPrevious().clear();

		// Install the SPOT EC2 prices
		installJsonPrices(context, "ec2-spot", configuration.get(CONF_URL_EC2_PRICES_SPOT, EC2_PRICES_SPOT),
				SpotPrices.class, (r, region) -> {
					nextStep(node, region.getName(), 1);
					// Get previous prices for this location
					context.setPrevious(ipRepository.findAll(node.getId(), region.getName()).stream()
							.collect(Collectors.toMap(ProvInstancePrice::getCode, Function.identity())));
					return r.getInstanceTypes().stream().flatMap(t -> t.getSizes().stream()).filter(t -> {
						final boolean availability = context.getInstanceTypes().containsKey(t.getName());
						if (!availability) {
							// Unavailable instances type of spot are ignored
							log.warn("Instance {} is referenced from spot but not available", t.getName());
						}
						return availability;
					}).mapToInt(j -> installSpotPrices(context, j, spotPriceType, region)).sum();
				});
		context.getInstanceTypes().clear();
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
	private int installEc2(final UpdateContext context, final AwsEc2Price csv, final ProvLocation region) {
		// Filter OS and type
		if (!isEnabledType(context, csv.getInstanceType()) || !isEnabledOs(context, csv.getOs())) {
			return 0;
		}

		// Up-front, partial or not
		int priceCounter = 0;
		if (UPFRONT_MODE.matcher(StringUtils.defaultString(csv.getPurchaseOption())).find()) {
			// Up-front ALL/PARTIAL
			final Map<String, AwsEc2Price> partialCost = context.getPartialCost();
			final String code = csv.getSku() + csv.getOfferTermCode() + region.getName();
			if (partialCost.containsKey(code)) {
				handleUpfront(newEc2Price(context, csv, region), csv, partialCost.get(code), ipRepository);

				// The price is completed, cleanup
				partialCost.remove(code);
				priceCounter++;
			} else {
				// First time, save this entry for a future completion
				partialCost.put(code, csv);
			}
		} else {
			// No up-front, cost is fixed
			priceCounter++;
			final ProvInstancePrice price = newEc2Price(context, csv, region);
			final double cost = csv.getPricePerUnit() * HOUR_TO_MONTH;
			saveAsNeeded(price, round3Decimals(cost), p -> {
				p.setCostPeriod(round3Decimals(cost * p.getTerm().getPeriod()));
				ipRepository.save(p);
			});
		}
		return priceCounter;
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
	private int installEC2Prices(final UpdateContext context, final ProvLocation region) {
		// Track the created instance to cache partial costs
		context.setPartialCost(new HashMap<>());
		final String endpoint = configuration.get(CONF_URL_EC2_PRICES, EC2_PRICES).replace("%s", region.getName());
		log.info("AWS EC2 OnDemand/Reserved import started for region {}@{} ...", region, endpoint);
		int priceCounter = 0;

		// Get the remote prices stream
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(new URI(endpoint).toURL().openStream()))) {
			// Pipe to the CSV reader
			final CsvForBeanEc2 csvReader = new CsvForBeanEc2(reader);

			// Build the AWS instance prices from the CSV
			AwsEc2Price csv = csvReader.read();
			while (csv != null) {
				// Complete the region human name associated to the API one
				region.setDescription(csv.getLocation());

				// Persist this price
				priceCounter += installEc2(context, csv, region);

				// Read the next one
				csv = csvReader.read();
			}
		} catch (final IOException | URISyntaxException use) {
			// Something goes wrong for this region, stop for this region
			log.info("AWS EC2 OnDemand/Reserved import failed for region {}", region.getName(), use);
		} finally {
			// Report
			log.info("AWS EC2 OnDemand/Reserved import finished for region {} : {} instance, {} price types, {} prices",
					region.getName(), context.getInstanceTypes().size(), context.getPriceTerms().size(), priceCounter);
		}

		// Return the available instances types
		return priceCounter;
	}

	/**
	 * Install or update a EC2 price
	 */
	private ProvInstancePrice newEc2Price(final UpdateContext context, final AwsEc2Price csv,
			final ProvLocation region) {
		final ProvInstanceType type = installInstanceType(context, csv, context.getInstanceTypes(),
				ProvInstanceType::new, itRepository);
		return context.getPrevious().computeIfAbsent(toCode(csv), c -> {
			final ProvInstancePrice p = new ProvInstancePrice();
			copy(context, csv, region, c, p, type);
			final String software = ObjectUtils.defaultIfNull(csv.getSoftware(), "");
			p.setSoftware(StringUtils.trimToNull(mapSoftware.computeIfAbsent(software, String::toUpperCase)));
			p.setOs(toVmOs(csv.getOs()));
			p.setTenancy(ProvTenancy.valueOf(StringUtils.upperCase(csv.getTenancy())));
			return p;
		});
	}

	private VmOs toVmOs(String osName) {
		return VmOs.valueOf(osName.toUpperCase(Locale.ENGLISH));
	}

	/**
	 * Indicate the given OS is enabled.
	 *
	 * @param context
	 *            The update context.
	 * @param os
	 *            The OS to test.
	 * @return <code>true</code> when the configuration enable the given OS.
	 */
	private boolean isEnabledOs(final UpdateContext context, final VmOs os) {
		return isEnabledOs(context, os.name());
	}

	/**
	 * Indicate the given OS is enabled.
	 *
	 * @param context
	 *            The update context.
	 * @param os
	 *            The OS to test.
	 * @return <code>true</code> when the configuration enable the given OS.
	 */
	private boolean isEnabledOs(final UpdateContext context, final String os) {
		return context.getValidOs().matcher(os.toUpperCase(Locale.ENGLISH)).matches();
	}

	/**
	 * Read the EC2 software name from AWS to standard name.
	 *
	 * @throws IOException
	 *             When the JSON mapping file cannot be read.
	 */
	@PostConstruct
	public void initSoftwareNormalize() throws IOException {
		mapSoftware.putAll(toMap("aws-software.json", MAP_STR));
	}
}
