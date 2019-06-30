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
		importCatalogResource.nextStep(context.getNode().getId(), t -> t.setPhase("ec2"));
		context.setValidOs(Pattern.compile(configuration.get(CONF_OS, ".*")));
		context.setValidInstanceType(Pattern.compile(configuration.get(CONF_ITYPE, ".*")));
		final ProvInstancePriceTerm spotPriceType = newSpotInstanceType(context.getNode());
		context.setPriceTerms(iptRepository.findAllBy(BY_NODE, context.getNode()).stream()
				.collect(Collectors.toConcurrentMap(ProvInstancePriceTerm::getCode, Function.identity())));
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
	 * @param context       The update context.
	 * @param json          The current JSON entry.
	 * @param spotPriceType The related AWS Spot instance price type.
	 * @param region        The target region.
	 */
	private void installSpotPrices(final UpdateContext context, final AwsEc2SpotPrice json,
			final ProvInstancePriceTerm spotPriceType, final ProvLocation region, final UpdateContext localContext) {
		json.getOsPrices().stream().filter(op -> !StringUtils.startsWithIgnoreCase(op.getPrices().get("USD"), "N/A"))
				.peek(op -> op.setOs(op.getName().equals("mswin") ? VmOs.WINDOWS : VmOs.LINUX))
				.filter(op -> isEnabledOs(context, op.getOs())).forEach(op -> {
					final var type = context.getInstanceTypes().get(json.getName());

					// Build the key for this spot
					final var code = "spot-" + region.getName() + "-" + type.getName() + "-" + op.getOs();
					final var price = localContext.getPrevious().computeIfAbsent(code, c -> {
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
					final var cost = Double.parseDouble(op.getPrices().get("USD"));
					saveAsNeeded(price, round3Decimals(cost * context.getHoursMonth()), p -> {
						p.setCostPeriod(cost);
						ipRepository.save(p);
					});
				});
	}

	private void installEc2(final UpdateContext context, final Node node, final ProvInstancePriceTerm spotPriceType)
			throws IOException {
		// The previously installed instance types cache. Key is the instance name
		context.setInstanceTypes(itRepository.findAllBy(BY_NODE, node).stream()
				.collect(Collectors.toConcurrentMap(ProvInstanceType::getName, Function.identity())));

		// Install the EC2 (non spot) prices
		context.getRegions().values().parallelStream().peek(region -> {
			// Get previous prices for this location
			final var localContext = new UpdateContext();
			localContext.setPrevious(ipRepository.findAll(node.getId(), region.getName()).stream()
					.collect(Collectors.toMap(ProvInstancePrice::getCode, Function.identity())));
			installEC2Prices(context, region, localContext);
		}).sequential().forEach(region -> nextStep(node, region.getName(), 1));

		// Install the SPOT EC2 prices
		installJsonPrices(context, "ec2-spot", configuration.get(CONF_URL_EC2_PRICES_SPOT, EC2_PRICES_SPOT),
				SpotPrices.class, (r, region) -> {
					nextStep(node, region.getName(), 1);
					// Get previous prices for this location
					final var localContext = new UpdateContext();
					localContext.setPrevious(ipRepository.findAll(node.getId(), region.getName()).stream()
							.collect(Collectors.toMap(ProvInstancePrice::getCode, Function.identity())));
					r.getInstanceTypes().stream().flatMap(t -> t.getSizes().stream()).filter(t -> {
						final var availability = context.getInstanceTypes().containsKey(t.getName());
						if (!availability) {
							// Unavailable instances type of spot are ignored
							log.warn("Instance {} is referenced from spot but not available", t.getName());
						}
						return availability;
					}).forEach(j -> installSpotPrices(context, j, spotPriceType, region, localContext));
				});
		context.getInstanceTypes().clear();
	}

	/**
	 * Download and install EC2 prices from AWS server.
	 *
	 * @param context The update context.
	 * @param region  The region to fetch.
	 */
	private void installEC2Prices(final UpdateContext context, final ProvLocation region,
			final UpdateContext localContext) {
		// Track the created instance to cache partial costs
		localContext.setPartialCost(new HashMap<>());
		final var endpoint = configuration.get(CONF_URL_EC2_PRICES, EC2_PRICES).replace("%s", region.getName());
		log.info("AWS EC2 OnDemand/Reserved import started for region {}@{} ...", region, endpoint);

		// Get the remote prices stream
		try (var reader = new BufferedReader(new InputStreamReader(new URI(endpoint).toURL().openStream()))) {
			// Pipe to the CSV reader
			final var csvReader = new CsvForBeanEc2(reader);

			// Build the AWS instance prices from the CSV
			AwsEc2Price csv = csvReader.read();
			while (csv != null) {
				// Complete the region human name associated to the API one
				region.setDescription(csv.getLocation());

				// Persist this price
				installEc2(context, csv, region, localContext);

				// Read the next one
				csv = csvReader.read();
			}
		} catch (final IOException | URISyntaxException use) {
			// Something goes wrong for this region, stop for this region
			log.info("AWS EC2 OnDemand/Reserved import failed for region {}", region.getName(), use);
		} finally {
			// Report
			log.info("AWS EC2 OnDemand/Reserved import finished for region {} : {} instance, {} price types",
					region.getName(), context.getInstanceTypes().size(), context.getPriceTerms().size());
		}
	}

	/**
	 * Install the install the instance type (if needed), the instance price type (if needed) and the price.
	 *
	 * @param context The update context.
	 * @param csv     The current CSV entry.
	 * @param region  The current region.
	 */
	private void installEc2(final UpdateContext context, final AwsEc2Price csv, final ProvLocation region,
			final UpdateContext localContext) {
		// Filter OS and type
		if (!isEnabledType(context, csv.getInstanceType()) || !isEnabledOs(context, csv.getOs())) {
			return;
		}

		// Up-front, partial or not
		if (UPFRONT_MODE.matcher(StringUtils.defaultString(csv.getPurchaseOption())).find()) {
			// Up-front ALL/PARTIAL
			final var partialCost = localContext.getPartialCost();
			final var code = toCode(csv);
			if (partialCost.containsKey(code)) {
				handleUpfront(context, newEc2Price(context, csv, region, localContext), csv, partialCost.get(code),
						ipRepository);

				// The price is completed, cleanup
				partialCost.remove(code);
			} else {
				// First time, save this entry for a future completion
				partialCost.put(code, csv);
			}
		} else {
			// No up-front, cost is fixed
			final var price = newEc2Price(context, csv, region, localContext);
			final var cost = csv.getPricePerUnit() * context.getHoursMonth();
			saveAsNeeded(price, round3Decimals(cost), p -> {
				p.setCostPeriod(round3Decimals(cost * p.getTerm().getPeriod()));
				ipRepository.save(p);
			});
		}
	}

	/**
	 * Install or update a EC2 price
	 */
	private ProvInstancePrice newEc2Price(final UpdateContext context, final AwsEc2Price csv, final ProvLocation region,
			final UpdateContext localContext) {
		final var type = installInstanceType(context, csv, context.getInstanceTypes(), ProvInstanceType::new,
				itRepository);
		return localContext.getPrevious().computeIfAbsent(toCode(csv), c -> {
			final var p = new ProvInstancePrice();
			copy(context, csv, region, c, p, type);
			final var software = ObjectUtils.defaultIfNull(csv.getSoftware(), "");
			p.setSoftware(StringUtils.trimToNull(mapSoftware.computeIfAbsent(software, String::toUpperCase)));
			p.setOs(toVmOs(csv.getOs()));
			p.setTenancy(ProvTenancy.valueOf(StringUtils.upperCase(csv.getTenancy())));
			return p;
		});
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
