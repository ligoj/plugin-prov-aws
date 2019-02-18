/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.ligoj.app.model.Node;
import org.ligoj.app.plugin.prov.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.prov.aws.catalog.ebs.EbsPrices;
import org.ligoj.app.plugin.prov.aws.catalog.ec2.AbstractAwsEc2Price;
import org.ligoj.app.plugin.prov.aws.catalog.ec2.AwsEc2Price;
import org.ligoj.app.plugin.prov.aws.catalog.ec2.AwsEc2SpotPrice;
import org.ligoj.app.plugin.prov.aws.catalog.ec2.CsvForBeanEc2;
import org.ligoj.app.plugin.prov.aws.catalog.ec2.SpotPrices;
import org.ligoj.app.plugin.prov.aws.catalog.efs.CsvForBeanEfs;
import org.ligoj.app.plugin.prov.aws.catalog.rds.AwsRdsPrice;
import org.ligoj.app.plugin.prov.aws.catalog.rds.CsvForBeanRds;
import org.ligoj.app.plugin.prov.aws.catalog.s3.AwsS3Price;
import org.ligoj.app.plugin.prov.aws.catalog.s3.CsvForBeanS3;
import org.ligoj.app.plugin.prov.catalog.AbstractImportCatalogResource;
import org.ligoj.app.plugin.prov.dao.BaseProvInstanceTypeRepository;
import org.ligoj.app.plugin.prov.dao.BaseProvTermPriceRepository;
import org.ligoj.app.plugin.prov.model.AbstractInstanceType;
import org.ligoj.app.plugin.prov.model.AbstractPrice;
import org.ligoj.app.plugin.prov.model.AbstractTermPrice;
import org.ligoj.app.plugin.prov.model.ProvDatabasePrice;
import org.ligoj.app.plugin.prov.model.ProvDatabaseType;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvInstanceType;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvStorageOptimized;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.app.plugin.prov.model.ProvSupportPrice;
import org.ligoj.app.plugin.prov.model.ProvSupportType;
import org.ligoj.app.plugin.prov.model.ProvTenancy;
import org.ligoj.app.plugin.prov.model.Rate;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.bootstrap.core.INamableBean;
import org.ligoj.bootstrap.core.curl.CurlProcessor;
import org.ligoj.bootstrap.core.dao.csv.CsvForJpa;
import org.ligoj.bootstrap.core.model.AbstractNamedEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;

import lombok.extern.slf4j.Slf4j;

/**
 * The provisioning price service for AWS. Manage install or update of prices.
 */
@Slf4j
@Service
public class ProvAwsPriceImportResource extends AbstractImportCatalogResource {

	private static final TypeReference<Map<String, String>> MAP_STR = new TypeReference<>() {
		// Nothing to extend
	};

	private static final TypeReference<Map<String, ProvLocation>> MAP_LOCATION = new TypeReference<>() {
		// Nothing to extend
	};

	private static final String BY_NODE = "node";

	/**
	 * The EC2 reserved and on-demand price end-point, a CSV file, accepting the region code with {@link Formatter}
	 */
	private static final String EC2_PRICES = "https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/%s/index.csv";

	/**
	 * The RDS reserved and on-demand price end-point, a CSV file, accepting the region code with {@link Formatter}
	 */
	private static final String RDS_PRICES = "https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonRDS/current/%s/index.csv";

	/**
	 * The EC2 spot price end-point, a JSON file. Contains the prices for all regions.
	 */
	private static final String EC2_PRICES_SPOT = "https://spot-price.s3.amazonaws.com/spot.js";
	private static final Pattern LEASING_TIME = Pattern.compile("(\\d)\\s*yr");
	private static final Pattern UPFRONT_MODE = Pattern.compile("(All|Partial)\\s*Upfront");

	/**
	 * The EBS prices end-point. Contains the prices for all regions.
	 */
	private static final String EBS_PRICES = "https://a0.awsstatic.com/pricing/1/ebs/pricing-ebs.js";

	/**
	 * The EFS price end-point, a CSV file. Multi-region.
	 */
	private static final String EFS_PRICES = "https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEFS/current/index.csv";

	/**
	 * The S3 price end-point, a CSV file. Multi-region.
	 */
	private static final String S3_PRICES = "https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonS3/current/index.csv";

	/**
	 * Configuration key used for AWS URL prices.
	 */
	public static final String CONF_URL_API_PRICES = ProvAwsPluginResource.KEY + ":%s-prices-url";

	/**
	 * Configuration key used for {@link #EC2_PRICES}
	 */
	public static final String CONF_URL_EC2_PRICES = String.format(CONF_URL_API_PRICES, "ec2");

	/**
	 * Configuration key used for {@link #RDS_PRICES}
	 */
	public static final String CONF_URL_RDS_PRICES = String.format(CONF_URL_API_PRICES, "rds");

	/**
	 * Configuration key used for {@link #EC2_PRICES_SPOT}
	 */
	public static final String CONF_URL_EC2_PRICES_SPOT = String.format(CONF_URL_API_PRICES, "ec2-spot");

	/**
	 * Configuration key used for {@link #EBS_PRICES}
	 */
	public static final String CONF_URL_EBS_PRICES = String.format(CONF_URL_API_PRICES, "ebs");

	/**
	 * Configuration key used for {@link #EFS_PRICES}
	 */
	public static final String CONF_URL_EFS_PRICES = String.format(CONF_URL_API_PRICES, "efs");

