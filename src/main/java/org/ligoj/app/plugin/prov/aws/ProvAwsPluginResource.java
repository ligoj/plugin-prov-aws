package org.ligoj.app.plugin.prov.aws;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.api.SubscriptionStatusWithData;
import org.ligoj.app.dao.NodeRepository;
import org.ligoj.app.dao.SubscriptionRepository;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Parameter;
import org.ligoj.app.plugin.prov.AbstractProvResource;
import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.QuoteVo;
import org.ligoj.app.plugin.prov.Terraforming;
import org.ligoj.app.plugin.prov.aws.auth.AWS4SignatureQuery;
import org.ligoj.app.plugin.prov.aws.auth.AWS4SignatureQuery.AWS4SignatureQueryBuilder;
import org.ligoj.app.plugin.prov.aws.auth.AWS4SignerForAuthorizationHeader;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceTypeRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstanceRepository;
import org.ligoj.app.plugin.prov.model.ProvInstance;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceType;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.app.plugin.prov.model.ProvTenancy;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.app.resource.plugin.CurlProcessor;
import org.ligoj.app.resource.plugin.CurlRequest;
import org.ligoj.bootstrap.core.NamedBean;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * The provisioning service for AWS. There is complete quote configuration along
 * the subscription.
 */
@Slf4j
@Service
@Path(ProvAwsPluginResource.URL)
@Produces(MediaType.APPLICATION_JSON)
public class ProvAwsPluginResource extends AbstractProvResource implements Terraforming {

	/**
	 * Plug-in key.
	 */
	public static final String URL = ProvResource.SERVICE_URL + "/aws";

	/**
	 * Plug-in key.
	 */
	public static final String KEY = URL.replace('/', ':').substring(1);

	/**
	 * The default region, fixed for now.
	 */
	private static final String DEFAULT_REGION = "eu-west-1";

	/**
	 * The default region for spot instances, fixed for now.
	 */
	private static final String DEFAULT_REGION_SPOT = "eu-ireland";

	/**
	 * The EC2 reserved and on-demand price end-point, a CSV file, accepting the
	 * region code with {@link Formatter}
	 */
	private static final String EC2_PRICES = "https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/%s/index.csv";

	/**
	 * The EC2 spot price end-point, a JSON file. The region code will be used
	 * to filter the JSON prices.
	 */
	private static final String EC2_PRICES_SPOT = "https://spot-price.s3.amazonaws.com/spot.js";
	private static final Pattern LEASING_TIME = Pattern.compile("(\\d)yr");

	/**
	 * Configuration key used for {@link #EC2_PRICES}
	 */
	public static final String CONF_URL_PRICES = KEY + ":ec2-prices-url";

	/**
	 * Configuration key used for {@link #EC2_PRICES_SPOT}
	 */
	public static final String CONF_URL_PRICES_SPOT = KEY + ":ec2-prices-spot-url";

	/**
	 * Configuration key used for {@link #DEFAULT_REGION}
	 */
	public static final String CONF_REGION = KEY + ":region";

	/**
	 * Configuration key used for {@link #DEFAULT_REGION_SPOT}
	 */
	public static final String CONF_REGION_SPOT = KEY + ":region-spot";

	/**
	 * Parameter used for AWS authentication
	 */
	public static final String PARAMETER_ACCESS_KEY_ID = KEY + ":access-key-id";

	/**
	 * Parameter used for AWS authentication
	 */
	public static final String PARAMETER_SECRET_ACCESS_KEY = KEY + ":secret-access-key";

	/**
	 * AWS Account Id.
	 */
	public static final String PARAMETER_ACCOUNT = KEY + ":account";

	/**
	 * Jackson type reference for Spot price
	 *
	 */
	private static final TypeReference<Collection<Collection<AwsInstanceSpotPrice>>> TYPE_PRICE_REF = new TypeReference<Collection<Collection<AwsInstanceSpotPrice>>>() {
		// Nothing to override
	};

	@Autowired
	private AWS4SignerForAuthorizationHeader signer;

	@Autowired
	private ConfigurationResource configuration;

	@Autowired
	private NodeRepository nodeRepository;

	@Autowired
	private ProvInstancePriceTypeRepository iptRepository;

	@Autowired
	private ProvInstanceRepository instanceRepository;

	@Autowired
	private ProvInstancePriceRepository ipRepository;

	@Autowired
	private SubscriptionRepository sRepository;

