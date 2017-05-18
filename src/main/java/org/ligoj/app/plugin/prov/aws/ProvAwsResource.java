package org.ligoj.app.plugin.prov.aws;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.dao.NodeRepository;
import org.ligoj.app.model.Node;
import org.ligoj.app.plugin.prov.AbstractProvResource;
import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceTypeRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstanceRepository;
import org.ligoj.app.plugin.prov.model.ProvInstance;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceType;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.app.plugin.prov.model.ProvTenancy;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.bootstrap.core.dao.csv.CsvForJpa;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.mockito.internal.util.io.IOUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * The provisioning service for AWS. There is complete quote configuration along
 * the subscription.
 */
@Slf4j
@Service
@Path(ProvAwsResource.SERVICE_URL)
@Produces(MediaType.APPLICATION_JSON)
public class ProvAwsResource extends AbstractProvResource {

	/**
	 * Plug-in key.
	 */
	public static final String SERVICE_URL = ProvResource.SERVICE_URL + "/aws";

	/**
	 * Plug-in key.
	 */
	public static final String SERVICE_KEY = SERVICE_URL.replace('/', ':').substring(1);

	/**
	 * The default region, fixed for now.
	 */
	private static final String DEFAULT_REGION = "eu-west-1";

	/**
	 * The EC2 reserved and on-demand price end-point, a CSV file, accepting the
	 * region code with {@link Formatter}
	 */
	private static final String EC2_PRICES = "https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/%s/index.csv";

	private static final Pattern LEASING_TIME = Pattern.compile("(\\d)yr");

	/**
	 * Configuration key used for {@link #EC2_PRICES}
	 */
	public static final String CONF_URL_PRICES = SERVICE_KEY + ":reserved-ec2-prices-url";

	/**
	 * Configuration key used for {@link #DEFAULT_REGION}
	 */
	public static final String CONF_REGION = SERVICE_KEY + ":region";

	@Autowired
	private ConfigurationResource configuration;

	@Autowired
	private CsvForJpa csvForJpa;

	@Autowired
	private NodeRepository nodeRepository;

	@Autowired
	private ProvInstancePriceTypeRepository iptRepository;

	@Autowired
	private ProvInstanceRepository instanceRepository;

	@Autowired
	private ProvInstancePriceRepository ipRepository;

	@Override
	public String getKey() {
		return SERVICE_KEY;
	}

	@Override
	public List<Class<?>> getInstalledEntities() {
		return Arrays.asList(Node.class, ProvStorageType.class);
	}

	/**
	 * Fetch the prices from the AWS server
	 */
	@Override
	public void install() throws IOException, URISyntaxException {
		installEC2Prices();
		installEC2SpotPrices();
	}

	/**
	 * Install EC2 spot prices from local CSV files.
	 */
	private void installEC2SpotPrices() throws IOException {
		csvForJpa.insert("csv", ProvInstancePriceType.class, ProvInstancePrice.class);
	}

	/**
	 * Download and install EC2 prices from AWS server
	 */
	protected void installEC2Prices() throws IOException, URISyntaxException {
		log.info("AWS EC2 OnDemand/Reserved import started...");

		// Track the created instance to cache instance and price type
		final Map<String, ProvInstancePriceType> priceTypes = new HashMap<>();
		final Map<String, ProvInstance> instances = new HashMap<>();
		final Map<String, ProvInstancePrice> partialCost = new HashMap<>();
		final String region = configuration.get(CONF_REGION, DEFAULT_REGION);
		final String priceEndpoint = configuration.get(CONF_URL_PRICES, EC2_PRICES).replace("%s", region);
		int prices = 0;

		BufferedReader reader = null;
		try {
			// Get the remote prices stream
			reader = new BufferedReader(new InputStreamReader(new URI(priceEndpoint).toURL().openStream()));
			// Pipe to the CSV reader
			final AwsCsvForBean csvReader = new AwsCsvForBean(reader);

			// Node is already persisted
			final Node awsNode = nodeRepository.findOneExpected(SERVICE_KEY);

			// Build the AWS instance prices
			AwsInstancePrice instancePrice = null;
			do {
				// Read the next one
				instancePrice = csvReader.read();
				if (instancePrice == null) {
					break;
				}

				// Persist this price
				prices += install(instancePrice, instances, priceTypes, partialCost, awsNode);
			} while (true);

		} finally {
			// Report
			log.info("AWS EC2 OnDemand/Reserved import finished : {} instance, {} price types, {} prices",
					instances.size(), priceTypes.size(), prices);
			IOUtil.closeQuietly(reader);
		}
	}