	/**
	 * Configuration key used for {@link #S3_PRICES}
	 */
	public static final String CONF_URL_S3_PRICES = String.format(CONF_URL_API_PRICES, "s3");

	/**
	 * Configuration key used for enabled regions pattern names. When value is <code>null</code>, no restriction.
	 */
	public static final String CONF_REGIONS = ProvAwsPluginResource.KEY + ":regions";

	/**
	 * Configuration key used for enabled OS pattern names. When value is <code>null</code>, no restriction.
	 */
	public static final String CONF_OS = ProvAwsPluginResource.KEY + ":os";

	/**
	 * Configuration key used for enabled instance type pattern names. When value is <code>null</code>, no restriction.
	 */
	public static final String CONF_ITYPE = ProvAwsPluginResource.KEY + ":instance-type";

	private static final double HOUR_TO_MONTH = 24 * 30.5;

	/**
	 * Mapping from Spot region name to API name.
	 */
	private Map<String, String> mapSpotToNewRegion = new HashMap<>();
	/**
	 * Mapping from API region identifier to region name.
	 */
	private Map<String, ProvLocation> mapRegionToName = new HashMap<>();

	/**
	 * Mapping from storage human name to API name.
	 */
	private Map<String, String> mapStorageToApi = new HashMap<>();

	/**
	 * Mapping from AWS software to standard form.
	 */
	private Map<String, String> mapSoftware = new HashMap<>();

	@Autowired
	protected CsvForJpa csvForBean;

	/**
	 * Install or update prices.
	 *
	 * @throws IOException
	 *             When CSV or XML files cannot be read.
	 * @throws URISyntaxException
	 *             When CSV or XML files cannot be read.
	 */
	public void install() throws IOException, URISyntaxException {
		final UpdateContext context = new UpdateContext();
		context.setValidOs(Pattern.compile(configuration.get(CONF_OS, ".*")));
		context.setValidInstanceType(Pattern.compile(configuration.get(CONF_ITYPE, ".*")));
		context.setValidRegion(Pattern.compile(configuration.get(CONF_REGIONS, ".*")));

		// Node is already persisted, install EC2 prices
		final Node node = nodeRepository.findOneExpected(ProvAwsPluginResource.KEY);
		context.setNode(node);

		// Install support prices
		installSupportPrices(context);

		// The previously installed location cache. Key is the location AWS name
		context.setRegions(locationRepository.findAllBy(BY_NODE, node).stream().filter(r -> isEnabledRegion(context, r))
				.collect(Collectors.toMap(INamableBean::getName, Function.identity())));
		nextStep(node, null, 0);

		// Proceed to the install
		installEbsPrices(context);
		installComputePrices(context);

		// S3 and NFS need to be processed after EC2 for region mapping
		installS3Prices(context);
		installEfsPrices(context);
	}

	/**
	 * Install EBS prices from the JSON file provided by AWS.
	 *
	 * @param context
	 *            The update context.
	 */
	private void installEbsPrices(final UpdateContext context) throws IOException {
		// The previously installed storage types cache. Key is the storage name
		final Node node = context.getNode();
		context.setStorageTypes(installStorageTypes(context));

		// Install EBS prices
		installJsonPrices(context, "ebs", configuration.get(CONF_URL_EBS_PRICES, EBS_PRICES), EbsPrices.class,
				(r, region) -> {
					// Get previous prices for this location
					final Map<Integer, ProvStoragePrice> previous = spRepository.findAll(node.getId(), region.getName())
							.stream().collect(Collectors.toMap(p -> p.getType().getId(), Function.identity()));
					return (int) r.getTypes().stream().filter(t -> containsKey(context, t)).filter(
							t -> t.getValues().stream().filter(j -> !"perPIOPSreq".equals(j.getRate())).anyMatch(
									j -> install(j, context.getStorageTypes().get(t.getName()), region, previous)))
							.count();
				});
	}

	private Map<String, ProvStorageType> installStorageTypes(final UpdateContext context) throws IOException {
		final Map<String, ProvStorageType> previous = stRepository.findAllBy(BY_NODE, context.getNode()).stream()
				.collect(Collectors.toMap(INamableBean::getName, Function.identity()));
		csvForBean.toBean(ProvStorageType.class, "csv/aws-prov-storage-type.csv").forEach(t -> {
			final ProvStorageType entity = previous.computeIfAbsent(t.getName(), n -> {
				t.setNode(context.getNode());
				return t;
			});

			// Merge the storage type details
			entity.setDescription(t.getDescription());
			entity.setInstanceCompatible(t.isInstanceCompatible());
			entity.setIops(t.getIops());
			entity.setLatency(t.getLatency());
			entity.setMaximal(t.getMaximal());
			entity.setMinimal(t.getMinimal());
			entity.setOptimized(t.getOptimized());
			entity.setThroughput(t.getThroughput());
			stRepository.save(entity);

		});
		return previous;
	}

	/**
	 * Install previous support prices.
	 */
	private Map<String, ProvSupportPrice> installSupportPrices(final UpdateContext context) throws IOException {
		// Install previous types
		installSupportTypes(context);

		// Fetch previous prices
		final Map<String, ProvSupportPrice> previous = sp2Repository.findAllBy("type.node", context.getNode()).stream()
				.collect(Collectors.toMap(AbstractPrice::getCode, Function.identity()));

		// Complete the set
		csvForBean.toBean(ProvSupportPrice.class, "csv/aws-prov-support-price.csv").forEach(t -> {
			final ProvSupportPrice entity = previous.computeIfAbsent(t.getCode(), n -> t);

			// Merge the support type details
			entity.setCost(t.getCost());
			entity.setLimit(t.getLimit());
			entity.setMin(t.getMin());
			entity.setRate(t.getRate());

			sp2Repository.save(entity);

		});
		return previous;
	}

