/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.prov.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.prov.catalog.Co2Data;
import org.ligoj.app.plugin.prov.catalog.Co2RegionData;
import org.ligoj.app.plugin.prov.catalog.ImportCatalog;
import org.ligoj.app.plugin.prov.model.AbstractCodedEntity;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.bootstrap.core.INamableBean;
import org.ligoj.bootstrap.core.csv.CsvBeanReader;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;

import lombok.extern.slf4j.Slf4j;

/**
 * The base import data.
 */
@Component
@Slf4j
public class AwsPriceImportBase extends AbstractAwsImport implements ImportCatalog<UpdateContext> {

	/**
	 * Configuration key used for enabled regions pattern names. When value is <code>null</code>, no restriction.
	 */
	public static final String CONF_REGIONS = ProvAwsPluginResource.KEY + ":regions";

	/**
	 * Path to root bulk price index.
	 */
	public static final String AWS_PRICES_PATH = "/offers/v1.0/aws/index.json";

	private static final String AWS_PRICES_BASE = "https://pricing.us-east-1.amazonaws.com";

	/**
	 * Configuration key used for AWS URL prices.
	 */
	public static final String CONF_URL_TMP_PRICES = ProvAwsPluginResource.KEY + ":%s-prices-url";

	/**
	 * Configuration key used for {@link #AWS_PRICES_BASE}
	 */
	public static final String CONF_URL_AWS_PRICES = String.format(CONF_URL_TMP_PRICES, "aws");

	/**
	 * Configuration key used for AWS URL CO2 dataset.
	 */
	public static final String CONF_URL_CO2_INSTANCE = ProvAwsPluginResource.KEY + ":co2-instance-url";
	/**
	 * Configuration key used for AWS URL CO2 regional (mix intensity) dataset.
	 */
	public static final String CONF_URL_CO2_REGION = ProvAwsPluginResource.KEY + ":co2-region-url";

	private static final TypeReference<Map<String, ProvLocation>> MAP_LOCATION = new TypeReference<>() {
		// Nothing to extend
	};

	private static final TypeReference<Map<String, Double>> MAP_BASELINE = new TypeReference<>() {
		// Nothing to extend
	};

	/**
	 * CO2 instance CSV Mapping to Java bean property
	 */
	private static final Map<String, String> CO2_INSTANCE_HEADERS_MAPPING = new HashMap<>();
	static {
		CO2_INSTANCE_HEADERS_MAPPING.put("Instance type", "type");
		Stream.of("Pkg", "GPU", "RAM")
				.forEach(t -> IntStream.range(0, 11)
						.forEach(p -> CO2_INSTANCE_HEADERS_MAPPING.put(
								"%sWatt @ %s".formatted(t, p == 0 ? "Idle" : "%s0%%".formatted(p)),
								"%sWatt%d".formatted(StringUtils.lowerCase(t), p * 10))));
		CO2_INSTANCE_HEADERS_MAPPING.put("Delta Full Machine", "extra");
		CO2_INSTANCE_HEADERS_MAPPING.put("Instance Hourly Manufacturing Emissions (gCO_eq)", "scope3");
	}

	/**
	 * CO2 regional CSV Mapping to Java bean property
	 */
	private static final Map<String, String> CO2_REGION_HEADERS_MAPPING = new HashMap<>();
	static {
		CO2_REGION_HEADERS_MAPPING.put("Region", "region");
		CO2_REGION_HEADERS_MAPPING.put("PUE", "pue");
		CO2_REGION_HEADERS_MAPPING.put("CO2e (metric gram/kWh)", "gPerKWH");
	}

	@Override
	public void install(final UpdateContext context) throws IOException {
		importCatalogResource.nextStep(context.getNode().getId(), t -> t.setPhase("region"));
		context.setValidRegion(Pattern.compile(configuration.get(CONF_REGIONS, ".*")));
		context.getMapRegionById().putAll(toMap("aws-regions.json", MAP_LOCATION));
		context.getBaselines().putAll(toMap("aws-baselines.json", MAP_BASELINE));

		// Complete the by-name map
		context.getMapStorageToApi().putAll(toMap("storage-to-api.json", MAP_STR));
		context.getMapRegionById().forEach((id, r) -> context.getMapStorageToApi().put(r.getName(), id));

		// The previously installed location cache. Key is the location AWS name
		context.setRegions(locationRepository.findAllBy(BY_NODE, context.getNode()).stream()
				.filter(r -> isEnabledRegion(context, r))
				.collect(Collectors.toMap(INamableBean::getName, Function.identity())));

		// The previously installed storage types cache. Key is the storage name
		context.setStorageTypes(stRepository.findAllBy(BY_NODE, context.getNode()).stream()
				.collect(Collectors.toMap(AbstractCodedEntity::getCode, Function.identity())));
		installStorageTypes(context);
		context.getMapSpotToNewRegion().putAll(toMap("spot-to-new-region.json", MAP_STR));
		loadBaseIndex(context);

		// Get CO2 dataset
		fetchCo2Data(context);

		nextStep(context, "region");
	}