	@Autowired
	private ProvAwsTerraformService terraformService;

	@Override
	public String getKey() {
		return KEY;
	}

	/**
	 * Check AWS connection and account.
	 * 
	 * @param subscription
	 *            subscription
	 * @return <code>true</code> if AWS connection is up
	 */
	@Override
	public boolean checkStatus(final String node, final Map<String, String> parameters) throws Exception {
		return validateAccess(parameters);
	}

	@Override
	public List<Class<?>> getInstalledEntities() {
		return Arrays.asList(Node.class, ProvStorageType.class, Parameter.class);
	}

	@Override
	public void create(final int subscription) throws Exception {
		if (!validateAccess(subscription)) {
			throw new BusinessException("Cannot access to AWS services with these parameters");
		}
	}

	@Override
	public SubscriptionStatusWithData checkSubscriptionStatus(final int subscription, final String node,
			final Map<String, String> parameters) throws Exception {
		// Validate the account
		if (validateAccess(subscription)) {
			// Return the quote details
			return super.checkSubscriptionStatus(subscription, node, parameters);
		}
		return new SubscriptionStatusWithData(false);
	}

	/**
	 * Fetch the prices from the AWS server
	 */
	@Override
	public void install() throws IOException, URISyntaxException {

		// Node is already persisted
		final Node node = nodeRepository.findOneExpected(KEY);
		installEC2SpotPrices(installEC2Prices(node), node);
	}

	/**
	 * Install EC2 spot prices from local CSV files.
	 * 
	 * @param instances
	 *            The previously installed instances. Key is the instance name.
	 * @param node
	 *            The related AWS {@link Node}
	 */
	private void installEC2SpotPrices(final Map<String, ProvInstance> instances, final Node node) throws IOException {
		log.info("AWS EC2 Spot import started...");

		// Create the Spot instance price type
		final ProvInstancePriceType spotPriceType = newSpotInstanceType(node);

		// Track the created instance to cache instance and price type
		final String region = configuration.get(CONF_REGION_SPOT, DEFAULT_REGION_SPOT);
		final String endpoint = configuration.get(CONF_URL_PRICES_SPOT, EC2_PRICES_SPOT).replace("%s", region);
		int priceCounter = 0;

		try {
			// Get the remote prices stream
			String rawJson = StringUtils.defaultString(new CurlProcessor().get(endpoint), "callback({\"config\":{\"regions\":[]}});");

			// Remove the useless data to save massive memory footprint
			final int regionIndex = rawJson.indexOf(region);
			final Stream<AwsInstanceSpotPrice> spotPrices;
			if (regionIndex < 0) {
				// Region has not been found, spot will not be available
				spotPrices = Stream.empty();
				log.warn("No spot available for region {} from endpoint {}", region, endpoint);
			} else {
				final int instancesTypesIndex = rawJson.indexOf("[", regionIndex);
				final Matcher closeMatcher = Pattern
						.compile("\\]\\s*\\}\\s*(,\\s*\\{\\s*\"region\"|\\]\\s*\\}\\s*\\}\\);)", Pattern.MULTILINE).matcher(rawJson);
				Assert.isTrue(closeMatcher.find(regionIndex), "Closing postion of region '" + region + "' not found");

				// Build the smallest JSON containing only the specified region
				rawJson = rawJson.substring(instancesTypesIndex, closeMatcher.start() + 1);

				// Build the stream of prices
				final ObjectMapper mapper = new ObjectMapper();
				final Collection<Collection<AwsInstanceSpotPrice>> prices = mapper
						.convertValue(mapper.readTree(rawJson).findValues("sizes"), TYPE_PRICE_REF);
				spotPrices = prices.stream().flatMap(Collection::stream);
			}

			// Install the spot instances and collect the amount
			priceCounter = spotPrices.filter(j -> {
				final boolean availability = instances.containsKey(j.getName());
				if (!availability) {
					// Unavailable instances type of spot are ignored
					log.warn("Instance {} is refrenced from spot but not available", j.getName());
				}
				return availability;
			}).mapToInt(j -> install(j, instances, spotPriceType)).sum();
		} finally {
			// Report
			log.info("AWS EC2 Spot import finished : {} prices", priceCounter);
		}
	}

