/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.prov.aws.catalog.AbstractAwsImport;
import org.ligoj.app.plugin.prov.aws.catalog.AwsPrices;
import org.ligoj.app.plugin.prov.aws.catalog.AwsRegionPrices;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AbstractAwsEc2Price;
import org.ligoj.app.plugin.prov.catalog.ImportCatalog;
import org.ligoj.app.plugin.prov.dao.BaseProvInstanceTypeRepository;
import org.ligoj.app.plugin.prov.dao.BaseProvQuoteResourceRepository;
import org.ligoj.app.plugin.prov.dao.BaseProvTermPriceRepository;
import org.ligoj.app.plugin.prov.model.AbstractInstanceType;
import org.ligoj.app.plugin.prov.model.AbstractTermPrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.Rate;
import org.ligoj.bootstrap.core.curl.CurlProcessor;
import org.springframework.data.repository.CrudRepository;

import com.hazelcast.util.function.BiConsumer;

import lombok.extern.slf4j.Slf4j;

/**
 * The compute part of AWS catalog import.
 */
@Slf4j
public abstract class AbstractAwsPriceImportVm extends AbstractAwsImport implements ImportCatalog<UpdateContext> {

	private static final Pattern LEASING_TIME = Pattern.compile("(\\d)\\s*yr");

	/**
	 * Pattern validating a a partial price entry.
	 */
	protected static final Pattern UPFRONT_MODE = Pattern.compile("(All|Partial)\\s*Upfront");

	/**
	 * Handle partial upfront prices split into multiple price entries.
	 * 
	 * @param <T>        The instance type's type.
	 * @param <P>        The price's type.
	 * @param <C>        The JSON price type.
	 *
	 * @param context    The current context to handle lazy sub-entities creation.
	 * @param price      The current price entity.
	 * @param csv        The current CSV price entry.
	 * @param other      The previous CSV price entry.
	 * @param repository The repository managing the price entity.
	 */
	protected <T extends AbstractInstanceType, P extends AbstractTermPrice<T>, C extends AbstractAwsEc2Price> void handleUpfront(
			final UpdateContext context, final P price, final C csv, final C other,
			final BaseProvTermPriceRepository<T, P> repository) {
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
		final var initCost = quantity.getPricePerUnit() / price.getTerm().getPeriod();
		final var cost = hourly.getPricePerUnit() * context.getHoursMonth() + initCost;
		saveAsNeeded(context, price, price.getCost(), cost, (cR, c) -> {
			price.setCost(cR);
			price.setInitialCost(quantity.getPricePerUnit());
			price.setCostPeriod(round3Decimals(price.getInitialCost()
					+ hourly.getPricePerUnit() * price.getTerm().getPeriod() * context.getHoursMonth()));
			price.setPeriod(price.getTerm().getPeriod());
		}, repository::save);
	}

	/**
	 * Copy a CSV price entry to a price entity.
	 * 
	 * @param <T>     The instance type type.
	 *
	 * @param context The current context to handle lazy sub-entities creation.
	 * @param csv     The current CSV entry.
	 * @param region  The current region.
	 * @param p       The target price entity.
	 * @param type    The instance type.
	 */
	protected <T extends AbstractInstanceType> void copy(final UpdateContext context, final AbstractAwsEc2Price csv,
			final ProvLocation region, final AbstractTermPrice<T> p, final T type, final ProvInstancePriceTerm term) {
		p.setLocation(region);
		p.setLicense(StringUtils.trimToNull(csv.getLicenseModel().replace("No License required", "")
				.replace("No license required", "").replace("License included", "")
				.replace("Bring your own license", ProvInstancePrice.LICENSE_BYOL)));
		p.setType(type);
		p.setTerm(term);
		p.setPeriod(term.getPeriod());
	}

	/**
	 * Build a price code from the CSV entry.
	 *
	 * @param csv The current CSV entry.
	 * @return A price code built from the CSV entry.
	 */
	protected String toCode(final AbstractAwsEc2Price csv) {
		return csv.getSku() + csv.getOfferTermCode();
	}

