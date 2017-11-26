package org.ligoj.app.plugin.prov.aws.in;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Formatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.ligoj.app.dao.NodeRepository;
import org.ligoj.app.model.Node;
import org.ligoj.app.plugin.prov.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceTermRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstanceTypeRepository;
import org.ligoj.app.plugin.prov.dao.ProvLocationRepository;
import org.ligoj.app.plugin.prov.dao.ProvStoragePriceRepository;
import org.ligoj.app.plugin.prov.dao.ProvStorageTypeRepository;
import org.ligoj.app.plugin.prov.in.ImportCatalogResource;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvInstanceType;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.app.plugin.prov.model.ProvTenancy;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.app.resource.plugin.CurlProcessor;
import org.ligoj.bootstrap.core.INamableBean;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * The provisioning price service for AWS. Manage install or update of prices.
 */
@Slf4j
@Service
public class ProvAwsPriceImportResource {

	/**
	 * File containing the mapping from the spot region name to the new format one.
	 * This mapping is required because of the old naming format used for the first
	 * region's name. All non mapped regions use the new name in spot JSON file.
	 */
	private static final String SPOT_TO_NEW_REGION_FILE = "spot-to-new-region.json";

	/**
	 * File containing the mapping from the EBS/S3 JSON code name to API name. All
	 * non mapped regions use the JSON name.
	 */
	private static final String STORAGE_TO_API = "storage-to-api.json";

	/**
	 * The EC2 reserved and on-demand price end-point, a CSV file, accepting the
	 * region code with {@link Formatter}
	 */
	private static final String EC2_PRICES = "https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/%s/index.csv";

	/**
	 * The EC2 spot price end-point, a JSON file. Contains the prices for all
	 * regions.
	 */
	private static final String EC2_PRICES_SPOT = "https://spot-price.s3.amazonaws.com/spot.js";
	private static final Pattern LEASING_TIME = Pattern.compile("(\\d)yr");

	/**
	 * The EBS prices end-point. Contains the prices for all regions.
	 */
	private static final String EBS_PRICES = "https://a0.awsstatic.com/pricing/1/ebs/pricing-ebs.js";

	/**
	 * The S3 prices end-point. Contains the prices for all regions.
	 */
	private static final String S3_PRICES = "https://a0.awsstatic.com/pricing/1/s3/pricing-storage-s3.js";

	/**
	 * Configuration key used for {@link #EBS_PRICES}
	 */
	public static final String CONF_URL_API_PRICES = ProvAwsPluginResource.KEY + ":%s-prices-url";

	/**
	 * Configuration key used for {@link #EC2_PRICES}
	 */
	public static final String CONF_URL_EC2_PRICES = String.format(CONF_URL_API_PRICES, "ec2");

	/**
	 * Configuration key used for {@link #EC2_PRICES_SPOT}
	 */
	public static final String CONF_URL_EC2_PRICES_SPOT = String.format(CONF_URL_API_PRICES, "ec2-spot");

	/**
	 * Configuration key used for {@link #EBS_PRICES}
	 */
	public static final String CONF_URL_EBS_PRICES = String.format(CONF_URL_API_PRICES, "ebs");

	/**
	 * Configuration key used for {@link #S3_PRICES}
	 */
	public static final String CONF_URL_S3_PRICES = String.format(CONF_URL_API_PRICES, "s3");

	@Autowired
	private ConfigurationResource configuration;

	@Autowired
	private NodeRepository nodeRepository;

	@Autowired
	private ProvLocationRepository locationRepository;

	@Autowired
	private ProvInstancePriceTermRepository iptRepository;

	@Autowired
	private ProvInstanceTypeRepository instanceRepository;

	@Autowired
	private ProvInstancePriceRepository ipRepository;

	@Autowired
	private ProvStoragePriceRepository spRepository;

	@Autowired
	private ProvStorageTypeRepository stRepository;

	@Autowired
	protected ImportCatalogResource importCatalogResource;

	@Autowired
	private ObjectMapper objectMapper;

	/**
	 * Mapping from Spot region name to API name.
	 */
	private Map<String, String> mapSpotToNewRegion = new HashMap<>();

	/**
	 * Mapping from storage human name to API name.
	 */
	private Map<String, String> mapStorageToApi = new HashMap<>();

