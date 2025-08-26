/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.fargate;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.Strings;
import org.ligoj.app.plugin.prov.aws.catalog.AwsPriceImportBase;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.aws.catalog.vm.AbstractAwsPriceImportVmOs;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.SavingsPlanPrice.SavingsPlanProduct;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.SavingsPlanPrice.SavingsPlanRate;
import org.ligoj.app.plugin.prov.catalog.AbstractUpdateContext;
import org.ligoj.app.plugin.prov.model.*;
import org.ligoj.bootstrap.core.SpringUtils;
import org.ligoj.bootstrap.core.curl.CurlProcessor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;

/**
 * The provisioning price service for AWS. Manage install or update of prices.
 *
 * @see <a href="https://aws.amazon.com/fargate/pricing/">Farget prices</a>
 */
@Slf4j
@Component
public class AwsPriceImportFargate extends
		AbstractAwsPriceImportVmOs<ProvContainerType, ProvContainerPrice, AwsFargatePrice, ProvQuoteContainer, LocalFargateContext, CsvForBeanFargate> {

	/**
	 * Service code.
	 */
	private static final String SERVICE_CODE = "AmazonECS";

	private static final String VCPU_HOURS = "vCPU-Hours";

	private static final String VCPU_HOURS_PER = VCPU_HOURS + ":perCPU";

	private static final String GB_HOURS = "GB-Hours";

	private static final double FREE_EPHEMERAL_STORAGE = 20d; // GiB

	/**
	 * The EC2 spot price end-point, a JSON file. Contains the prices for all regions.
	 */
	private static final String FARGATE_PRICES_SPOT = "https://dftu77xade0tc.cloudfront.net/fargate-spot-prices.json";

	/**
	 * The API name.
	 */
	private static final String API = "fargate";
	private static final String API_SPOT = API + "-spot";

	/**
	 * Configuration key used for {@link #API_SPOT}
	 */
	public static final String CONF_URL_FARGATE_PRICES_SPOT = String.format(AwsPriceImportBase.CONF_URL_TMP_PRICES,
			API_SPOT);

	private static final Map<Double, double[]> CPU_TO_RAM = Map.of(
			// See https://docs.aws.amazon.com/AmazonECS/latest/developerguide/AWS_Fargate.html
			// | .CPU | Memory Values |
			// |----- | --------------|
			// | 0.25 | {0.5,1,2}
			0.25d, new double[]{0.5d, 1d, 2d},
			// | 0.50 | [1-4]
			0.5d, new double[]{1d, 2d, 3d, 4d},
			// | 1.00 | [2-8]
			1d, DoubleStream.iterate(2, n -> n <= 8, n -> n + 1).toArray(),
			// | 2.00 | [4-16]
			2d, DoubleStream.iterate(4, n -> n <= 16, n -> n + 1).limit(16).toArray(),
			// | 4.00 | [8-30]
			4d, DoubleStream.iterate(8, n -> n <= 30, n -> n + 1).limit(30).toArray(),
			// | 8.00 | [16-60]
			8d, DoubleStream.iterate(16, n -> n <= 60, n -> n + 4).limit(60).toArray(),
			// | 16.00 | [16-60]
			16d, DoubleStream.iterate(32, n -> n <= 120, n -> n + 8).limit(120).toArray());

	@Override
	public void install(final UpdateContext context) throws IOException {
		nextStep(context, API, null, 0);
		// Pre-install types
		installFargateTypes(context);

		// Install OnDemand and savings plan prices
		installPrices(context, API, SERVICE_CODE, TERM_ON_DEMAND, null);

		// Install the SPOT Fargate prices
		nextStep(context, API_SPOT, null, 0);
		installSpotPrices(context, configuration.get(CONF_URL_FARGATE_PRICES_SPOT, FARGATE_PRICES_SPOT));
		nextStep(context, API_SPOT, null, 1);
	}

	@Override
	protected void installPrice(final LocalFargateContext context, final AwsFargatePrice csv) {
		final var partialCost = context.getPartialCost();
		final var usage = csv.getUsageType().replaceFirst(".*Fargate-", "");
		if (!usage.contains("Anywhere") && !usage.contains("ECS-EC2")) {
			// Only Ephemeral storage and Window/Linux/ARM OS/CPU/RAM compute are supported
			partialCost.put(usage, csv);
		}
	}

	@Override
	protected void purgePrices(final LocalFargateContext context) {
		final var partialCost = context.getPartialCost();
		// Compute prices
		partialCost.forEach((usage, csvCpu) -> {
			if (usage.endsWith("vCPU-Hours:perCPU")) {
				final var osPrefix = usage.replace("vCPU-Hours:perCPU", "");
				final var csvRam = partialCost.get(osPrefix + "GB-Hours");
				final var csvOsCpu = partialCost.get(osPrefix + "OS-Hours:perCPU");
				final var costRam = csvRam.getPricePerUnit();
				final var costCpu = csvCpu.getPricePerUnit() + (csvOsCpu == null ? 0 : csvOsCpu.getPricePerUnit());
				installFargatePrice(context, csvCpu, costRam, costCpu);
			}
		});
		context.getPRepository().flush();

		// Ephemeral storage (+20GiB) price
		final var csvStorage = partialCost.get("EphemeralStorage-GB-Hours");
		if (csvStorage != null) {
			final var type = context.getStorageTypes().get("fargate-ephemeral");
			final var price = context.getPreviousStorage()
					.computeIfAbsent(context.getRegion().getName() + "-fargate-ephemeral", c -> {
						final var p = new ProvStoragePrice();
						p.setCode(c);
						return p;
					});
			copyAsNeeded(context, price, p -> {
				p.setLocation(context.getRegion());
				p.setType(type);
			});
			saveAsNeededInternal(context, price, price.getCostGb(),
					csvStorage.getPricePerUnit() * context.getHoursMonth(), (cR, c) -> {
						price.setCostGb(cR);
						price.setCost(-round3Decimals(cR * FREE_EPHEMERAL_STORAGE));
					}, spRepository::save);

			stRepository.flush();
		}

		super.purgePrices(context, context.getLocals(), context.getPRepository(), context.getQRepository());
	}

	@Override
	protected boolean filterSPProduct(final SavingsPlanProduct product) {
		return "ComputeSavingsPlans".equals(product.getProductFamily());
	}

	@Override
	protected Stream<String> installSavingsPlanRates(final LocalFargateContext context, final String serviceCode,
			final ProvInstancePriceTerm term, final Map<String, ProvContainerPrice> previousOd, final String odCode,
			final Collection<SavingsPlanRate> rates) {
		final var rateCpu = findSavingsPlanCost(rates, VCPU_HOURS_PER);
		final var rateRam = findSavingsPlanCost(rates, GB_HOURS);
		if (ObjectUtils.allNotNull(rateCpu, rateRam)) {
			installSavingsPlanPrices(context, term, rateCpu, rateRam, previousOd, odCode);
		} else {
			log.warn("AWS {} No Savings Plan prices @{} term={}/{}, cpu={}, ram={}", API, context.getRegion().getName(),
					term.getCode(), term.getName(), rateCpu, rateRam);
			return Stream.empty();
		}
		return Stream.empty();
	}

	private void installSavingsPlanPrices(final LocalFargateContext context, final ProvInstancePriceTerm term,
			final SavingsPlanRate rateCpu, final SavingsPlanRate rateRam,
			final Map<String, ProvContainerPrice> previousOd, final String odCode) {
		final var costCpu = rateCpu.getDiscountedRate().getPrice();
		final var costRam = rateRam.getDiscountedRate().getPrice();
		final var cpuRateCode = rateCpu.getRateCode();
		CPU_TO_RAM.forEach((cpu, ramGbA) -> Arrays.stream(ramGbA).forEach(ram -> {
			rateCpu.getDiscountedRate().setPrice(costCpu * cpu + costRam * ram);
			rateCpu.setRateCode(toPriceCode(cpuRateCode, cpu, ram));
			super.installSavingsPlanPrice(context, term, rateCpu, previousOd, toPriceCode(odCode, cpu, ram));
		}));
	}

	/**
	 * Create a rate code based on the original rate code and resource configuration.
	 */
	private String toPriceCode(final String rateCode, final double cpu, final double ram) {
		return rateCode + "|" + cpu + "|" + ram;
	}

	/**
	 * Create a type code based on the architecture and resource configuration.
	 */
	private String toTypeCode(final double cpu, final double ramGb, final String processor) {
		return API + "-" + cpu + "-" + ramGb + (processor == null ? "" : "-arm");
	}

	private ProvContainerPrice newPrice(final LocalFargateContext context, final AwsFargatePrice csv, final double cpu,
			final double ram) {
		final var code = toPriceCode(csv.getRateCode(), cpu, ram);
		final var price = context.getLocals().computeIfAbsent(code, context::newPrice);
		return copyAsNeeded(context, price,
				p -> copy(context, csv, p,
						installInstanceType(context, cpu, ram,
								Strings.CI.contains(csv.getUsageType(), "arm") ? "arm" : null),
						installInstancePriceTerm(context, csv)));
	}

	/**
	 * Pre-install all Fargate container types.
	 */
	private void installFargateTypes(final UpdateContext context) {
		final LocalFargateContext localContext = newContext(context, new ProvLocation(), null, null);
		CPU_TO_RAM.forEach((cpu, ramGbA) -> Arrays.stream(ramGbA).forEach(ram -> {
			installInstanceType(localContext, cpu, ram, null);
			installInstanceType(localContext, cpu, ram, "arm");
		}));
	}

	private ProvContainerType installInstanceType(final LocalFargateContext context, final double cpu,
			final double ramGb, final String processor) {
		final var fakeCsv = new AwsFargatePrice();
		fakeCsv.setInstanceType(toTypeCode(cpu, ramGb, processor));
		fakeCsv.setCpu(cpu);
		fakeCsv.setRamGb(ramGb);
		fakeCsv.setPhysicalProcessor(processor == null ? "Intel" : processor);
		return installInstanceType(context, fakeCsv);
	}

	@Override
	protected void copy(final LocalFargateContext context, final AwsFargatePrice csv, final ProvContainerType t) {
		t.setAutoScale(true);
		t.setName(t.getCode());
		t.setPhysical(false);
		t.setDescription("Fargate");
		t.setCpu(csv.getCpu());
		t.setRam(csv.getRamGb() * 1024d);
		t.setProcessor("arm".equals(csv.getPhysicalProcessor()) ? "Graviton2" : csv.getPhysicalProcessor());

		// Rating
		t.setCpuRate(Rate.MEDIUM);
		t.setRamRate(Rate.MEDIUM);
		t.setNetworkRate(Rate.MEDIUM);
		t.setStorageRate(Rate.MEDIUM);
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
	 * @param gContext The global context.
	 * @param endpoint The prices end-point JSON URL.
	 * @throws IOException When JSON content cannot be parsed.
	 */
	private void installSpotPrices(final UpdateContext gContext, final String endpoint) throws IOException {
		log.info("AWS Fargate Spot prices...");
		try (var curl = new CurlProcessor()) {
			// Get the remote prices stream
			final var rawJson = Objects.toString(curl.get(endpoint), "{\"prices\":[]}");
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
		return rates.stream().filter(p -> p.getDiscountedUsageType().endsWith(usageType)
				&& SERVICE_CODE.equals(p.getDiscountedServiceCode())).findFirst().orElse(null);
	}

	/**
	 * Find the first cost corresponding to the required unit.
	 */
	private double findSpotCost(final ProvLocation region, final Set<SpotPrice> prices, final String unit) {
		var cost = (double) prices.stream().filter(p -> unit.equals(p.getUnit())).findFirst()
				.map(p -> Double.parseDouble(p.getPrice().get("USD"))).orElse(0d);
		if (cost == 0d) {
			log.warn("Missing {} cost for AWS Fargate@{}", unit, region.getName());
		}
		return cost;
	}

	private void installSpotPrices(final UpdateContext gContext, final ProvLocation region,
			final Set<SpotPrice> prices) {
		log.info("AWS Fargate Spot prices@{}...", region.getName());
		final var costRam = findSpotCost(region, prices, GB_HOURS);
		final var costCpu = findSpotCost(region, prices, VCPU_HOURS);

		// Get previous prices for this location
		final var context = newContext(gContext, region, TERM_SPOT, TERM_SPOT);

		// Install the spot term as needed
		final var term = newSpotInstanceTerm(context);

		final var csvCpu = new AwsFargatePrice();
		csvCpu.setTermType(term.getName());
		csvCpu.setOfferTermCode(term.getCode());
		csvCpu.setRateCode(term.getCode() + "." + region.getName() + "." + API);
		installFargatePrice(context, csvCpu, costRam, costCpu);

		// Purge the SKUs
		purgePrices(context);
	}

	private void installFargatePrice(final LocalFargateContext context, final AwsFargatePrice csvCpu,
			final double costRam, final double costCpu) {
		CPU_TO_RAM.forEach((cpu, ramGbA) -> {
			final var os = Strings.CI.contains(csvCpu.getUsageType(), "windows") ? VmOs.WINDOWS : VmOs.LINUX;
			if (os == VmOs.WINDOWS && (cpu < 1d || cpu >= 8)) {
				// This CPU configuration is not supported for Windows
				return;
			}
			csvCpu.setOs(os.name());
			Arrays.stream(ramGbA).forEach(ram -> {
				final var cost = (costCpu * cpu + ram * costRam) * context.getHoursMonth();
				final var price = newPrice(context, csvCpu, cpu, ram);
				saveAsNeeded(context, price, cost, context.getPRepository());
			});
		});
	}

	@Override
	protected boolean isEnabledType(final AbstractUpdateContext context, final String type) {
		return true;
	}

	@Override
	protected boolean isEnabledOs(final AbstractUpdateContext context, final String os) {
		return true;
	}

}