	/**
	 * Create a new {@link ProvInstancePriceType} for Spot.
	 */
	private ProvInstancePriceType newSpotInstanceType(final Node node) {
		final ProvInstancePriceType spotPriceType = new ProvInstancePriceType();
		spotPriceType.setName("Spot");
		spotPriceType.setNode(node);
		spotPriceType.setPeriod(60); // 1h
		spotPriceType.setVariable(true);
		spotPriceType.setEphemeral(true);
		iptRepository.saveAndFlush(spotPriceType);
		return spotPriceType;
	}

	/**
	 * Install the install the instance type (if needed), the instance price
	 * type (if needed) and the price.
	 * 
	 * @param json
	 *            The current JSON entry.
	 * @param instances
	 *            The previously installed instances. Key is the instance name.
	 * @param spotPriceType
	 *            The related AWS Spot instance price type.
	 * @return The amount of installed prices. Only for the report.
	 */
	private int install(final AwsInstanceSpotPrice json, final Map<String, ProvInstance> instances,
			final ProvInstancePriceType spotPriceType) {
		return (int) json.getOsPrices().stream().filter(op -> !StringUtils.startsWithIgnoreCase(op.getPrices().get("USD"), "N/A"))
				.map(op -> {
					final ProvInstancePrice price = new ProvInstancePrice();
					price.setInstance(instances.get(json.getName()));
					price.setType(spotPriceType);
					price.setOs(op.getName().equals("mswin") ? VmOs.WINDOWS : VmOs.LINUX);
					price.setCost(Double.valueOf(op.getPrices().get("USD")));
					ipRepository.save(price);
					return price;
				}).count();
	}

	/**
	 * Download and install EC2 prices from AWS server.
	 * 
	 * @param node
	 *            The related AWS {@link Node}
	 * @return The installed instances. Key is the instance name.
	 */
	protected Map<String, ProvInstance> installEC2Prices(final Node node) throws IOException, URISyntaxException {
		log.info("AWS EC2 OnDemand/Reserved import started...");

		// Track the created instance to cache instance and price type
		final Map<String, ProvInstancePriceType> priceTypes = new HashMap<>();
		final Map<String, ProvInstance> instances = new HashMap<>();
		final Map<String, ProvInstancePrice> partialCost = new HashMap<>();
		final String region = getRegion();
		final String endpoint = configuration.get(CONF_URL_PRICES, EC2_PRICES).replace("%s", region);
		int priceCounter = 0;

		BufferedReader reader = null;
		try {
			// Get the remote prices stream
			reader = new BufferedReader(new InputStreamReader(new URI(endpoint).toURL().openStream()));
			// Pipe to the CSV reader
			final AwsCsvForBean csvReader = new AwsCsvForBean(reader);

			// Build the AWS instance prices from the CSV
			AwsInstancePrice csv = null;
			do {
				// Read the next one
				csv = csvReader.read();
				if (csv == null) {
					break;
				}

				// Persist this price
				priceCounter += install(csv, instances, priceTypes, partialCost, node);
			} while (true);
		} finally {
			// Report
			log.info("AWS EC2 OnDemand/Reserved import finished : {} instance, {} price types, {} prices", instances.size(),
					priceTypes.size(), priceCounter);
			IOUtils.closeQuietly(reader);
		}

		// Return the available instances types
		return instances;
	}

	/**
	 * Install the install the instance type (if needed), the instance price
	 * type (if needed) and the price.
	 * 
	 * @param csv
	 *            The current CSV entry.
	 * @param instances
	 *            The previously installed instances. Key is the instance name.
	 * @param priceTypes
	 *            The previously installed price types.
	 * @param partialCost
	 *            The current partial cost for up-front options.
	 * @param node
	 *            The related {@link Node}
	 * @return The amount of installed prices. Only for the report.
	 */
	private int install(final AwsInstancePrice csv, final Map<String, ProvInstance> instances,
			final Map<String, ProvInstancePriceType> priceTypes, final Map<String, ProvInstancePrice> partialCost, final Node node) {
		// Upfront, partial or not
		int priceCounter = 0;
		if (StringUtils.equalsAnyIgnoreCase(csv.getPurchaseOption(), "All Upfront", "Partial Upfront")) {
			final String partialCostKey = csv.getSku() + csv.getOfferTermCode();
			if (partialCost.containsKey(partialCostKey)) {
				final ProvInstancePrice ipUpfront = partialCost.get(partialCostKey);
				handleUpfront(csv, ipUpfront);

				// The price is completed, cleanup and persist
				partialCost.remove(partialCostKey);
				priceCounter++;
				ipRepository.save(ipUpfront);
			} else if (csv.getMemory() != null) {
				// First time, save this instance for a future completion
				handleUpfront(csv,
						partialCost.computeIfAbsent(partialCostKey, k -> newProvInstancePrice(csv, instances, priceTypes, node)));
			}
		} else if (csv.getMemory() != null) {
			// No leasing, cost is fixed
			priceCounter++;
			final ProvInstancePrice price = newProvInstancePrice(csv, instances, priceTypes, node);
			price.setCost(csv.getPricePerUnit());
			ipRepository.save(price);
		}
		return priceCounter;
	}