	/**
	 * Install or update prices.
	 */
	public void install() throws IOException, URISyntaxException {
		// Node is already persisted, install EC2 prices
		final Node node = nodeRepository.findOneExpected(ProvAwsPluginResource.KEY);

		// The previously installed location cache. Key is the location AWS name
		final Map<String, ProvLocation> regions = locationRepository.findAllBy("node.id", node.getId()).stream()
				.collect(Collectors.toMap(INamableBean::getName, Function.identity()));

		// Update the workload : nb.regions + 3 (spot, ebs, s3)
		nextStep(node, null, 0);

		// Proceed to the install
		installStoragePrices(node, regions);
		installComputePrices(node, regions);
	}

	/**
	 * Install storage prices from the JSON file provided by AWS.
	 * 
	 * @param node
	 *            The related AWS {@link Node}
	 * @param regions
	 *            The available regions.
	 */
	private void installStoragePrices(final Node node, final Map<String, ProvLocation> regions) throws IOException {
		// The previously installed storage types cache. Key is the storage name
		final Map<String, ProvStorageType> storages = stRepository.findAllBy("node.id", node.getId()).stream()
				.collect(Collectors.toMap(INamableBean::getName, Function.identity()));

		// Install EBS prices
		installPrices(node, regions, "ebs", configuration.get(CONF_URL_EBS_PRICES, EBS_PRICES), EbsPrices.class, (r, region) -> {
			// Get previous prices for this location
			final Map<Integer, ProvStoragePrice> previous = spRepository.findAll(node.getId(), region.getName()).stream()
					.collect(Collectors.toMap(p -> p.getType().getId(), Function.identity()));
			return (int) r.getTypes().stream().filter(t -> containsKey(storages, t))
					.filter(t -> t.getValues().stream().filter(j -> !"perPIOPSreq".equals(j.getRate()))
							.filter(j -> install(j, storages.get(t.getName()), region, previous)).findAny().isPresent())
					.count();
		});

		// Install S3 prices, note only the first 50TB storage tiers is considered
		installPrices(node, regions, "s3", configuration.get(CONF_URL_S3_PRICES, S3_PRICES), S3Prices.class, (r, region) -> {
			// Get previous prices for this location
			final Map<Integer, ProvStoragePrice> previous = spRepository.findAll(node.getId(), region.getName()).stream()
					.collect(Collectors.toMap(p -> p.getType().getId(), Function.identity()));
			return (int) r.getTiers().stream().limit(1).flatMap(tiers -> tiers.getStorageTypes().stream())
					.filter(t -> containsKey(storages, t)).filter(t -> install(t, storages.get(t.getName()), region, previous)).count();
		});
	}

	/**
	 * Convert the JSON name to the API name and check this storage is exists
	 * 
	 * @param storages
	 *            The accepted and existing storage type.
	 * @param storage
	 *            The storage to evaluate.
	 * @return <code>true</code> when the storage is valid.
	 */
	private <T extends INamableBean<?>> boolean containsKey(final Map<String, ProvStorageType> storages, final T storage) {
		storage.setName(mapStorageToApi.getOrDefault(storage.getName(), storage.getName()));
		return storages.containsKey(storage.getName());
	}

	/**
	 * Install compute prices from the JSON file provided by AWS.
	 * 
	 * @param node
	 *            The related AWS {@link Node}
	 * @param regions
	 *            The available regions.
	 */
	private void installComputePrices(final Node node, final Map<String, ProvLocation> regions) throws IOException {
		// Create the Spot instance price type
		final ProvInstancePriceTerm spotPriceType = newSpotInstanceType(node);
		final Map<String, ProvInstancePriceTerm> priceTypes = iptRepository.findAllBy("node.id", node.getId()).stream()
				.collect(Collectors.toMap(ProvInstancePriceTerm::getCode, Function.identity()));

		// The previously installed instance types cache. Key is the instance name
		final Map<String, ProvInstanceType> instances = instanceRepository.findAllBy("node.id", node.getId()).stream()
				.collect(Collectors.toMap(ProvInstanceType::getName, Function.identity()));

		installPrices(node, regions, "ec2", configuration.get(CONF_URL_EC2_PRICES_SPOT, EC2_PRICES_SPOT), SpotPrices.class, (r, region) -> {
			// Get previous prices for this location
			final Map<String, ProvInstancePrice> previous = ipRepository.findAll(node.getId(), region.getName()).stream()
					.collect(Collectors.toMap(ProvInstancePrice::getCode, Function.identity()));

			// Install the EC2 prices and related instance details used later
			final int ec2Prices = installEC2Prices(instances, priceTypes, node, region, previous);
			nextStep(node, region.getName(), 1);

			// Install the SPOT EC2 prices
			return ec2Prices + r.getInstanceTypes().stream().flatMap(t -> t.getSizes().stream()).filter(j -> {
				final boolean availability = instances.containsKey(j.getName());
				if (!availability) {
					// Unavailable instances type of spot are ignored
					log.warn("Instance {} is referenced from spot but not available", j.getName());
				}
				return availability;
			}).mapToInt(j -> install(j, instances, spotPriceType, region, previous)).sum();

		});
	}