	/**
	 * Install a new EC2/RDS instance type. The previous entries will contains this type at the end of this operation.
	 * 
	 * @param <T>        The instance type type.
	 *
	 * @param context    The current context to handle lazy sub-entities creation.
	 * @param csv        The current CSV entry.
	 * @param previous   The previous installed types.
	 * @param newType    The constructor to use in case of this type was not already in the previous installed types.
	 * @param repository The repository handling the instance type entity, and used when a new type need to be created.
	 * @return Either the previous entity, either a new one. Never <code>null</code>.
	 */
	protected <T extends AbstractInstanceType> T installInstanceType(final UpdateContext context,
			final AbstractAwsEc2Price csv, final Map<String, T> previous, Supplier<T> newType,
			final BaseProvInstanceTypeRepository<T> repository) {
		final var type = previous.computeIfAbsent(csv.getInstanceType(), k -> {
			final var t = newType.get();
			t.setNode(context.getNode());
			t.setCode(csv.getInstanceType());
			return t;
		});

		// Update the statistics only once
		if (context.getInstanceTypesMerged().add(type.getCode())) {
			type.setCpu(csv.getCpu());
			type.setName(type.getCode());
			type.setConstant(!type.getName().startsWith("t") && !type.getName().startsWith("db.t"));
			type.setPhysical(type.getName().contains("metal"));
			type.setProcessor(StringUtils
					.trimToNull(RegExUtils.removeAll(csv.getPhysicalProcessor(), "(Variable|\\s*Family|\\([^)]*\\))")));
			type.setDescription(ArrayUtils.toString(ArrayUtils
					.removeAllOccurences(new String[] { csv.getStorage(), csv.getNetworkPerformance() }, null)));

			// Convert GiB to MiB, and rounded
			final var memoryStr = StringUtils.removeEndIgnoreCase(csv.getMemory(), " GiB").replace(",", "");
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
		var rate = getRate("storage", csv);
		if (!"EBS only".equals(csv.getStorage())) {
			// Upgrade for non EBS
			rate = Rate.values()[Math.min(rate.ordinal(), rate.ordinal() + 1)];
		}
		return rate;
	}

	/**
	 * Return the most precise rate from the AWS instance type definition.
	 *
	 * @param type The rating mapping name.
	 * @param csv  The CSV price row.
	 * @return The direct [class, generation, size] rate association, or the [class, generation] rate association, or
	 *         the [class] association, of the explicit "default association or {@link Rate#MEDIUM} value.
	 */
	private Rate getRate(final String type, final AbstractAwsEc2Price csv) {
		return getRate(type, csv, csv.getInstanceType());
	}

	/**
	 * Return the most precise rate from a name.
	 *
	 * @param type The rating mapping name.
	 * @param name The name to map.
	 * @param csv  The CSV price row.
	 * @return The direct [class, generation, size] rate association, or the [class, generation] rate association, or
	 *         the [class] association, of the explicit "default association or {@link Rate#MEDIUM} value. Previous
	 *         generations types are downgraded.
	 */
	protected Rate getRate(final String type, final AbstractAwsEc2Price csv, final String name) {
		var rate = getRate(type, name);

		// Downgrade the rate for a previous generation
		if ("No".equals(csv.getCurrentGeneration())) {
			rate = Rate.values()[Math.max(0, rate.ordinal() - 1)];
		}
		return rate;
	}

	/**
	 * Build a new instance price type from the CSV line.
	 */
	protected ProvInstancePriceTerm installInstancePriceTerm(final UpdateContext context,
			final AbstractAwsEc2Price csv) {
		final var term = context.getPriceTerms().computeIfAbsent(csv.getOfferTermCode(), k -> {
			final var newTerm = new ProvInstancePriceTerm();
			newTerm.setNode(context.getNode());
			newTerm.setCode(k);
			return newTerm;
		});

		// Update the properties only once
		if (context.getPriceTermsMerged().add(term.getCode())) {

			// Build the name from the leasing, purchase option and offering class
			final var name = StringUtils.trimToNull(RegExUtils.removeAll(
					RegExUtils.replaceAll(csv.getPurchaseOption(), "([a-z])Upfront", "$1 Upfront"), "No\\s*Upfront"));
			term.setName(Arrays
					.stream(new String[] { csv.getTermType(),
							StringUtils.replace(csv.getLeaseContractLength(), " ", ""), name,
							StringUtils.trimToNull(StringUtils.remove(csv.getOfferingClass(), "standard")) })
					.filter(Objects::nonNull).collect(Collectors.joining(", ")));
			term.setReservation(StringUtils.containsIgnoreCase(term.getName(), "reserved")); // ODCR not yet managed of
			term.setConvertibleType(
					!term.getReservation() || StringUtils.containsIgnoreCase(term.getName(), "convertible"));

			// Only for OD term
			term.setConvertibleFamily(!term.getReservation());
			term.setConvertibleEngine(!term.getReservation());
			term.setConvertibleOs(!term.getReservation());
			term.setConvertibleLocation(!term.getReservation());

			// Handle leasing
			final var matcher = LEASING_TIME.matcher(StringUtils.defaultIfBlank(csv.getLeaseContractLength(), ""));
			if (matcher.find()) {
				// Convert years to months
				term.setPeriod(Integer.parseInt(matcher.group(1)) * 12d);
			}
			iptRepository.save(term);
		}
		return term;
	}

	/**
	 * Install AWS prices from a JSON file.
	 * 
	 * @param <R>      The region prices wrapper type.
	 * @param <T>      The region price type.
	 *
	 * @param context  The update context.
	 * @param api      The API name, only for log.
	 * @param endpoint The prices end-point JSON URL.
	 * @param apiClass The mapping model from JSON at region level.
	 * @param mapper   The mapping function from JSON at region level to JPA entity.
	 * @throws IOException When JSON content cannot be parsed.
	 */
	protected <R extends AwsRegionPrices, T extends AwsPrices<R>> void installJsonPrices(final UpdateContext context,
			final String api, final String endpoint, final Class<T> apiClass, final BiConsumer<R, ProvLocation> mapper)
			throws IOException {
		log.info("AWS {} prices...", api);
		try (var curl = new CurlProcessor()) {
			// Get the remote prices stream
			final var rawJson = StringUtils.defaultString(curl.get(endpoint), "any({\"config\":{\"regions\":[]}});");

			// All regions are considered
			final var configIndex = rawJson.indexOf('{');
			final var configCloseIndex = rawJson.lastIndexOf('}');
			final var prices = objectMapper.readValue(rawJson.substring(configIndex, configCloseIndex + 1), apiClass);

			// Install the enabled region as needed
			final var eRegions = prices.getConfig().getRegions().stream()
					.peek(r -> r.setRegion(context.getMapSpotToNewRegion().getOrDefault(r.getRegion(), r.getRegion())))
					.filter(r -> isEnabledRegion(context, r)).collect(Collectors.toList());
			eRegions.forEach(r -> installRegion(context, r.getRegion()));
			nextStep(context, null, 1);

			// Install the prices for each region
			newStream(eRegions).forEach(r -> mapper.accept(r, context.getRegions().get(r.getRegion())));
		} finally {
			// Report
			log.info("AWS {} import finished", api);
			nextStep(context, null, 1);
		}
	}

	/**
	 * Indicate the given region is enabled.
	 *
	 * @param context The update context.
	 * @param region  The region API name to test.
	 * @return <code>true</code> when the configuration enable the given region.
	 */
	protected boolean isEnabledRegion(final UpdateContext context, final AwsRegionPrices region) {
		return isEnabledRegion(context, region.getRegion());
	}

	/**
	 * Read the network rate mapping. File containing the mapping from the AWS network rate to the normalized
	 * application rating.
	 *
	 * @see <a href="https://calculator.s3.amazonaws.com/index.html">calculator</a>
	 * @see <a href="https://aws.amazon.com/ec2/instance-types/">instance-types</a>
	 *
	 * @throws IOException When the JSON mapping file cannot be read.
	 */
	@PostConstruct
	public void initRate() throws IOException {
		initRate("storage");
		initRate("cpu");
		initRate("ram");
		initRate("network");
	}

	/**
	 * Remove SKU that were present in the context and not refresh with this update.
	 * 
	 * @param context      The update context.
	 * @param localContext The local update context.
	 * @param previous     The whole price context in the database. Some of them are no more available in the new
	 *                     catalog.
	 * @param pRepository  The price repository used to clean the related price.
	 * @param qRepository  The quote repository to check for unused price.
	 * @param <T>          The instance type.
	 * @param <P>          The price type.
	 */
	protected <T extends AbstractInstanceType, P extends AbstractTermPrice<T>> void purgeSku(
			final UpdateContext context, final UpdateContext localContext, Map<String, P> previous,
			final CrudRepository<P, Integer> pRepository, final BaseProvQuoteResourceRepository<?> qRepository) {
		final var purgeCodes = new HashSet<>(previous.keySet());
		purgeCodes.removeAll(localContext.getActualCodes());
		if (!purgeCodes.isEmpty()) {
			final var purge = previous.size();
			purgeCodes.removeAll(qRepository.finUsedPrices(context.getNode().getId()));
			log.info("Purge {} unused of not refresh {} prices ...", purgeCodes.size(), purge);
			purgeCodes.stream().map(previous::get).forEach(pRepository::delete);

			// Remove the purged from the context
			previous.keySet().removeAll(purgeCodes);
			log.info("Code purged");
		}
	}
}