	private int install(final AwsInstancePrice csvPrice, final Map<String, ProvInstance> instances,
			final Map<String, ProvInstancePriceType> priceTypes, final Map<String, ProvInstancePrice> partialCost,
			final Node awsNode) {
		// Upfront, partial or not
		int prices = 0;
		if (StringUtils.equalsAnyIgnoreCase(csvPrice.getPurchaseOption(), "All Upfront", "Partial Upfront")) {
			final String partialCostKey = csvPrice.getSku() + csvPrice.getOfferTermCode();
			if (partialCost.containsKey(partialCostKey)) {
				final ProvInstancePrice ipUpfront = partialCost.get(partialCostKey);
				handleUpfront(csvPrice, ipUpfront);

				// The is completed, cleanup and persist
				partialCost.remove(partialCostKey);
				prices++;
				ipRepository.saveAndFlush(ipUpfront);
			} else {
				// First time, save this instance for a future completion
				handleUpfront(csvPrice, partialCost.computeIfAbsent(partialCostKey,
						k -> newProvInstancePrice(csvPrice, instances, priceTypes, awsNode)));
			}
		} else {
			// No leasing, cost is fixed
			prices++;
			final ProvInstancePrice instancePrice = newProvInstancePrice(csvPrice, instances, priceTypes, awsNode);
			instancePrice.setCost(csvPrice.getPricePerUnit());
			ipRepository.saveAndFlush(instancePrice);
		}
		return prices;
	}

	private void handleUpfront(final AwsInstancePrice csvPrice, final ProvInstancePrice ipUpfront) {
		final double hourlyCost;
		if (csvPrice.getPriceUnit().equals("Quantity")) {
			// Upfront price part , update the effective hourly cost
			ipUpfront.setInitialCost(Double.valueOf(csvPrice.getPricePerUnit()));
			hourlyCost = ipUpfront.getCost() + ipUpfront.getInitialCost() * 60 / ipUpfront.getType().getPeriod();
		} else {
			// Remaining hourly cost of the leasing
			hourlyCost = csvPrice.getPricePerUnit() + ipUpfront.getCost();
		}

		// Round the computed hourly cost
		ipUpfront.setCost(round3Decimals(hourlyCost));
	}

	private ProvInstancePrice newProvInstancePrice(final AwsInstancePrice csvPrice,
			final Map<String, ProvInstance> instances, final Map<String, ProvInstancePriceType> priceTypes,
			final Node awsNode) {

		final ProvInstancePrice instancePrice = new ProvInstancePrice();

		// Initial price is 0, and is updated depending on the leasing
		instancePrice.setCost(0d);

		// Associate the instance
		instancePrice.setInstance(
				instances.computeIfAbsent(csvPrice.getInstanceType(), k -> newProvInstance(csvPrice, awsNode)));

		// Associate the instance price type
		instancePrice.setType(priceTypes.computeIfAbsent(csvPrice.getOfferTermCode(),
				k -> newProvInstancePriceType(csvPrice, awsNode)));

		// Fill the price variable
		instancePrice.setOs(VmOs.valueOf(csvPrice.getOs().toUpperCase(Locale.ENGLISH).replace("RHEL", "RHE")));
		instancePrice.setTenancy(ProvTenancy.valueOf(StringUtils.upperCase(csvPrice.getTenancy())));
		instancePrice.setLicense(StringUtils.trimToNull(
				StringUtils.remove(csvPrice.getLicenseModel().replace("License Included", csvPrice.getSoftware())
						.replace("NA", "License Included"), "No License required")));
		return instancePrice;
	}

	private ProvInstance newProvInstance(final AwsInstancePrice csvPrice, final Node awsNode) {
		final ProvInstance instance = new ProvInstance();
		instance.setNode(awsNode);
		instance.setCpu(csvPrice.getCpu());
		instance.setName(csvPrice.getInstanceType());

		// Convert GiB to MiB, and rounded
		instance.setRam((int) Math.round(
				Double.parseDouble(StringUtils.removeEndIgnoreCase(csvPrice.getMemory(), " GiB").replace(",", ""))
						* 1024d));
		instance.setConstant(!"Variable".equals(csvPrice.getEcu()));
		instance.setDescription(
				ArrayUtils.toString(new String[] { csvPrice.getPhysicalProcessor(), csvPrice.getClockSpeed() }));
		instanceRepository.saveAndFlush(instance);
		return instance;
	}

	/**
	 * Round up to 3 decimals the given value.
	 */
	private double round3Decimals(final double value) {
		return Math.round(value * 1000d) / 1000d;
	}

	/**
	 * Build a new instance price type from the CSV line.
	 */
	private ProvInstancePriceType newProvInstancePriceType(final AwsInstancePrice csvPrice, final Node awsNode) {
		final ProvInstancePriceType result = new ProvInstancePriceType();
		result.setNode(awsNode);

		// Build the name from the leasing, purchase option and offering class
		result.setName(Arrays
				.stream(new String[] { csvPrice.getTermType(), csvPrice.getLeaseContractLength(),
						StringUtils.trimToNull(StringUtils.remove(csvPrice.getPurchaseOption(), "No Upfront")),
						StringUtils.trimToNull(StringUtils.remove(csvPrice.getOfferingClass(), "standard")) })
				.filter(Objects::nonNull).collect(Collectors.joining(", ")));

		// By default, hourly period
		result.setPeriod(60);

		// Handle leasing
		final Matcher matcher = LEASING_TIME.matcher(StringUtils.defaultIfBlank(csvPrice.getLeaseContractLength(), ""));
		if (matcher.find()) {
			// Convert years to minutes
			result.setPeriod(Integer.parseInt(matcher.group(1)) * 60 * 24 * 365);
		}
		iptRepository.saveAndFlush(result);
		return result;
	}
}