	/**
	 * Install AWS prices from the JSON file.
	 * 
	 * @param node
	 *            The related AWS {@link Node}
	 * @param regions
	 *            The available regions.
	 * @param api
	 *            The API name, only for log.
	 * @param endpoint
	 *            The prices end-point JSON URL.
	 * @param apiClass
	 *            The mapping model from JSON at region level.
	 * @param mapper
	 *            The mapping function from JSON at region level to JPA entity.
	 */
	private <R extends AwsRegionPrices, T extends AwsPrices<R>> void installPrices(final Node node, final Map<String, ProvLocation> regions,
			final String api, final String endpoint, final Class<T> apiClass, final BiFunction<R, ProvLocation, Integer> mapper)
			throws IOException {
		log.info("AWS {} prices...", api);

		// Track the created instance to cache instance and price type
		int priceCounter = 0;
		importCatalogResource.nextStep(node.getId(), t -> t.setPhase(api));

		try {
			// Get the remote prices stream
			String rawJson = StringUtils.defaultString(new CurlProcessor().get(endpoint), "callback({\"config\":{\"regions\":[]}});");

			// All regions are considered
			final int configIndex = rawJson.indexOf('{');
			final int configCloseIndex = rawJson.lastIndexOf('}');
			final T prices = objectMapper.readValue(rawJson.substring(configIndex, configCloseIndex + 1), apiClass);

			// Install the region as needed
			prices.getConfig().getRegions().forEach(r -> r
					.setRegion(installRegion(regions, mapSpotToNewRegion.getOrDefault(r.getRegion(), r.getRegion()), node).getName()));
			nextStep(node, null, 0);

			// Install the (EC2 + Spot) prices for each region
			priceCounter = prices.getConfig().getRegions().stream().mapToInt(r -> mapper.apply(r, regions.get(r.getRegion()))).sum();
		} finally {
			// Report
			log.info("AWS {} import finished : {} prices", api, priceCounter);
			nextStep(node, null, 1);
		}
	}

	/**
	 * Update the statistics
	 */
	private void nextStep(final Node node, final String location, final int step) {
		importCatalogResource.nextStep(node.getId(), t -> {
			importCatalogResource.updateStats(t);
			t.setWorkload(t.getNbLocations() + 3); // Nb region + 3 (spot+s3+ebs)
			t.setDone(t.getDone() + step);
			t.setLocation(location);
		});
	}

	/**
	 * Install a new region.
	 */
	private ProvLocation installRegion(final Map<String, ProvLocation> regions, final String region, final Node node) {
		return regions.computeIfAbsent(region, r -> {
			final ProvLocation location = new ProvLocation();
			location.setNode(node);
			location.setName(r);
			locationRepository.saveAndFlush(location);
			return location;
		});
	}

	/**
	 * Create as needed a new {@link ProvInstancePriceTerm} for Spot.
	 */
	private ProvInstancePriceTerm newSpotInstanceType(final Node node) {
		return Optional.ofNullable(iptRepository.findByName(node.getId(), "Spot")).orElseGet(() -> {
			final ProvInstancePriceTerm spotPriceType = new ProvInstancePriceTerm();
			spotPriceType.setName("Spot");
			spotPriceType.setNode(node);
			spotPriceType.setPeriod(60); // 1h
			spotPriceType.setVariable(true);
			spotPriceType.setEphemeral(true);
			spotPriceType.setCode("spot");
			iptRepository.saveAndFlush(spotPriceType);
			return spotPriceType;
		});
	}

