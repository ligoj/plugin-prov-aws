/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.fargate;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Formatter;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.model.Node;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.aws.catalog.vm.AbstractAwsPriceImportVmOs;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.SavingsPlanPrice.SavingsPlanRate;
import org.ligoj.app.plugin.prov.catalog.AbstractUpdateContext;
import org.ligoj.app.plugin.prov.model.ProvContainerPrice;
import org.ligoj.app.plugin.prov.model.ProvContainerType;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvQuoteContainer;
import org.ligoj.app.plugin.prov.model.Rate;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.bootstrap.core.SpringUtils;
import org.ligoj.bootstrap.core.curl.CurlProcessor;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * The provisioning price service for AWS. Manage install or update of prices.
 * 
 * @see https://aws.amazon.com/fargate/pricing/
 */
@Slf4j
@Component
public class AwsPriceImportFargate extends
		AbstractAwsPriceImportVmOs<ProvContainerType, ProvContainerPrice, AwsFargatePrice, ProvQuoteContainer, LocalFargateContext, CsvForBeanFargate> {

	/**
	 * The EC2 reserved and on-demand price end-point, a CSV file, accepting the region code with {@link Formatter}
	 */
	private static final String FARGATE_PRICES = "https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonECS/current/%s/index.csv";

	/**
	 * The EC2 spot price end-point, a JSON file. Contains the prices for all regions.
	 */
	private static final String FARGATE_PRICES_SPOT = "https://dftu77xade0tc.cloudfront.net/fargate-spot-prices.json";

	/**
	 * Configuration key used for {@link #FARGATE_PRICES}
	 */
	public static final String CONF_URL_FARGATE_PRICES = String.format(CONF_URL_API_PRICES, "fargate");

	/**
	 * Configuration key used for {@link #EC2_PRICES_SPOT}
	 */
	public static final String CONF_URL_FARGATE_PRICES_SPOT = String.format(CONF_URL_API_PRICES, "fargate-spot");

	private static final Map<Double, double[]> CPU_TO_RAM = Map.of(
			// | .CPU | Memory Values |
			// |----- | --------------|
			// | 0.25 | {0.5,1,2}
			0.25d, new double[] { 0.5d, 1d, 2d },
			// | 0.50 | [1-4]
			0.5d, new double[] { 1d, 2d, 3d, 4d },
			// | 1.00 | [2-8]
			1d, DoubleStream.iterate(2, n -> n <= 8, n -> n + 1).toArray(),
			// | 2.00 | [4-16]
			2d, DoubleStream.iterate(4, n -> n <= 16, n -> n + 1).limit(16).toArray(),
			// | 4.00 | [8-30]
			4d, DoubleStream.iterate(8, n -> n <= 30, n -> n + 1).limit(30).toArray());

	@Override
	public void install(final UpdateContext context) throws IOException {
		importCatalogResource.nextStep(context.getNode().getId(), t -> t.setPhase("fargate"));
		installFargate(context, context.getNode(), "fargate",
				configuration.get(CONF_URL_FARGATE_PRICES, FARGATE_PRICES),
				configuration.get(CONF_URL_FARGATE_PRICES_SPOT, FARGATE_PRICES_SPOT));
	}

	private void installFargate(final UpdateContext context, final Node node, final String api, final String apiPrice,
			final String apiSpotPrice) throws IOException {

		// Install the Fargate (non spot) prices
		final var indexes = context.getSavingsPlanUrls();
		newStream(context.getRegions().values()).filter(region -> isEnabledRegion(context, region)).map(region -> {
			// Install OnDemand and reserved prices
			newProxy().installEC2Prices(context, region, api, apiPrice, indexes);
			return region;
		}).reduce((region1, region2) -> {
			nextStep(node, region2.getName(), 1);
			return region1;
		});

		// Install the SPOT Fargate prices
		installSpotPrices(context, apiSpotPrice);
	}

	@Override
	protected Stream<String> installSavingsPlanRates(final LocalFargateContext context,
			final ProvInstancePriceTerm term, final Map<String, ProvContainerPrice> previousOd, final String odCode,
			final Collection<SavingsPlanRate> rates) {
		final var fargatePrices = rates.stream().filter(r -> r.getDiscountedUsageType().contains("Fargate"))
				.collect(Collectors.toList());
		final var rateCpu = findSavingsPlanCost(fargatePrices, "vCPU-Hours:perCPU");
		final var rateRam = findSavingsPlanCost(fargatePrices, "GB-Hours");
		if (ObjectUtils.allNotNull(rateCpu, rateRam)) {
			installSavingsPlanPrices(context, term, rateCpu, rateRam, previousOd, odCode);
		}
		return Stream.empty();
	}

	private void installSavingsPlanPrices(final LocalFargateContext context, final ProvInstancePriceTerm term,
			final SavingsPlanRate rateCpu, final SavingsPlanRate rateRam,
			final Map<String, ProvContainerPrice> previousOd, final String odCode) {
		final var costCpu = rateCpu.getDiscountedRate().getPrice();
		final var costRam = rateRam.getDiscountedRate().getPrice();
		final var cpuRateCode = rateRam.getRateCode();
		CPU_TO_RAM.forEach((cpu, ramGbA) -> Arrays.stream(ramGbA).forEach(ram -> {
			rateCpu.getDiscountedRate().setPrice(costCpu * cpu + costRam * ram);
			rateCpu.setRateCode(toPriceCode(cpuRateCode, cpu, ram));
			super.installSavingsPlanPrices(context, term, rateCpu, previousOd, toPriceCode(odCode, cpu, ram));
		}));
	}

	@Override
	protected boolean handlePartialCost(final LocalFargateContext context, final AwsFargatePrice csv) {
		context.getPartialCost().put(csv.getUsageType().replaceFirst(".*Fargate-", ""), csv);
		if (context.getPartialCost().size() == 2) {
			// All parts are read
			final var csvRam = context.getPartialCost().get("GB-Hours");
			final var csvCpu = context.getPartialCost().get("vCPU-Hours:perCPU");
			final var costRam = csvRam.getPricePerUnit();
			final var costCpu = csvCpu.getPricePerUnit();
			context.getPrices().add(csvCpu.getSku());
			installFargatePrice(context, csvCpu, csvRam.getRateCode(), costRam, costCpu);
		}
		return true;
	}

	private String toPriceCode(final String rateCode, final double cpu, final double ram) {
		return rateCode + "|" + cpu + "|" + ram;
	}

	private ProvContainerPrice newPrice(final LocalFargateContext context, final AwsFargatePrice csv, final double cpu,
			final double ram) {
		final var code = toPriceCode(csv.getRateCode(), cpu, ram);
		final var price = context.getLocals().computeIfAbsent(code, context::newPrice);

		// Update the price in force mode
		return copyAsNeeded(context, price, p -> copy(context, csv, p, installInstanceType(context, cpu, ram),
				installInstancePriceTerm(context, csv)));
	}

	@Override
	protected void copy(final AwsFargatePrice csv, final ProvContainerPrice p) {
		p.setOs(VmOs.LINUX);
	}

	protected ProvContainerType installInstanceType(final LocalFargateContext context, final double cpu,
			final double ramGb) {
		final var sharedType = context.getPreviousTypes().computeIfAbsent("fargate-" + cpu + "-" + ramGb, code -> {
			final var t = context.newType();
			t.setNode(context.getNode());
			t.setCode(code);
			return t;
		});

		final var type = context.getLocalTypes().computeIfAbsent(sharedType.getCode(),
				code -> ObjectUtils.defaultIfNull(context.getTRepository().findBy("code", code), sharedType));

		// Update the statistics only once
		return copyAsNeeded(context, type, t -> {
			t.setAutoScale(true);
			t.setName(t.getCode());
			t.setConstant(true);
			t.setPhysical(false);
			t.setDescription("Fargate");
			t.setCpu(cpu);
			t.setRam(ramGb * 1024d);

			// Rating
			t.setCpuRate(Rate.MEDIUM);
			t.setRamRate(Rate.MEDIUM);
			t.setNetworkRate(Rate.MEDIUM);
			t.setStorageRate(Rate.MEDIUM);
		}, context.getTRepository());
	}

	@Override
	public AwsPriceImportFargate newProxy() {
		return SpringUtils.getBean(AwsPriceImportFargate.class);
	}

	@Override
	protected CsvForBeanFargate newReader(final BufferedReader reader) throws IOException {
		return new CsvForBeanFargate(reader);
	}

	@Override
	protected LocalFargateContext newContext(final UpdateContext gContext, final ProvLocation region,
			final String term1, final String term2) {
		return new LocalFargateContext(gContext, iptRepository, ctRepository, cpRepository, qcRepository, region, term1,
				term2);
	}

	/**
	 * Install AWS Spot prices from a JSON file.
	 * 
	 * @param context  The update context.
	 * @param endpoint The prices end-point JSON URL.
	 * @throws IOException When JSON content cannot be parsed.
	 */
	private void installSpotPrices(final UpdateContext gContext, final String endpoint) throws IOException {
		log.info("AWS Fargate Spot prices...");
		nextStep(gContext, "spot", 1);
		try (var curl = new CurlProcessor()) {
			// Get the remote prices stream
			final var rawJson = StringUtils.defaultString(curl.get(endpoint), "{\"prices\":[]}");
			final var prices = objectMapper.readValue(rawJson, SpotPrices.class);

			// Install the enabled regions as needed
			final var eRegions = prices.getPrices().stream().peek(p -> {
				final var regionName = p.getAttributes().get("aws:region");
				p.setRegionName(gContext.getMapSpotToNewRegion().getOrDefault(regionName, regionName));
			}).filter(p -> isEnabledRegion(gContext, p.getRegionName())).map(p -> {
				final var region = installRegion(gContext, p.getRegionName());
				p.setRegion(region);
				return region;
			}).collect(Collectors.toSet());

			// Install the prices for each region
			eRegions.forEach(r -> installSpotPrices(gContext, r,
					prices.getPrices().stream().filter(p -> r.equals(p.getRegion())).collect(Collectors.toSet())));
		} finally {
			// Report
			log.info("AWS Fargate Spot import finished");
		}
	}

	/**
	 * Find the first cost corresponding to the required unit.
	 */
	private SavingsPlanRate findSavingsPlanCost(final Collection<SavingsPlanRate> rates, final String usageType) {
		return rates.stream().filter(p -> p.getDiscountedUsageType().endsWith(usageType)).findFirst().orElse(null);
	}

	/**
	 * Find the first cost corresponding to the required unit.
	 */
	private double findSpotCost(final ProvLocation region, final Set<SpotPrice> prices, final String unit) {
		var cost = prices.stream().filter(p -> unit.equals(p.getUnit())).findFirst()
				.map(p -> Double.parseDouble(p.getPrice().get("USD"))).orElse(0d);
		if (cost == 0d) {
			log.warn("Missing {} cost for AWS Fargate@{}", unit, region.getName());
		}
		return cost;
	}

	public void installSpotPrices(final UpdateContext gContext, final ProvLocation region,
			final Set<SpotPrice> prices) {
		log.info("AWS Fargate Spot prices@{}...", region.getName());
		nextStep(gContext.getNode(), region.getName(), 1);
		// final var region = locationRepository.findByName(gContext.getNode().getId(), r.getRegion());
		final var costRam = findSpotCost(region, prices, "GB-Hours");
		final var costCpu = findSpotCost(region, prices, "vCPU-Hours");

		// Get previous prices for this location
		final var context = newContext(gContext, region, TERM_SPOT, TERM_SPOT);

		// INstall the spot term as needed
		final var term = newSpotInstanceTerm(context);

		final var csvCpu = new AwsFargatePrice();
		csvCpu.setTermType(term.getName());
		csvCpu.setOfferTermCode(term.getCode());
		csvCpu.setRateCode(term.getCode() + "." + region.getName() + ".fargate");
		installFargatePrice(context, csvCpu, null, costRam, costCpu);

		// Purge the SKUs
		purgePrices(context);
	}

	private void installFargatePrice(final LocalFargateContext context, final AwsFargatePrice csvCpu,
			final String ramRateCode, final double costRam, final double costCpu) {
		CPU_TO_RAM.forEach((cpu, ramGbA) -> {
			csvCpu.setCostCpu(costCpu);
			Arrays.stream(ramGbA).forEach(ram -> {
				csvCpu.setCostRam(costRam);
				final var price = newPrice(context, csvCpu, cpu, ram);
				final var cost = (costCpu * cpu + ram * costRam) * context.getHoursMonth();
				saveAsNeeded(context, price, cost, context.getPRepository());
			});
		});
	}

	@Override
	protected boolean isEnabledType(final AbstractUpdateContext context, final String type) {
		return isEnabledContainerType(context, type);
	}

	@Override
	protected boolean isEnabledOs(final AbstractUpdateContext context, final String os) {
		return true;
	}

}