	private void handleUpfront(final AwsInstancePrice csv, final ProvInstancePrice ipUpfront) {
		final double hourlyCost;
		if (csv.getPriceUnit().equals("Quantity")) {
			// Upfront price part , update the effective hourly cost
			ipUpfront.setInitialCost(Double.valueOf(csv.getPricePerUnit()));
			hourlyCost = ipUpfront.getCost() + ipUpfront.getInitialCost() * 60 / ipUpfront.getType().getPeriod();
		} else {
			// Remaining hourly cost of the leasing
			hourlyCost = csv.getPricePerUnit() + ipUpfront.getCost();
		}

		// Round the computed hourly cost
		ipUpfront.setCost(round5Decimals(hourlyCost));
	}

	private ProvInstancePrice newProvInstancePrice(final AwsInstancePrice csv, final Map<String, ProvInstance> instances,
			final Map<String, ProvInstancePriceType> priceTypes, final Node node) {

		final ProvInstancePrice price = new ProvInstancePrice();

		// Initial price is 0, and is updated depending on the leasing
		price.setCost(0d);

		// Associate the instance
		price.setInstance(instances.computeIfAbsent(csv.getInstanceType(), k -> newProvInstance(csv, node)));

		// Associate the instance price type
		price.setType(priceTypes.computeIfAbsent(csv.getOfferTermCode(), k -> newProvInstancePriceType(csv, node)));

		// Fill the price variable
		price.setOs(VmOs.valueOf(csv.getOs().toUpperCase(Locale.ENGLISH)));
		price.setTenancy(ProvTenancy.valueOf(StringUtils.upperCase(csv.getTenancy())));
		price.setLicense(StringUtils.trimToNull(
				StringUtils.remove(csv.getLicenseModel().replace("License Included", StringUtils.defaultString(csv.getSoftware(), ""))
						.replace("NA", "License Included"), "No License required")));
		return price;
	}

	private ProvInstance newProvInstance(final AwsInstancePrice csv, final Node node) {
		final ProvInstance instance = new ProvInstance();
		instance.setNode(node);
		instance.setCpu(csv.getCpu());
		instance.setName(csv.getInstanceType());

		// Convert GiB to MiB, and rounded
		instance.setRam(
				(int) Math.round(Double.parseDouble(StringUtils.removeEndIgnoreCase(csv.getMemory(), " GiB").replace(",", "")) * 1024d));
		instance.setConstant(!"Variable".equals(csv.getEcu()));
		instance.setDescription(ArrayUtils.toString(new String[] { csv.getPhysicalProcessor(), csv.getClockSpeed() }));
		instanceRepository.saveAndFlush(instance);
		return instance;
	}

	/**
	 * Round up to 5 decimals the given value.
	 */
	private double round5Decimals(final double value) {
		return Math.round(value * 100000d) / 100000d;
	}