	/**
	 * EC2 spot installer. Install the instance type (if needed), the instance price
	 * type (if needed) and the price.
	 * 
	 * @param json
	 *            The current JSON entry.
	 * @param instances
	 *            The previously installed instance types. Key is the instance name.
	 * @param spotPriceType
	 *            The related AWS Spot instance price type.
	 * @param region
	 *            The target region.
	 * @param previous
	 *            The previous installed prices.
	 * @return The amount of installed prices. Only for the report.
	 */
	private int install(final AwsInstanceSpotPrice json, final Map<String, ProvInstanceType> instances,
			final ProvInstancePriceTerm spotPriceType, final ProvLocation region, final Map<String, ProvInstancePrice> previous) {
		return (int) json.getOsPrices().stream().filter(op -> !StringUtils.startsWithIgnoreCase(op.getPrices().get("USD"), "N/A"))
				.map(op -> {
					final VmOs os = op.getName().equals("mswin") ? VmOs.WINDOWS : VmOs.LINUX;
					final ProvInstanceType instance = instances.get(json.getName());

					// Build the key for this spot
					final String code = "spot-" + region.getName() + "-" + instance.getName() + "-" + os.name();
					final ProvInstancePrice price = Optional.ofNullable(previous.get(code)).orElseGet(() -> {
						final ProvInstancePrice p = new ProvInstancePrice();
						p.setCode(code);
						p.setType(instance);
						p.setTerm(spotPriceType);
						p.setTenancy(ProvTenancy.SHARED);
						p.setOs(os);
						p.setLocation(region);
						return p;
					});

					// Update the price
					price.setCost(Double.valueOf(op.getPrices().get("USD")));
					return ipRepository.save(price);
				}).count();
	}

	/**
	 * Install the EBS/S3 price using the related storage type.
	 * 
	 * @param json
	 *            The current JSON entry.
	 * @param storage
	 *            The related storage specification.
	 * @param region
	 *            The target region.
	 * @return The amount of installed prices. Only for the report.
	 */
	private <T extends AwsPrice> boolean install(final T json, final ProvStorageType storage, final ProvLocation region,
			final Map<Integer, ProvStoragePrice> previous) {
		return Optional.ofNullable(json.getPrices().get("USD")).filter(NumberUtils::isParsable).map(usd -> {
			final ProvStoragePrice price = previous.computeIfAbsent(storage.getId(), s -> {
				final ProvStoragePrice p = new ProvStoragePrice();
				p.setType(storage);
				p.setLocation(region);
				return p;
			});

			// Update the price
			price.setCostGb(Double.valueOf(usd));
			spRepository.save(price);
			return price;
		}).isPresent();
	}

