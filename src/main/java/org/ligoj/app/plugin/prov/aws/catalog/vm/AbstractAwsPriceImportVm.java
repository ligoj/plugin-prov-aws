/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.prov.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.prov.aws.catalog.AbstractAwsImport;
import org.ligoj.app.plugin.prov.aws.catalog.AwsPrices;
import org.ligoj.app.plugin.prov.aws.catalog.AwsRegionPrices;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AbstractAwsEc2Price;
import org.ligoj.app.plugin.prov.dao.BaseProvInstanceTypeRepository;
import org.ligoj.app.plugin.prov.dao.BaseProvTermPriceRepository;
import org.ligoj.app.plugin.prov.model.AbstractInstanceType;
import org.ligoj.app.plugin.prov.model.AbstractTermPrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.Rate;
import org.ligoj.bootstrap.core.curl.CurlProcessor;

import lombok.extern.slf4j.Slf4j;

/**
 * The compute part of AWS catalog import.
 */
@Slf4j
public abstract class AbstractAwsPriceImportVm extends AbstractAwsImport {

	private static final Pattern LEASING_TIME = Pattern.compile("(\\d)\\s*yr");
	protected static final Pattern UPFRONT_MODE = Pattern.compile("(All|Partial)\\s*Upfront");

	/**
	 * Configuration key used for enabled instance type pattern names. When value is <code>null</code>, no restriction.
	 */
	public static final String CONF_ITYPE = ProvAwsPluginResource.KEY + ":instance-type";

	protected <T extends AbstractInstanceType, P extends AbstractTermPrice<T>, C extends AbstractAwsEc2Price> void handleUpfront(
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

	protected <T extends AbstractInstanceType> void copy(final UpdateContext context, final AbstractAwsEc2Price csv,
			final ProvLocation region, final String code, final AbstractTermPrice<T> p, final T instance) {
		p.setLocation(region);
		p.setCode(code);
		p.setLicense(StringUtils.trimToNull(csv.getLicenseModel().replace("No License required", "")
				.replace("No license required", "").replace("Bring your own license", ProvInstancePrice.LICENSE_BYOL)));
		p.setType(instance);
		p.setTerm(context.getPriceTerms().computeIfAbsent(csv.getOfferTermCode(),
				k -> newInstancePriceTerm(context, csv)));
	}

	protected String toCode(final AbstractAwsEc2Price csv) {
		return csv.getSku() + csv.getOfferTermCode();
	}

	/**
	 * Install a new EC2/RDS instance type
	 */
	protected <T extends AbstractInstanceType> T installInstanceType(final UpdateContext context,
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
		Rate rate = getRate(type, name);

		// Downgrade the rate for a previous generation
		if ("No".equals(csv.getCurrentGeneration())) {
			rate = Rate.values()[Math.max(0, rate.ordinal() - 1)];
		}
		return rate;
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
	 * Indicate the given instance type is enabled.
	 *
	 * @param context
	 *            The update context.
	 * @param type
	 *            The instance type to test.
	 * @return <code>true</code> when the configuration enable the given instance type.
	 */
	protected boolean isEnabledType(final UpdateContext context, final String type) {
		return context.getValidInstanceType().matcher(type).matches();
	}

	protected <T extends AbstractInstanceType, P extends AbstractTermPrice<T>> P saveAsNeeded(final P entity,
			final double newCost, final Consumer<P> c) {
		return saveAsNeeded(entity, entity.getCost(), newCost, entity::setCost, c);
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
	protected <R extends AwsRegionPrices, T extends AwsPrices<R>> void installJsonPrices(final UpdateContext context,
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
					.peek(r -> r.setRegion(context.getMapSpotToNewRegion().getOrDefault(r.getRegion(), r.getRegion())))
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