	/**
	 * Build a new instance price type from the CSV line.
	 */
	private ProvInstancePriceType newProvInstancePriceType(final AwsInstancePrice csvPrice, final Node node) {
		final ProvInstancePriceType result = new ProvInstancePriceType();
		result.setNode(node);

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

	@Override
	public void terraform(final OutputStream output, final int subscription, final QuoteVo quote) throws IOException {
		final Writer writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
		terraformService.writeTerraform(writer, quote, sRepository.findOne(subscription));
		writer.flush();
	}

	@Override
	public String[] commandLineParameters(final int subscription) {
		final Map<String, String> parameters = subscriptionResource.getParameters(subscription);
		return new String[] { "-var", "'AWS_ACCESS_KEY_ID=" + parameters.get(PARAMETER_ACCESS_KEY_ID) + "'", "-var",
				"'AWS_SECRET_ACCESS_KEY=" + parameters.get(PARAMETER_SECRET_ACCESS_KEY) + "'" };
	}

	/**
	 * Return EC2 keys
	 * 
	 * @param subscription
	 *            The related subscription.
	 * @return EC2 keys related to given subscription.
	 */
	@Path("ec2/keys/{subscription:\\d+}")
	@GET
	public List<NamedBean<String>> getEC2Keys(@PathParam("subscription") final int subscription) {
		// Call "DescribeKeyPairs" service
		final String query = "Action=DescribeKeyPairs&Version=2016-11-15";
		final AWS4SignatureQueryBuilder signatureQueryBuilder = AWS4SignatureQuery.builder().service("ec2")
				.host("ec2." + getRegion() + ".amazonaws.com").path("/").body(query);
		final CurlRequest request = newRequest(signatureQueryBuilder, subscription);
		// extract key pairs from response
		final List<NamedBean<String>> keys = new ArrayList<>();
		if (new CurlProcessor().process(request)) {
			final Matcher keyNames = Pattern.compile("<keyName>(.*)</keyName>").matcher(request.getResponse());
			while (keyNames.find()) {
				keys.add(new NamedBean<>(keyNames.group(1), null));
			}
		}
		return keys;
	}

	/**
	 * Create Curl request for AWS service. Initialize default values for
	 * awsAccessKey, awsSecretKey and regionName and compute signature.
	 * 
	 * @param signatureBuilder
	 *            {@link AWS4SignatureQueryBuilder} initialized with values used
	 *            for this call (headers, parameters, host, ...)
	 * @param subscription
	 *            Subscription's identifier.
	 * @return initialized request
	 */
	protected CurlRequest newRequest(final AWS4SignatureQueryBuilder signatureBuilder, final int subscription) {
		return newRequest(signatureBuilder, subscriptionResource.getParameters(subscription));
	}

	/**
	 * Create Curl request for AWS service. Initialize default values for
	 * awsAccessKey, awsSecretKey and regionName and compute signature.
	 * 
	 * @param signatureBuilder
	 *            {@link AWS4SignatureQueryBuilder} initialized with values used
	 *            for this call (headers, parameters, host, ...)
	 * @param subscription
	 *            Subscription's identifier.
	 * @return initialized request
	 */
	protected CurlRequest newRequest(final AWS4SignatureQueryBuilder signatureBuilder, final Map<String, String> parameters) {
		final AWS4SignatureQuery signatureQuery = signatureBuilder.accessKey(parameters.get(PARAMETER_ACCESS_KEY_ID))
				.secretKey(parameters.get(PARAMETER_SECRET_ACCESS_KEY)).region(getRegion()).build();
		final String authorization = signer.computeSignature(signatureQuery);
		final CurlRequest request = new CurlRequest(signatureQuery.getMethod(),
				"https://" + signatureQuery.getHost() + signatureQuery.getPath(), signatureQuery.getBody());
		request.getHeaders().putAll(signatureQuery.getHeaders());
		request.getHeaders().put("Authorization", authorization);
		request.setSaveResponse(true);
		return request;
	}

	/**
	 * Check AWS connection and account.
	 * 
	 * @param parameters
	 *            Subscription parameters.
	 * @return <code>true</code> if AWS connection is up
	 */
	private boolean validateAccess(final Map<String, String> parameters) throws Exception {
		// Call S3 ls service
		// TODO Use EC2 instead of S3
		final AWS4SignatureQueryBuilder signatureQueryBuilder = AWS4SignatureQuery.builder().method("GET").service("s3")
				.host("s3-" + getRegion() + ".amazonaws.com").path("/");
		return new CurlProcessor().process(newRequest(signatureQueryBuilder, parameters));
	}

	/**
	 * Return the default region for this plug-in.
	 */
	protected String getRegion() {
		return configuration.get(CONF_REGION, DEFAULT_REGION);
	}

	/**
	 * Check AWS connection and account.
	 * 
	 * @param subscription
	 *            Subscription identifier.
	 * @return <code>true</code> if AWS connection is up
	 */
	public boolean validateAccess(final int subscription) throws Exception {
		// Call S3 ls service
		// TODO Use EC2 instead of S3
		final AWS4SignatureQueryBuilder signatureQueryBuilder = AWS4SignatureQuery.builder().method("GET").service("s3")
				.host("s3-" + getRegion() + ".amazonaws.com").path("/");
		return new CurlProcessor().process(newRequest(signatureQueryBuilder, subscription));
	}
}