	/**
	 * Download and install EC2 prices from AWS server.
	 * 
	 * @param instances
	 *            The previously instance types already installed. Key is the
	 *            instance name.
	 * @param priceTypes
	 *            The previously price types already installed. Key is the offer
	 *            English name.
	 * @param node
	 *            The related AWS {@link Node}
	 * @param region
	 *            The region to fetch.
	 * @return The amount installed EC2 instances.
	 */
	protected int installEC2Prices(final Map<String, ProvInstanceType> instances, final Map<String, ProvInstancePriceTerm> priceTypes,
			final Node node, final ProvLocation region, final Map<String, ProvInstancePrice> previous) {
		log.info("AWS EC2 OnDemand/Reserved import started for region {} ...", region);

		// Track the created instance to cache partial costs
		final Map<String, ProvInstancePrice> partialCost = new HashMap<>();
		final String endpoint = configuration.get(CONF_URL_EC2_PRICES, EC2_PRICES).replace("%s", region.getName());
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
				priceCounter += install(csv, instances, priceTypes, partialCost, node, region, previous);
			} while (true);
		} catch (final IOException | URISyntaxException use) {
			// Something goes wrong for this region, stop for this region
			log.info("AWS EC2 OnDemand/Reserved import failed for region {}", region.getName(), use);
		} finally {
			// Report
			log.info("AWS EC2 OnDemand/Reserved import finished for region {} : {} instance, {} price types, {} prices", region.getName(),
					instances.size(), priceTypes.size(), priceCounter);
			IOUtils.closeQuietly(reader);
		}

		// Return the available instances types
		return priceCounter;
	}

	/**
	 * Install the install the instance type (if needed), the instance price type
	 * (if needed) and the price.
	 * 
	 * @param csv
	 *            The current CSV entry.
	 * @param instances
	 *            The previously installed instance types. Key is the instance name.
	 * @param priceTypes
	 *            The previously installed price types.
	 * @param partialCost
	 *            The current partial cost for up-front options.
	 * @param node
	 *            The related {@link Node}
	 * @param region
	 *            The current region.
	 * @param previous
	 *            The previous installed prices.
	 * @return The amount of installed prices. Only for the report.
	 */
	private int install(final AwsInstancePrice csv, final Map<String, ProvInstanceType> instances,
			final Map<String, ProvInstancePriceTerm> priceTypes, final Map<String, ProvInstancePrice> partialCost, final Node node,
			final ProvLocation region, final Map<String, ProvInstancePrice> previous) {
		// Upfront, partial or not
		int priceCounter = 0;
		if (StringUtils.equalsAnyIgnoreCase(csv.getPurchaseOption(), "All Upfront", "Partial Upfront")) {
			final String code = csv.getSku() + csv.getOfferTermCode();
			if (partialCost.containsKey(code)) {
				final ProvInstancePrice ipUpfront = partialCost.get(code);
				handleUpfront(csv, ipUpfront);

				// The price is completed, cleanup and persist
				partialCost.remove(code);
				priceCounter++;
				ipRepository.save(ipUpfront);
			} else {
				// First time, save this instance for a future completion
				handleUpfront(csv,
						partialCost.computeIfAbsent(code, k -> newProvInstancePrice(csv, instances, priceTypes, node, region, previous)));
			}
		} else {
			// No leasing, cost is fixed
			priceCounter++;
			final ProvInstancePrice price = newProvInstancePrice(csv, instances, priceTypes, node, region, previous);
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
			hourlyCost = ipUpfront.getCost() + ipUpfront.getInitialCost() * 60 / ipUpfront.getTerm().getPeriod();
		} else {
			// Remaining hourly cost of the leasing
			hourlyCost = csv.getPricePerUnit() + ipUpfront.getCost();
		}

		// Round the computed hourly cost
		ipUpfront.setCost(round5Decimals(hourlyCost));
	}

	/**
	 * Install or update a EC2 price
	 */
	private ProvInstancePrice newProvInstancePrice(final AwsInstancePrice csv, final Map<String, ProvInstanceType> instances,
			final Map<String, ProvInstancePriceTerm> priceTypes, final Node node, final ProvLocation region,
			final Map<String, ProvInstancePrice> previous) {
		final VmOs os = VmOs.valueOf(csv.getOs().toUpperCase(Locale.ENGLISH));
		final ProvInstanceType instance = instances.computeIfAbsent(csv.getInstanceType(), k -> newProvInstance(csv, node));
		final String license = StringUtils.trimToNull(
				StringUtils.remove(csv.getLicenseModel().replace("License Included", StringUtils.defaultString(csv.getSoftware(), ""))
						.replace("NA", "License Included"), "No License required"));
		final ProvTenancy tenancy = ProvTenancy.valueOf(StringUtils.upperCase(csv.getTenancy()));
		final ProvInstancePriceTerm term = priceTypes.computeIfAbsent(csv.getOfferTermCode(), k -> newProvInstancePriceTerm(csv, node));
		final String code = toCode(csv);
		final ProvInstancePrice price = previous.computeIfAbsent(code, c -> {
			final ProvInstancePrice p = new ProvInstancePrice();
			p.setLocation(region);
			p.setCode(code);
			p.setOs(os);
			p.setTenancy(tenancy);
			p.setLicense(license);
			p.setType(instance);
			p.setTerm(term);
			return p;
		});

		// Initial price is 0, and is updated depending on the leasing
		price.setCost(0d);
		return price;
	}

	private String toCode(final AwsInstancePrice csv) {
		return csv.getSku() + csv.getOfferTermCode();
	}

	/**
	 * Install a new EC2 instance type
	 */
	private ProvInstanceType newProvInstance(final AwsInstancePrice csv, final Node node) {
		final ProvInstanceType instance = new ProvInstanceType();
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
	private ProvInstancePriceTerm newProvInstancePriceTerm(final AwsInstancePrice csvPrice, final Node node) {
		final ProvInstancePriceTerm result = new ProvInstancePriceTerm();
		result.setNode(node);
		result.setCode(csvPrice.getOfferTermCode());

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

	/**
	 * Read the spot region to the new format from an external JSON file.
	 */
	@PostConstruct
	public void initSpotToNewRegion() throws IOException {
		mapSpotToNewRegion.putAll(objectMapper.readValue(
				IOUtils.toString(new ClassPathResource(SPOT_TO_NEW_REGION_FILE).getInputStream(), StandardCharsets.UTF_8),
				new TypeReference<Map<String, String>>() {
					// Nothing to extend
				}));
	}

	/**
	 * Read the EBS/S3 mapping to API name from an external JSON file.
	 */
	@PostConstruct
	public void initEbsToApi() throws IOException {
		mapStorageToApi.putAll(
				objectMapper.readValue(IOUtils.toString(new ClassPathResource(STORAGE_TO_API).getInputStream(), StandardCharsets.UTF_8),
						new TypeReference<Map<String, String>>() {
							// Nothing to extend
						}));
	}
}