	private void installSupportTypes(final UpdateContext context) throws IOException {
		// Fetch previous prices
		final Map<String, ProvSupportType> previous = st2Repository.findAllBy(BY_NODE, context.getNode()).stream()
				.collect(Collectors.toMap(INamableBean::getName, Function.identity()));

		// Complete the set
		csvForBean.toBean(ProvSupportType.class, "csv/aws-prov-support-type.csv").forEach(t -> {
			final ProvSupportType entity = previous.computeIfAbsent(t.getName(), n -> t);

			// Merge the support type details
			entity.setDescription(t.getDescription());
			entity.setAccessApi(t.getAccessApi());
			entity.setAccessChat(t.getAccessChat());
			entity.setAccessEmail(t.getAccessEmail());
			entity.setAccessPhone(t.getAccessPhone());
			entity.setSlaStartTime(t.getSlaStartTime());
			entity.setSlaEndTime(t.getSlaEndTime());

			entity.setSlaBusinessCriticalSystemDown(t.getSlaBusinessCriticalSystemDown());
			entity.setSlaGeneralGuidance(t.getSlaGeneralGuidance());
			entity.setSlaProductionSystemDown(t.getSlaProductionSystemDown());
			entity.setSlaProductionSystemImpaired(t.getSlaProductionSystemImpaired());
			entity.setSlaSystemImpaired(t.getSlaSystemImpaired());
			entity.setSlaWeekEnd(t.isSlaWeekEnd());

			entity.setCommitment(t.getCommitment());
			entity.setSeats(t.getSeats());
			entity.setLevel(t.getLevel());
			st2Repository.save(entity);
		});
	}

	/**
	 * Convert the JSON name to the API name and check this storage is exists
	 *
	 * @param context
	 *            The update context.
	 * @param storage
	 *            The storage to evaluate.
	 * @return <code>true</code> when the storage is valid.
	 */
	private <T extends INamableBean<?>> boolean containsKey(final UpdateContext context, final T storage) {
		storage.setName(mapStorageToApi.getOrDefault(storage.getName(), storage.getName()));
		return context.getStorageTypes().containsKey(storage.getName());
	}