	private void fetchCo2Data(final UpdateContext context) {
		fetchCo2DataGeneric("instance", CONF_URL_CO2_INSTANCE, context.getCo2DataSet(), CO2_INSTANCE_HEADERS_MAPPING,
				Co2Data::getType, Co2Data.class);
		fetchCo2DataGeneric("region", CONF_URL_CO2_REGION, context.getCo2RegionDataSet(), CO2_REGION_HEADERS_MAPPING,
				Co2RegionData::getRegion, Co2RegionData.class);
		context.getCo2DataSet().values().forEach(Co2Data::compute);
	}

	private <X> void fetchCo2DataGeneric(final String type, final String cUrl, final Map<String, X> co2DataSet,
			final Map<String, String> headersMapping, final Function<X, String> keyer, Class<X> clazz) {
		final var endpoint = configuration.get(cUrl);
		if (endpoint == null) {
			log.info("No provided {} dataset, if you have one, set the CSV URL to configuration '{}'", type, cUrl);
			// No provided CO2 dataset, ingore this step
			return;
		}

		// Get the remote CO2 stream
		try (var reader = new BufferedReader(new InputStreamReader(new URI(endpoint).toURL().openStream()))) {
			// Pipe to the CSV reader
			final var csvReader = new AbstractAwsCsvForBean<>(reader, headersMapping, clazz, ';') {

				@Override
				protected CsvBeanReader<X> newCsvReader(final Reader reader, final String[] headers,
						final Class<X> beanType) {
					return new AbstractAwsCsvReader<>(reader, headers, beanType, ';') {

						@Override
						protected boolean isValidRaw(final List<String> rawValues) {
							return true;
						}
					};
				}

				@Override
				protected boolean isHeaderRow(final List<String> values) {
					// No extra padding before headers
					return true;
				}
			};

			// Build the AWS instance prices from the CSV
			var csv = csvReader.read();
			while (csv != null) {
				co2DataSet.put(keyer.apply(csv), csv);

				// Read the next one
				csv = csvReader.read();
			}
		} catch (final IOException | URISyntaxException use) {
			// Something goes wrong for this region, stop for this region
			log.warn("AWS {} dataset fetch failed", type, use);
		} finally {
			// Report
			log.info("AWS {} dataset fetch ({})", type, co2DataSet.size());
		}
	}

	/**
	 * Get the root AWS bulk index file and save it in the context.
	 */
	private void loadBaseIndex(final UpdateContext context) throws IOException {
		final var basePrice = configuration.get(CONF_URL_AWS_PRICES, AWS_PRICES_BASE);
		context.setBaseUrl(basePrice);
		final var baseUrl = basePrice + AWS_PRICES_PATH;
		log.info("AWS {} import: download root index {}", "lambda", baseUrl);
		try (var reader = new BufferedReader(new InputStreamReader(new URL(baseUrl).openStream()))) {
			context.setOffers(objectMapper.readValue(reader, AwsPriceIndex.class).getOffers());
		}
	}

	private void installStorageTypes(final UpdateContext context) throws IOException {
		csvForBean.toBean(ProvStorageType.class, "csv/aws-prov-storage-type.csv").forEach(t -> {
			final var entity = context.getStorageTypes().computeIfAbsent(t.getName(), n -> {
				final var newType = new ProvStorageType();
				newType.setNode(context.getNode());
				newType.setCode(n);
				return newType;
			});

			// Merge the storage type details
			entity.setName(entity.getCode());
			entity.setDescription(t.getDescription());
			entity.setInstanceType(t.getInstanceType());
			entity.setContainerType(t.getContainerType());
			entity.setDatabaseType(t.getDatabaseType());
			entity.setFunctionType(t.getFunctionType());
			entity.setNotInstanceType(t.getNotInstanceType());
			entity.setNotContainerType(t.getNotContainerType());
			entity.setNotDatabaseType(t.getNotDatabaseType());
			entity.setNotFunctionType(t.getNotFunctionType());
			entity.setIops(t.getIops());
			entity.setLatency(t.getLatency());
			entity.setMinimal(t.getMinimal());
			entity.setMaximal(t.getMaximal());
			entity.setOptimized(t.getOptimized());
			entity.setThroughput(t.getThroughput());
			entity.setAvailability(t.getAvailability());
			entity.setDurability9(t.getDurability9());
			entity.setEngine(t.getEngine());
			entity.setNetwork(t.getNetwork());
			stRepository.save(entity);
		});
	}
}