	/**
	 * Install compute prices from the JSON file provided by AWS.
	 *
	 * @param context
	 *            The update context.
	 */
	private void installComputePrices(final UpdateContext context) throws IOException {
		// Create the Spot instance price type
		final Node node = context.getNode();
		final ProvInstancePriceTerm spotPriceType = newSpotInstanceType(node);
		context.setPriceTerms(iptRepository.findAllBy(BY_NODE, node).stream()
				.collect(Collectors.toMap(ProvInstancePriceTerm::getCode, Function.identity())));
		installEc2(context, node, spotPriceType);
		installRds(context, node);
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
	 * Install the RDS prices
	 */
	private void installRds(final UpdateContext context, final Node node) {
		context.setDatabaseTypes(dtRepository.findAllBy(BY_NODE, node).stream()
				.collect(Collectors.toMap(ProvDatabaseType::getName, Function.identity())));
		importCatalogResource.nextStep(context.getNode().getId(), t -> t.setPhase("rds"));
		context.getRegions().values().forEach(region -> {
			nextStep(node, region.getName(), 1);
			// Get previous RDS storage and instance prices for this location
			context.setPreviousDatabase(dpRepository.findAll(node.getId(), region.getName()).stream()
					.collect(Collectors.toMap(ProvDatabasePrice::getCode, Function.identity())));
			context.setPreviousStorage(spRepository.findAll(context.getNode().getId(), region.getName()).stream()
					.collect(Collectors.toMap(ProvStoragePrice::getCode, Function.identity())));
			installRdsPrices(context, region);
		});
		context.getDatabaseTypes().clear();
		context.getPreviousDatabase().clear();
	}

	/**
	 * Install AWS prices from a JSON file.
	 *
	 * @param context
	 *            The update context.
	 * @param api
	 *            The API name, only for log.
	 * @param endpoint
	 *            The prices end-point JSON URL.
	 * @param apiClass
	 *            The mapping model from JSON at region level.
	 * @param mapper
	 *            The mapping function from JSON at region level to JPA entity.
	 */
	private <R extends AwsRegionPrices, T extends AwsPrices<R>> void installJsonPrices(final UpdateContext context,
			final String api, final String endpoint, final Class<T> apiClass,
			final BiFunction<R, ProvLocation, Integer> mapper) throws IOException {
		log.info("AWS {} prices...", api);

		// Track the created instance to cache instance and price type
		int priceCounter = 0;
		importCatalogResource.nextStep(context.getNode().getId(), t -> t.setPhase(api));

		try (CurlProcessor curl = new CurlProcessor()) {
			// Get the remote prices stream
			final String rawJson = StringUtils.defaultString(curl.get(endpoint),
					"callback({\"config\":{\"regions\":[]}});");

			// All regions are considered
			final int configIndex = rawJson.indexOf('{');
			final int configCloseIndex = rawJson.lastIndexOf('}');
			final T prices = objectMapper.readValue(rawJson.substring(configIndex, configCloseIndex + 1), apiClass);

			// Install the enabled region as needed
			final List<R> eRegions = prices.getConfig().getRegions().stream()
					.peek(r -> r.setRegion(mapSpotToNewRegion.getOrDefault(r.getRegion(), r.getRegion())))
					.filter(r -> isEnabledRegion(context, r)).collect(Collectors.toList());
			eRegions.forEach(r -> installRegion(context, r.getRegion()));
			nextStep(context, null, 0);

			// Install the prices for each region
			priceCounter = eRegions.stream().mapToInt(r -> mapper.apply(r, context.getRegions().get(r.getRegion())))
					.sum();
		} finally {
			// Report
			log.info("AWS {} import finished : {} prices", api, priceCounter);
			nextStep(context, null, 1);
		}
	}

	/**
	 * Install S3 AWS prices from the CSV file. Note only the first 50TB storage tiers is considered
	 *
	 * @param context
	 *            The update context.
	 * @param api
	 *            The API name, only for log.
	 * @param endpoint
	 *            The prices end-point JSON URL.
	 * @param apiClass
	 *            The mapping model from JSON at region level.
	 * @param mapper
	 *            The mapping function from JSON at region level to JPA entity.
	 */
	private void installS3Prices(final UpdateContext context) throws IOException, URISyntaxException {
		log.info("AWS S3 prices ...");
		importCatalogResource.nextStep(context.getNode().getId(), t -> t.setPhase("s3"));

		// Track the created instance to cache partial costs
		final Map<String, ProvStoragePrice> previous = spRepository.findAllBy("type.node", context.getNode()).stream()
				.filter(p -> p.getType().getName().startsWith("s3") || "glacier".equals(p.getType().getName()))
				.collect(Collectors.toMap(p2 -> p2.getLocation().getName() + p2.getType().getName(),
						Function.identity()));
		context.setPreviousStorage(previous);
		context.setStorageTypes(previous.values().stream().map(ProvStoragePrice::getType).distinct()
				.collect(Collectors.toMap(ProvStorageType::getName, Function.identity())));
		context.setStorageTypesMerged(new HashMap<>());

		int priceCounter = 0;
		// Get the remote prices stream
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				new URI(configuration.get(CONF_URL_S3_PRICES, S3_PRICES)).toURL().openStream()))) {
			// Pipe to the CSV reader
			final CsvForBeanS3 csvReader = new CsvForBeanS3(reader);

			// Build the AWS storage prices from the CSV
			AwsS3Price csv = csvReader.read();
			while (csv != null) {
				final ProvLocation location = getRegionByHumanName(context, csv.getLocation());
				if (location != null) {
					// Supported location
					instalS3Price(context, csv, location);
					priceCounter++;
				}

				// Read the next one
				csv = csvReader.read();
			}
		} finally {
			// Report
			log.info("AWS S3 finished : {} prices", priceCounter);
			nextStep(context, null, 1);
		}
	}

	private Double toPercent(String raw) {
		if (StringUtils.endsWith(raw, "%")) {
			return Double.valueOf(raw.substring(0, raw.length() - 1));
		}

		// Not a valid percent
		return null;
	}

	private void instalS3Price(final UpdateContext context, final AwsS3Price csv, final ProvLocation location) {
		// Resolve the type
		final String name = mapStorageToApi.get(csv.getVolumeType());
		if (name == null) {
			log.warn("Unknown storage type {}, ignored", csv.getVolumeType());
			return;
		}

		final ProvStorageType type = context.getStorageTypesMerged().computeIfAbsent(name, n -> {
			final ProvStorageType t = context.getStorageTypes().computeIfAbsent(name, n2 -> {
				// New storage type
				final ProvStorageType newType = new ProvStorageType();
				newType.setName(n2);
				newType.setNode(context.getNode());
				return newType;
			});

			// Update storage details
			t.setAvailability(toPercent(csv.getAvailability()));
			t.setDurability9(StringUtils.countMatches(StringUtils.defaultString(csv.getDurability()), '9'));
			t.setOptimized(ProvStorageOptimized.DURABILITY);
			t.setLatency(name.equals("glacier") ? Rate.WORST : Rate.MEDIUM);
			t.setDescription("{\"class\":\"" + csv.getStorageClass() + "\",\"type\":\"" + csv.getVolumeType() + "\"}");
			stRepository.save(t);
			return t;
		});

		// Update the price as needed
		final ProvStoragePrice price = context.getPreviousStorage().computeIfAbsent(location.getName() + name, r -> {
			final ProvStoragePrice p = new ProvStoragePrice();
			p.setLocation(location);
			p.setType(type);
			p.setCode(csv.getSku());
			return p;
		});
		price.setCode(csv.getSku());
		saveAsNeeded(price, csv.getPricePerUnit(), spRepository::save);
	}

	/**
	 * Install AWS prices from the CSV file.
	 *
	 * @param context
	 *            The update context.
	 * @param api
	 *            The API name, only for log.
	 * @param endpoint
	 *            The prices end-point JSON URL.
	 * @param apiClass
	 *            The mapping model from JSON at region level.
	 * @param mapper
	 *            The mapping function from JSON at region level to JPA entity.
	 */
	private void installEfsPrices(final UpdateContext context) throws IOException, URISyntaxException {
		log.info("AWS EFS prices ...");
		importCatalogResource.nextStep(context.getNode().getId(), t -> t.setPhase("efs"));

		// Track the created instance to cache partial costs
		final ProvStorageType efs = stRepository.findAllBy(BY_NODE, context.getNode(), new String[] { "name" }, "efs")
				.get(0);
		final Map<ProvLocation, ProvStoragePrice> previous = spRepository.findAllBy("type", efs).stream()
				.collect(Collectors.toMap(ProvStoragePrice::getLocation, Function.identity()));

		int priceCounter = 0;
		// Get the remote prices stream
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				new URI(configuration.get(CONF_URL_EFS_PRICES, EFS_PRICES)).toURL().openStream()))) {
			// Pipe to the CSV reader
			final CsvForBeanEfs csvReader = new CsvForBeanEfs(reader);

			// Build the AWS instance prices from the CSV
			AwsCsvPrice csv = csvReader.read();
			while (csv != null) {
				final ProvLocation location = getRegionByHumanName(context, csv.getLocation());
				if (location != null) {
					// Supported location
					instalEfsPrice(efs, previous, csv, location);
					priceCounter++;
				}
				// Read the next one
				csv = csvReader.read();
			}
		} finally {
			// Report
			log.info("AWS EFS finished : {} prices", priceCounter);
			nextStep(context, null, 1);
		}
	}

	private void instalEfsPrice(final ProvStorageType efs, final Map<ProvLocation, ProvStoragePrice> previous,
			AwsCsvPrice csv, final ProvLocation location) {
		// Update the price as needed
		final ProvStoragePrice price = previous.computeIfAbsent(location, r -> {
			final ProvStoragePrice p = new ProvStoragePrice();
			p.setLocation(r);
			p.setType(efs);
			p.setCode(csv.getSku());
			return p;
		});
		price.setCode(csv.getSku());
		saveAsNeeded(price, csv.getPricePerUnit(), spRepository::save);
	}

	/**
	 * Return the {@link ProvLocation} matching the human name.
	 *
	 * @param context
	 *            The update context.
	 * @param humanName
	 *            The required human name.
	 * @return The corresponding {@link ProvLocation} or <code>null</code>.
	 */
	private ProvLocation getRegionByHumanName(final UpdateContext context, final String humanName) {
		return context.getRegions().values().stream().filter(r -> isEnabledRegion(context, r))
				.filter(r -> humanName.equals(r.getDescription())).findAny().orElse(null);
	}

	/**
	 * Update the statistics
	 */
	private void nextStep(final Node node, final String location, final int step) {
		importCatalogResource.nextStep(node.getId(), t -> {
			importCatalogResource.updateStats(t);
			t.setWorkload(t.getNbLocations() * 3 + 4); // NB region (for EC2 + Spot + RDS) + 3 (S3+EBS+EFS)
			t.setDone(t.getDone() + step);
			t.setLocation(location);
		});
	}

	/**
	 * Update the statistics
	 */
	private void nextStep(final UpdateContext context, final String location, final int step) {
		nextStep(context.getNode(), location, step);
	}

	/**
	 * Install a new region.
	 */
	private ProvLocation installRegion(final UpdateContext context, final String region) {
		final ProvLocation entity = context.getRegions().computeIfAbsent(region, r -> {
			final ProvLocation newRegion = new ProvLocation();
			newRegion.setNode(context.getNode());
			newRegion.setName(r);
			return newRegion;
		});

		// Update the location details as needed
		if (context.getRegionsMerged().add(region)) {
			final ProvLocation regionStats = mapRegionToName.getOrDefault(region, new ProvLocation());
			entity.setContinentM49(regionStats.getContinentM49());
			entity.setCountryA2(regionStats.getCountryA2());
			entity.setCountryM49(regionStats.getCountryM49());
			entity.setPlacement(regionStats.getPlacement());
			entity.setRegionM49(regionStats.getRegionM49());
			entity.setSubRegion(regionStats.getSubRegion());
			entity.setLatitude(regionStats.getLatitude());
			entity.setLongitude(regionStats.getLongitude());
			entity.setDescription(regionStats.getName());
			locationRepository.saveAndFlush(entity);
		}
		return entity;
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

	/**
	 * Download and install EC2 prices from AWS server.
	 *
	 * @param context
	 *            The update context.
	 * @param region
	 *            The region to fetch.
	 * @return The amount installed EC2 instances.
	 */
	protected int installEC2Prices(final UpdateContext context, final ProvLocation region) {
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
	 * Download and install EC2 prices from AWS server.
	 *
	 * @param context
	 *            The update context.
	 * @param region
	 *            The region to fetch.
	 * @return The amount installed EC2 instances.
	 */
	private int installRdsPrices(final UpdateContext context, final ProvLocation region) {
		// Track the created instance to cache partial costs
		context.setPartialCostRds(new HashMap<>());
		final String endpoint = configuration.get(CONF_URL_RDS_PRICES, RDS_PRICES).replace("%s", region.getName());
		log.info("AWS RDS OnDemand/Reserved import started for region {}@{} ...", region, endpoint);
		int priceCounter = 0;

		// Get the remote prices stream
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(new URI(endpoint).toURL().openStream()))) {
			// Pipe to the CSV reader
			final CsvForBeanRds csvReader = new CsvForBeanRds(reader);

			// Build the AWS instance prices from the CSV
			AwsRdsPrice csv = csvReader.read();
			while (csv != null) {
				// Complete the region human name associated to the API one
				region.setDescription(csv.getLocation());

				// Persist this price
				priceCounter += installRds(context, csv, region);

				// Read the next one
				csv = csvReader.read();
			}
		} catch (final IOException | URISyntaxException use) {
			// Something goes wrong for this region, stop for this region
			log.info("AWS RDS OnDemand/Reserved import failed for region {}", region.getName(), use);
		} finally {
			// Report
			log.info("AWS RDS OnDemand/Reserved import finished for region {} : {} instance, {} price types, {} prices",
					region.getName(), context.getInstanceTypes().size(), context.getPriceTerms().size(), priceCounter);
		}

		// Return the available instances types
		return priceCounter;
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
	private int installRds(final UpdateContext context, final AwsRdsPrice csv, final ProvLocation region) {
		// Up-front, partial or not
		int priceCounter = 0;
		if (UPFRONT_MODE.matcher(StringUtils.defaultString(csv.getPurchaseOption())).find()) {
			// Up-front ALL/PARTIAL
			final Map<String, AwsRdsPrice> partialCost = context.getPartialCostRds();
			final String code = csv.getSku() + csv.getOfferTermCode() + region.getName();
			if (partialCost.containsKey(code)) {
				handleUpfront(newRdsPrice(context, csv, region), csv, partialCost.get(code), dpRepository);

				// The price is completed, cleanup
				partialCost.remove(code);
				priceCounter++;
			} else {
				// First time, save this entry for a future completion
				partialCost.put(code, csv);
			}
		} else if ("Database Instance".equals(csv.getFamily())) {
			// No up-front, cost is fixed
			priceCounter++;
			final ProvDatabasePrice price = newRdsPrice(context, csv, region);
			final double cost = csv.getPricePerUnit() * HOUR_TO_MONTH;
			saveAsNeeded(price, round3Decimals(cost), p -> {
				p.setCostPeriod(round3Decimals(cost * p.getTerm().getPeriod()));
				dpRepository.save(p);
			});
		} else {
			// Database storage
			priceCounter++;
			final ProvStorageType type = installRdsStorageTypeAsNeeded(context, csv);
			final ProvStoragePrice price = context.getPreviousStorage().computeIfAbsent(csv.getSku(), s -> {
				final ProvStoragePrice p = new ProvStoragePrice();
				p.setType(type);
				p.setCode(csv.getSku());
				p.setLocation(region);
				return p;
			});

			// Update the price as needed
			saveAsNeeded(price, csv.getPricePerUnit(), spRepository::save);
		}
		return priceCounter;
	}

	/**
	 * Install the RDS storage type as needed, and return it.
	 */
	private final ProvStorageType installRdsStorageTypeAsNeeded(final UpdateContext context, final AwsRdsPrice csv) {
		// RDS Storage type is composition of
		final String name;
		final String engine;
		if ("General Purpose-Aurora".equals(csv.getVolume())) {
			if ("Aurora PostgreSQL".equals(csv.getEngine())) {
				name = "gp-aurora-postgresql";
				engine = "Aurora PostgreSQL";
			} else {
				name = "gp-aurora-mysql";
				engine = "Aurora MySQL";
			}
		} else {
			engine = null;
			if ("General Purpose".equals(csv.getVolume())) {
				name = "gp-rds";
			} else if ("Provisioned IOPS".equals(csv.getVolume())) {
				name = "io-rds";
			} else {
				name = "magnetic-rds";
			}
		}

		return context.getStorageTypes().computeIfAbsent(name, n -> {
			final ProvStorageType entity = new ProvStorageType();
			final boolean ssd = "SSD".equals(csv.getStorage());
			entity.setNode(context.getNode());
			entity.setName(n);
			entity.setDescription(csv.getVolume());
			entity.setMinimal(toInteger(csv.getSizeMin()));
			entity.setMaximal(toInteger(csv.getSizeMax()));
			entity.setEngine(engine == null ? null : engine.toUpperCase(Locale.ENGLISH));
			entity.setDatabaseCompatible(true);
			entity.setOptimized(ssd ? ProvStorageOptimized.IOPS : null);
			entity.setLatency(ssd ? Rate.BEST : Rate.MEDIUM);
			stRepository.save(entity);
			return entity;
		});
	}

	private Integer toInteger(final String value) {
		return Optional.ofNullable(StringUtils.trimToNull(value))
				.map(v -> StringUtils.replaceEach(v, new String[] { "GB", "TB", " " }, new String[] { "", "", "" }))
				.map(Integer::valueOf).map(v -> value.contains("TB") ? v * 1024 : v).orElse(null);
	}

	private <T extends AbstractInstanceType, P extends AbstractTermPrice<T>> P saveAsNeeded(final P entity,
			final double newCost, final Consumer<P> c) {
		return saveAsNeeded(entity, entity.getCost(), newCost, entity::setCost, c);
	}

	private ProvStoragePrice saveAsNeeded(final ProvStoragePrice entity, final double newCostGb,
			final Consumer<ProvStoragePrice> c) {
		return saveAsNeeded(entity, entity.getCostGb(), newCostGb, entity::setCostGb, c);
	}

	private <A extends Serializable, N extends AbstractNamedEntity<A>, T extends AbstractPrice<N>> T saveAsNeeded(
			final T entity, final double oldCost, final double newCost, final DoubleConsumer updateCost,
			final Consumer<T> c) {
		if (oldCost != newCost) {
			updateCost.accept(newCost);
			c.accept(entity);
		}
		return entity;
	}

	/**
	 * Install the EBS/S3 price using the related storage type.
	 *
	 * @param json
	 *            The current JSON entry.
	 * @param type
	 *            The related storage type.
	 * @param region
	 *            The target region.
	 * @return The amount of installed prices. Only for the report.
	 */
	private <T extends AwsPrice> boolean install(final T json, final ProvStorageType type, final ProvLocation region,
			final Map<Integer, ProvStoragePrice> previous) {
		return Optional.ofNullable(json.getPrices().get("USD")).filter(NumberUtils::isParsable)
				.map(usd -> install(type, region, previous, usd)).isPresent();
	}

	private ProvStoragePrice install(final ProvStorageType type, final ProvLocation region,
			final Map<Integer, ProvStoragePrice> previous, String usd) {
		final ProvStoragePrice price = previous.computeIfAbsent(type.getId(), s -> {
			final ProvStoragePrice p = new ProvStoragePrice();
			p.setType(type);
			p.setCode(region.getName() + "-" + type.getName());
			p.setLocation(region);
			return p;
		});
		price.setCode(region.getName() + "-" + type.getName());

		// Update the price as needed
		return saveAsNeeded(price, Double.valueOf(usd), spRepository::save);
	}

	private <T extends AbstractInstanceType, P extends AbstractTermPrice<T>, C extends AbstractAwsEc2Price> void handleUpfront(
			final P price, final C csv, final C other, final BaseProvTermPriceRepository<T, P> repository) {
		final AbstractAwsEc2Price quantity;
		final AbstractAwsEc2Price hourly;
		if (csv.getPriceUnit().equals("Quantity")) {
			quantity = csv;
			hourly = other;
		} else {
			quantity = other;
			hourly = csv;
		}

		// Round the computed hourly cost and save as needed
		final double initCost = quantity.getPricePerUnit() / price.getTerm().getPeriod();
		final double cost = hourly.getPricePerUnit() * HOUR_TO_MONTH + initCost;
		saveAsNeeded(price, round3Decimals(cost), p -> {
			p.setInitialCost(quantity.getPricePerUnit());
			p.setCostPeriod(round3Decimals(
					p.getInitialCost() + hourly.getPricePerUnit() * p.getTerm().getPeriod() * HOUR_TO_MONTH));
			repository.save(p);
		});
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
	 * Install or update a RDS price
	 */
	private ProvDatabasePrice newRdsPrice(final UpdateContext context, final AwsRdsPrice csv,
			final ProvLocation region) {
		final ProvDatabaseType type = installInstanceType(context, csv, context.getDatabaseTypes(),
				ProvDatabaseType::new, dtRepository);
		return context.getPreviousDatabase().computeIfAbsent(toCode(csv), c -> {
			final ProvDatabasePrice p = new ProvDatabasePrice();
			copy(context, csv, region, c, p, type);
			p.setEngine(StringUtils.trimToNull(csv.getEngine().toUpperCase(Locale.ENGLISH)));
			p.setEdition(StringUtils.trimToNull(StringUtils.trimToEmpty(csv.getEdition()).toUpperCase(Locale.ENGLISH)));
			return p;
		});
	}

	private <T extends AbstractInstanceType> void copy(final UpdateContext context, final AbstractAwsEc2Price csv,
			final ProvLocation region, final String code, final AbstractTermPrice<T> p, final T instance) {
		p.setLocation(region);
		p.setCode(code);
		p.setLicense(StringUtils.trimToNull(csv.getLicenseModel().replace("No License required", "")
				.replace("No license required", "").replace("Bring your own license", ProvInstancePrice.LICENSE_BYOL)));
		p.setType(instance);
		p.setTerm(context.getPriceTerms().computeIfAbsent(csv.getOfferTermCode(),
				k -> newInstancePriceTerm(context, csv)));
	}

	private String toCode(final AbstractAwsEc2Price csv) {
		return csv.getSku() + csv.getOfferTermCode();
	}

	/**
	 * Install a new EC2/RDS instance type
	 */
	private <T extends AbstractInstanceType> T installInstanceType(final UpdateContext context,
			final AbstractAwsEc2Price csv, Map<String, T> previous, Supplier<T> newType,
			final BaseProvInstanceTypeRepository<T> repository) {
		final T type = previous.computeIfAbsent(csv.getInstanceType(), k -> {
			final T t = newType.get();
			t.setNode(context.getNode());
			t.setName(csv.getInstanceType());
			return t;
		});

		// Update the statistics only once
		if (context.getInstanceTypesMerged().add(type.getName())) {
			type.setCpu(csv.getCpu());
			type.setConstant(!type.getName().startsWith("t") && !type.getName().startsWith("db.t"));
			type.setDescription(ArrayUtils.toString(ArrayUtils
					.removeAllOccurences(new String[] { csv.getPhysicalProcessor(), csv.getClockSpeed() }, null)));

			// Convert GiB to MiB, and rounded
			final String memoryStr = StringUtils.removeEndIgnoreCase(csv.getMemory(), " GiB").replace(",", "");
			type.setRam((int) Math.round(Double.parseDouble(memoryStr) * 1024d));

			// Rating
			type.setCpuRate(getRate("cpu", csv));
			type.setRamRate(getRate("ram", csv));
			type.setNetworkRate(getRate("network", csv, csv.getNetworkPerformance()));
			type.setStorageRate(toStorage(csv));

			// Need this update
			repository.save(type);
		}
		return type;
	}

	private Rate toStorage(final AbstractAwsEc2Price csv) {
		Rate rate = getRate("storage", csv);
		if (!"EBS only".equals(csv.getStorage())) {
			// Upgrade for non EBS
			rate = Rate.values()[Math.min(rate.ordinal(), rate.ordinal() + 1)];
		}
		return rate;
	}

	/**
	 * Return the most precise rate from the AWS instance type definition.
	 *
	 * @param type
	 *            The rating mapping name.
	 * @param csv
	 *            The CSV price row.
	 * @return The direct [class, generation, size] rate association, or the [class, generation] rate association, or
	 *         the [class] association, of the explicit "default association or {@link Rate#MEDIUM} value.
	 */
	private Rate getRate(final String type, final AbstractAwsEc2Price csv) {
		return getRate(type, csv, csv.getInstanceType());
	}

	/**
	 * Return the most precise rate from a name.
	 *
	 * @param type
	 *            The rating mapping name.
	 * @param name
	 *            The name to map.
	 * @param csv
	 *            The CSV price row.
	 * @return The direct [class, generation, size] rate association, or the [class, generation] rate association, or
	 *         the [class] association, of the explicit "default association or {@link Rate#MEDIUM} value. Previous
	 *         generations types are downgraded.
	 */
	protected Rate getRate(final String type, final AbstractAwsEc2Price csv, final String name) {
		Rate rate = getRate(type, StringUtils.replaceOnce(name, "db\\.", ""));

		// Downgrade the rate for a previous generation
		if ("No".equals(csv.getCurrentGeneration())) {
			rate = Rate.values()[Math.max(0, rate.ordinal() - 1)];
		}
		return rate;
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
	private ProvInstancePriceTerm newInstancePriceTerm(final UpdateContext context, final AbstractAwsEc2Price csv) {
		final ProvInstancePriceTerm term = new ProvInstancePriceTerm();
		term.setNode(context.getNode());
		term.setCode(csv.getOfferTermCode());

		// Build the name from the leasing, purchase option and offering class
		final String name = StringUtils.trimToNull(RegExUtils.removeAll(
				RegExUtils.replaceAll(csv.getPurchaseOption(), "([a-z])Upfront", "$1 Upfront"), "No\\s*Upfront"));
		term.setName(Arrays
				.stream(new String[] { csv.getTermType(), StringUtils.replace(csv.getLeaseContractLength(), " ", ""),
						name, StringUtils.trimToNull(StringUtils.remove(csv.getOfferingClass(), "standard")) })
				.filter(Objects::nonNull).collect(Collectors.joining(", ")));

		// Handle leasing
		final Matcher matcher = LEASING_TIME.matcher(StringUtils.defaultIfBlank(csv.getLeaseContractLength(), ""));
		if (matcher.find()) {
			// Convert years to months
			term.setPeriod(Integer.parseInt(matcher.group(1)) * 12d);
		}
		iptRepository.save(term);
		return term;
	}

	/**
	 * Indicate the given region is enabled.
	 *
	 * @param context
	 *            The update context.
	 * @param region
	 *            The region API name to test.
	 * @return <code>true</code> when the configuration enable the given region.
	 */
	private boolean isEnabledRegion(final UpdateContext context, final AwsRegionPrices region) {
		return isEnabledRegion(context, region.getRegion());
	}

	/**
	 * Indicate the given region is enabled.
	 *
	 * @param context
	 *            The update context.
	 * @param region
	 *            The region API name to test.
	 * @return <code>true</code> when the configuration enable the given region.
	 */
	private boolean isEnabledRegion(final UpdateContext context, final ProvLocation region) {
		return isEnabledRegion(context, region.getName());
	}

	/**
	 * Indicate the given region is enabled.
	 *
	 * @param context
	 *            The update context.
	 * @param region
	 *            The region API name to test.
	 * @return <code>true</code> when the configuration enable the given region.
	 */
	private boolean isEnabledRegion(final UpdateContext context, final String region) {
		return context.getValidRegion().matcher(region).matches();
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
	 * Indicate the given instance type is enabled.
	 *
	 * @param context
	 *            The update context.
	 * @param type
	 *            The instance type to test.
	 * @return <code>true</code> when the configuration enable the given instance type.
	 */
	private boolean isEnabledType(final UpdateContext context, final String type) {
		return context.getValidInstanceType().matcher(type).matches();
	}

	/**
	 *
	 * Read the spot region to the new format from an external JSON file. File containing the mapping from the spot
	 * region name to the new format one. This mapping is required because of the old naming format used for the first
	 * region's name. Non mapped regions use the new name in spot JSON file.
	 *
	 * @throws IOException
	 *             When the JSON mapping file cannot be read.
	 */
	@PostConstruct
	public void initSpotToNewRegion() throws IOException {
		mapRegionToName.putAll(toMap("aws-regions.json", MAP_LOCATION));
		mapSpotToNewRegion.putAll(toMap("spot-to-new-region.json", MAP_STR));
	}

	/**
	 * Read the EBS/S3 mapping to API name from an external JSON file.
	 *
	 * @throws IOException
	 *             When the JSON mapping file cannot be read.
	 */
	@PostConstruct
	public void initEbsToApi() throws IOException {
		mapStorageToApi.putAll(toMap("storage-to-api.json", MAP_STR));
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

	private <T> Map<String, T> toMap(final String path, final TypeReference<Map<String, T>> type) throws IOException {
		return objectMapper.readValue(
				IOUtils.toString(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8), type);
	}

	/**
	 * Read the network rate mapping. File containing the mapping from the AWS network rate to the normalized
	 * application rating.
	 *
	 * @see <a href="https://calculator.s3.amazonaws.com/index.html">calculator</a>
	 * @see <a href="https://aws.amazon.com/ec2/instance-types/">instance-types</a>
	 *
	 * @throws IOException
	 *             When the JSON mapping file cannot be read.
	 */
	@PostConstruct
	public void initRate() throws IOException {
		initRate("storage");
		initRate("cpu");
		initRate("ram");
		initRate("network");
	}
}
