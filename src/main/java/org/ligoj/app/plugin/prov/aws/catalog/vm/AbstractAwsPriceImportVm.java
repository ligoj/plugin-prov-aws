/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.prov.aws.catalog.AbstractAwsImport;
import org.ligoj.app.plugin.prov.aws.catalog.AbstractLocalContext;
import org.ligoj.app.plugin.prov.aws.catalog.AwsPriceRegion;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AbstractCsvForBeanEc2;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.SavingsPlanPrice;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.SavingsPlanPrice.SavingsPlanProduct;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.SavingsPlanPrice.SavingsPlanRate;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.SavingsPlanPrice.SavingsPlanTerm;
import org.ligoj.app.plugin.prov.catalog.ImportCatalog;
import org.ligoj.app.plugin.prov.model.AbstractInstanceType;
import org.ligoj.app.plugin.prov.model.AbstractQuoteVm;
import org.ligoj.app.plugin.prov.model.AbstractTermPriceVm;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.ligoj.app.plugin.prov.model.Rate;
import org.ligoj.bootstrap.core.curl.CurlProcessor;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

/**
 * The compute part of AWS catalog import.
 *
 * @param <T> The instance type's type.
 * @param <P> The price's type.
 * @param <C> The JSON price type.
 * @param <Q> The quote type.
 * @param <X> The context type.
 * @param <R> The CSV bean reader type.
 */
@Slf4j
public abstract class AbstractAwsPriceImportVm<T extends AbstractInstanceType, P extends AbstractTermPriceVm<T>, C extends AbstractAwsVmPrice, Q extends AbstractQuoteVm<P>, X extends AbstractLocalContext<T, P, C, Q>, R extends AbstractCsvForBeanEc2<C>>
		extends AbstractAwsImport implements ImportCatalog<UpdateContext> {

	/**
	 * Spot term code.
	 */
	protected static final String TERM_SPOT_CODE = "spot";

	/**
	 * Spot term name.
	 */
	protected static final String TERM_SPOT = "Spot";

	/**
	 * Compute Savings Plan name
	 */
	private static final String TERM_COMPUTE_SP = "Compute Savings Plan";

	/**
	 * EC2 Savings Plan name
	 */
	private static final String TERM_EC2_SP = "EC2 Savings Plan";

	/**
	 * Reserved term types.
	 */
	public static final String TERM_RESERVED = "Reserved";

	/**
	 * On demand term types.
	 */
	public static final String TERM_ON_DEMAND = "OnDemand";

	private static final Pattern LEASING_TIME = Pattern.compile("(\\d)\\s*yr");

	/**
	 * Return <code>true</code> when this price corresponds to a reserved price with up-front part.
	 *
	 * @param csv The current CSV price entry.
	 * @return <code>true</code> when this price corresponds to a reserved price with up-front part.
	 */
	protected boolean hasPartialCost(final C csv) {
		return TERM_RESERVED.equals(csv.getTermType()) && !csv.getPurchaseOption().startsWith("No ");
	}

	/**
	 * Handle partial up-front prices split into multiple price entries.
	 *
	 * @param context The regional update context.
	 * @param csv     The current CSV price entry.
	 * @return <code>true</code> when the current CSV entry is associated to a RI with up-front.
	 */
	protected boolean handlePartialCost(final X context, final C csv) {
		if (!hasPartialCost(csv)) {
			return false;
		}
		// Up-front ALL/PARTIAL
		final var partialCost = context.getPartialCost();
		final var upFrontCode = toUpFrontCode(csv);
		if (partialCost.containsKey(upFrontCode)) {
			handlePartialCost(context, csv, partialCost.get(upFrontCode));

			// The price is completed, cleanup
			partialCost.remove(upFrontCode);
		} else {
			partialCost.put(upFrontCode, csv);
		}
		return true;
	}

	/**
	 * Handle partial up-front prices split into multiple price entries.
	 *
	 * @param context    The regional update context.
	 * @param csv        The current CSV price entry.
	 * @param other      The previous CSV price entry.
	 * @param repository The repository managing the price entity.
	 */
	private void handlePartialCost(final X context, final C one, final C two) {
		// Up-front part
		final C quantity = "Quantity".equals(one.getPriceUnit()) ? one : two;
		final C hourly = "Quantity".equals(one.getPriceUnit()) ? two : one;

		// Price code is based on the hourly term code
		final var price = newPrice(context, hourly);

		// Round the computed hourly cost and save as needed
		final var initCost = quantity.getPricePerUnit() / price.getTerm().getPeriod();
		final var cost = hourly.getPricePerUnit() * context.getHoursMonth() + initCost;

		saveAsNeeded(context, price, price.getCost(), cost, (cR, c) -> {
			price.setInitialCost(quantity.getPricePerUnit());
			price.setCost(cR);
			price.setCostPeriod(round3Decimals(price.getInitialCost()
					+ hourly.getPricePerUnit() * price.getTerm().getPeriod() * context.getHoursMonth()));
		}, context.getPRepository()::save);
	}

	/**
	 * Install or update a price.
	 *
	 * @param context The regional update context.
	 * @param csv     The current CSV entry.
	 * @return The new updated price entity.
	 */
	protected P newPrice(final X context, final C csv) {
		return syncAdd(context.getLocals(), csv.getRateCode(), context::newPrice,
				price -> copyAsNeeded(context, price, p -> copy(context, csv, price, installInstanceType(context, csv),
						installInstancePriceTerm(context, csv))));
	}

	/**
	 * Copy a CSV price entry to a price entity.
	 *
	 * @param csv The current CSV entry.
	 * @param p   The target price entity.
	 */
	protected abstract void copy(final C csv, final P p);

	/**
	 * Copy a CSV price entry to a type entity.
	 *
	 * @param csv The current CSV entry.
	 * @param p   The target type entity.
	 */
	protected void copy(final C csv, final T t) {
		t.setCpu(csv.getCpu());
		t.setGpu(csv.getGpu());
		t.setAutoScale(true);
		t.setName(t.getCode());
		t.setConstant(!t.getName().startsWith("t") && !t.getName().startsWith("db.t"));
		t.setPhysical(t.getName().contains("metal"));
		t.setProcessor(StringUtils
				.trimToNull(RegExUtils.removeAll(csv.getPhysicalProcessor(), "(Variable|\\s*Family|\\([^)]*\\))")));
		t.setDescription(ArrayUtils.toString(
				ArrayUtils.removeAllOccurrences(new String[] { csv.getStorage(), csv.getNetworkPerformance() }, null)));

		// Convert GiB to MiB, and rounded
		final var memoryStr = StringUtils.removeEndIgnoreCase(csv.getMemory(), " GiB").replace(",", "");
		t.setRam((int) Math.round(Double.parseDouble(memoryStr) * 1024d));

		// Rating
		t.setCpuRate(getRate("cpu", csv));
		t.setRamRate(getRate("ram", csv));
		t.setNetworkRate(getRate("network", csv, csv.getNetworkPerformance()));
		t.setStorageRate(toStorage(csv));
	}

	/**
	 * Copy a CSV price entry to a price entity.
	 *
	 * @param context The regional update context.
	 * @param csv     The current CSV entry.
	 * @param p       The target price entity.
	 * @param type    The instance type.
	 * @param term    The price term.
	 */
	protected void copy(final X context, final C csv, final P p, final T type, final ProvInstancePriceTerm term) {
		copy(csv, p);
		p.setLocation(context.getRegion());
		p.setLicense(StringUtils.trimToNull(Objects.requireNonNullElse(csv.getLicenseModel(), "")
				.replace("No License required", "").replace("No license required", "").replace("License included", "")
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
	private String toUpFrontCode(final C csv) {
		return csv.getSku() + csv.getOfferTermCode();
	}

	/**
	 * Install a new EC2/RDS instance type. The previous entries will contains this type at the end of this operation.
	 *
	 * @param context The regional update context.
	 * @param csv     The current CSV entry.
	 * @return Either the previous entity, either a new one. Never <code>null</code>.
	 */
	protected final T installInstanceType(final X context, final C csv) {
		return syncAdd(context.getPreviousTypes(), csv.getInstanceType(), code -> {
			final var n = context.newType();
			n.setNode(context.getNode());
			n.setCode(code);
			return n;
		}, previous -> {
			final var type = context.getLocalTypes().computeIfAbsent(previous.getCode(),
					code -> ObjectUtils.defaultIfNull(context.getTRepository().findBy("code", code), previous));

			// Update the statistics only once
			return copyAsNeeded(context, type, t -> copy(csv, t), context.getTRepository());
		});
	}

	private Rate toStorage(final C csv) {
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
	private Rate getRate(final String type, final C csv) {
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
	protected Rate getRate(final String type, final C csv, final String name) {
		var rate = getRate(type, name);

		// Downgrade the rate for a previous generation
		if ("No".equals(csv.getCurrentGeneration())) {
			rate = Rate.values()[Math.max(0, rate.ordinal() - 1)];
		}
		return rate;
	}

	/**
	 * Remove SKU that were present in the context and not refresh with this update.
	 *
	 * @param context The regional update context.
	 */
	protected void purgePrices(final X context) {
		super.purgePrices(context, context.getLocals(), context.getPRepository(), context.getQRepository());
	}

	/**
	 * Build a new instance price term from the CSV line.
	 *
	 * @param context The regional update context.
	 * @param csv     The CSV price row.
	 * @return The updated {@link ProvInstancePriceTerm} corresponding to the CSV row.
	 */
	protected ProvInstancePriceTerm installInstancePriceTerm(final X context, final C csv) {
		final var term = getLocalTerm(context, csv.getOfferTermCode());

		// Update the properties only once
		return copyAsNeeded(context, term, t -> {

			// Build the name from the leasing, purchase option and offering class
			final var name = StringUtils.trimToNull(RegExUtils.removeAll(
					RegExUtils.replaceAll(csv.getPurchaseOption(), "([a-z])Upfront", "$1 Upfront"), "No\\s*Upfront"));
			t.setName(Arrays
					.stream(new String[] { csv.getTermType(),
							StringUtils.replace(csv.getLeaseContractLength(), " ", ""), name,
							StringUtils.trimToNull(StringUtils.remove(csv.getOfferingClass(), "standard")) })
					.filter(Objects::nonNull).collect(Collectors.joining(", ")));
			t.setReservation(StringUtils.containsIgnoreCase(term.getName(), "reserved")); // ODCR not yet managed of
			t.setConvertibleType(
					!term.getReservation() || StringUtils.containsIgnoreCase(term.getName(), "convertible"));

			// Only for OD term
			t.setConvertibleFamily(!t.getReservation());
			t.setConvertibleEngine(!t.getReservation());
			t.setConvertibleOs(!t.getReservation());
			t.setConvertibleLocation(!t.getReservation());

			// Handle leasing
			final var matcher = LEASING_TIME.matcher(StringUtils.defaultIfBlank(csv.getLeaseContractLength(), ""));
			if (matcher.find()) {
				// Convert years to months
				t.setPeriod(Integer.parseInt(matcher.group(1)) * 12d);
			}
			t.setInitialCost(t.getName().matches(".*(All|Partial)\\s*Upfront.*"));
		});
	}

	protected ProvInstancePriceTerm getLocalTerm(final X context, final String code) {
		return syncAdd(context.getPriceTerms(), code, k -> {
			final var newTerm = new ProvInstancePriceTerm();
			newTerm.setNode(context.getNode());
			newTerm.setCode(k);
			return newTerm;
		}, sharedTerm -> context.getLocalPriceTerms().computeIfAbsent(sharedTerm.getCode(),
				c -> ObjectUtils.defaultIfNull(iptRepository.findBy("code", c), sharedTerm)));
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
	 * Return <code>true</code> when the Savings Plan product is accepted.
	 * 
	 * @param product The product to accept.
	 * @return <code>true</code> when the Savings Plan product is accepted.
	 */
	protected boolean filterSPProduct(SavingsPlanProduct product) {
		return true;
	}

	/**
	 * Download and install Savings Plan prices from AWS endpoint.
	 */
	private void installSavingsPlan(final X context, final String api, final String serviceCode, final String endpoint,
			final Map<String, P> previousOd) {
		final var region = context.getRegion();
		final var odTermCode = getOnDemandCode(previousOd);
		if (odTermCode == null) {
			// No OD found for SP/region
			log.warn("AWS {} No OnDemand prices @{}, Savings Plan is ignored", api, region.getName());
			return;
		}

		final var oldCount = context.getLocals().size();
		try (var curl = new CurlProcessor()) {
			// Get the remote prices stream
			final var rawJson = curl.get(endpoint);
			final var sps = objectMapper.readValue(rawJson, SavingsPlanPrice.class);
			final var skus = sps.getProducts().stream().filter(this::filterSPProduct).map(SavingsPlanProduct::getSku)
					.collect(Collectors.toSet());
			final var skuErrors = sps.getTerms().getSavingsPlan().stream().filter(sp -> skus.contains(sp.getSku()))
					.flatMap(sp -> installSavingsPlanRates(context, serviceCode, newSavingsPlanTerm(context, sp),
							previousOd, odTermCode, sp.getRates()))
					.filter(Objects::nonNull).toList();
			if (!skuErrors.isEmpty()) {
				// At least one SKU as not been resolved
				log.warn("AWS {} Savings Plan import errors @{} with {} unresolved SKUs, first : {}", api,
						region.getName(), skuErrors.size(), skuErrors.get(0));
			}

			// Purge the SKUs
			purgePrices(context);
		} catch (final IOException | IllegalArgumentException use) {
			// Something goes wrong for this region, stop for this region
			log.warn("AWS {} Savings Plan import failed @{}", api, region.getName(), use);
		} finally {
			// Report
			log.info("AWS {} Savings Plan import finished @{}: {} prices ({})", api, region.getName(),
					context.getPrices().size(), String.format("%+d", context.getPrices().size() - oldCount));
			context.cleanup();
		}
	}

	/**
	 * Persist the savings plan prices for a specific term and region.
	 * 
	 * @param context     The current global context to handle lazy sub-entities creation.
	 * @param serviceCode The service code to filter.
	 * @param term        The current term.
	 * @param previousOd  The previous On Demand prices.
	 * @param odTermCode  The On Demand term code.
	 * @param rates       The SP rates to filter.
	 * @return A stream of filtered rates with a broken discounted rate reference.
	 */
	protected Stream<String> installSavingsPlanRates(final X context, final String serviceCode,
			final ProvInstancePriceTerm term, final Map<String, P> previousOd, final String odTermCode,
			final Collection<SavingsPlanRate> rates) {
		return rates.stream().filter(r -> serviceCode.equals(r.getDiscountedServiceCode()))
				.map(r -> installSavingsPlanPrice(context, term, r, previousOd, odTermCode));
	}

	/**
	 * Create or update the savings plan term and return it.
	 */
	private ProvInstancePriceTerm newSavingsPlanTerm(final X context, final SavingsPlanTerm sp) {
		final var term = getLocalTerm(context, sp.getSku());

		// Update the properties only once
		return copyAsNeeded(context, term, t -> {
			var name = sp.getDescription();
			final boolean computePlan;
			if (sp.getDescription().contains(TERM_COMPUTE_SP)) {
				// Sample: "3 year No Upfront Compute Savings Plan"
				// Sample: "1 year All Upfront Compute Savings Plan"
				name = RegExUtils.replaceAll(name, "(\\d+) year\\s+(.*)\\s+Compute Savings Plan",
						TERM_COMPUTE_SP + ", $1yr, $2");
				computePlan = true;
			} else {
				// Sample: "3 year Partial Upfront r5 EC2 Instance Savings Plan in eu-west-3"
				name = RegExUtils.replaceAll(name, "(\\d+) year (.*)\\s+(.+)\\s+EC2 Instance Savings Plan (.*)",
						TERM_EC2_SP + ", $1yr, $2, $3 $4");
				computePlan = false;

				// This term is only available for a specific region
				term.setLocation(context.getRegion());
			}

			term.setName(name);
			term.setReservation(false);
			term.setConvertibleLocation(computePlan);
			term.setConvertibleFamily(computePlan);
			term.setConvertibleType(true);
			term.setConvertibleOs(true);
			term.setConvertibleEngine(false);
			term.setDescription(sp.getDescription());
			term.setPeriod(Math.round(sp.getLeaseContractLength().getDuration() * 12d));
			term.setInitialCost(name.matches(".*(All|Partial) Upfront.*"));
		});
	}

	/**
	 * Install the SP prices related to a term. The instance price type is reused from the discounted OnDemand price,
	 * and must exists.
	 * 
	 * @param context    The regional update context.
	 * @param term       The current term.
	 * @param jsonPrice  The SP price to persist.
	 * @param previousOd The previous On Demand prices.
	 * @param odTermCode The On Demand term code.
	 * @return A stream of filtered rates with a broken discounted rate reference.
	 */
	protected String installSavingsPlanPrice(final X context, final ProvInstancePriceTerm term,
			final SavingsPlanRate jsonPrice, final Map<String, P> previousOd, final String odTermCode) {
		if (jsonPrice.getDiscountedUsageType().contains("Unused")) {
			// Ignore this usage
			return null;
		}

		// Get the related OD Price
		final var odPrice = previousOd.get(jsonPrice.getDiscountedSku() + odTermCode);
		if (odPrice == null) {
			// Return discounted SKU not found
			return jsonPrice.getDiscountedSku();
		}

		// Add this code to the existing SKU codes
		final var price = newSavingPlanPrice(context, odPrice, jsonPrice, term);
		final var cost = jsonPrice.getDiscountedRate().getPrice() * context.getHoursMonth();

		// Save the price as needed with up-front computation if any
		saveAsNeeded(context, price, price.getCost(), cost, (cR, c) -> {
			price.setCost(cR);
			saveInitialCost(price, c);
		}, context.getPRepository()::save);

		// No error
		return null;
	}

	/**
	 * Save initial cost depending on the term of given price.
	 * 
	 * @param price The target price to complete.
	 * @param cost  The actual monthly based cost.
	 */
	protected void saveInitialCost(final P price, final double cost) {
		final var costPeriod = cost * Math.max(1, price.getTerm().getPeriod());
		price.setCostPeriod(round3Decimals(costPeriod));

		if (!price.getTerm().getInitialCost().booleanValue()) {
			// No up-front
			price.setInitialCost(0d);
		} else if (price.getTerm().getName().contains("Partial")) {
			// Partial up-front: at least 50% of the full period
			price.setInitialCost(round3Decimals(costPeriod * 0.5d));
		} else {
			// All up-front
			price.setInitialCost(price.getCostPeriod());
		}
	}

	/**
	 * Copy OnDemand attributes to Savings Plan price.
	 * 
	 * @param odPrice The OnDemand source price.
	 * @param spPrice The target savings plan price.
	 */
	protected void copySavingsPlan(final P odPrice, final P spPrice) {
		// Nothing to do by default
	}

	/**
	 * Install savings plan prices of target regions.
	 * 
	 * @param gContext    The current global context to handle lazy sub-entities creation.
	 * @param endpoint    The endpoint price URL.
	 * @param api         The current API name.
	 * @param serviceCode The current service code
	 * @param region      The current region.
	 * @param context     The regional update context.
	 */
	protected void installSavingsPlan(final UpdateContext gContext, final String endpoint, final String api,
			final String serviceCode, final ProvLocation region, final X context) {
		log.info("AWS {} Savings Plan import started @{}>{} ...", api, region.getName(), endpoint);
		final var spContext = newContext(gContext, region, TERM_EC2_SP, TERM_COMPUTE_SP);
		spContext.setLocalTypes(context.getLocalTypes());
		spContext.setRegion(context.getRegion());
		installSavingsPlan(spContext, api, serviceCode, endpoint, context.getLocals());
		spContext.cleanup();
	}

	/**
	 * Return a new local context.
	 * 
	 * @param context The current global context to handle lazy sub-entities creation.
	 * @param region  The target region.
	 * @param term1   The expected term name prefix alternative 1.
	 * @param term2   The expected term name prefix alternative 2.
	 * @return A new local context.
	 */
	protected abstract X newContext(final UpdateContext gContext, final ProvLocation region, final String term1,
			final String term2);

	/**
	 * Return the rate term code without SKU part of the current On Demand session.
	 *
	 * @param previousOd The previous On Demand prices.
	 * @return The rate term code without SKU part of the current On Demand session and looks like:
	 *         <code>.JRTCKXETXF</code>. <code>null</code> when not found.
	 */
	protected String getOnDemandCode(final Map<String, P> previousOd) {
		return previousOd.values().stream()
				.filter(c -> c.getCode().indexOf('.') != -1 && TERM_ON_DEMAND.equals(c.getTerm().getName())).findFirst()
				.map(P::getCode).map(c -> StringUtils.substringBefore(c.substring(c.indexOf('.')), "|")).orElse(null);
	}

	/**
	 * Create as needed a new {@link ProvInstancePriceTerm} for Spot.
	 */
	protected ProvInstancePriceTerm newSpotInstanceTerm(final X context) {
		final var term = getLocalTerm(context, TERM_SPOT_CODE);

		// Update the properties only once
		return copyAsNeeded(context, term, t -> {
			t.setName(TERM_SPOT);
			t.setVariable(true);
			t.setEphemeral(true);
			t.setCode(TERM_SPOT_CODE);
			t.setConvertibleEngine(true);
			t.setConvertibleOs(true);
			t.setConvertibleType(true);
			t.setConvertibleFamily(true);
			t.setConvertibleLocation(true);
			t.setReservation(false);
			t.setPeriod(0);
		});
	}

	/**
	 * Install or update a EC2 price
	 */
	protected P newSavingPlanPrice(final X context, final P odPrice, final SavingsPlanRate jsonPrice,
			final ProvInstancePriceTerm term) {
		final var type = odPrice.getType();
		return syncAdd(context.getLocals(), jsonPrice.getRateCode(), context::newPrice,
				price -> copyAsNeeded(context, price, p -> {
					p.setLocation(context.getRegion());
					p.setType(type);
					p.setTerm(term);
					p.setPeriod(term.getPeriod());
					newSavingPlanPrice(odPrice, p);
				}));
	}

	/**
	 * Install or update a EC2 price
	 */
	protected void newSavingPlanPrice(final P odPrice, final P p) {
		p.setLicense(odPrice.getLicense());
		copySavingsPlan(odPrice, p);
	}

	/**
	 * Return a CSV price reader instance.
	 *
	 * @param reader The input stream reader.
	 * @return A CSV price reader instance
	 * @throws IOException When the content cannot be read.
	 */
	protected abstract R newReader(BufferedReader reader) throws IOException;

	/**
	 * Install all prices related to the current service.
	 * 
	 * @param gContext    The current global context.
	 * @param api         The current API name.
	 * @param serviceCode The current service code
	 * @param term1       The expected term name prefix alternative 1.
	 * @param term2       The expected term name prefix alternative 2.
	 * @throws IOException When indexes cannot be downloaded.
	 */
	protected void installPrices(final UpdateContext gContext, final String api, final String serviceCode,
			final String term1, final String term2) throws IOException {
		nextStep(gContext, api, null, 0);
		log.info("AWS {} started ...", api);
		// Get the remote prices stream
		final var regions = getRegionalPrices(gContext, api, serviceCode);
		final var spRegions = getRegionalSPPrices(gContext, api, serviceCode);
		nextStep(gContext, api, null, 1);
		newStream(regions.values()).forEach(r -> newProxy().installRegionalPrices(gContext, r, api, serviceCode,
				spRegions.get(r.getRegionCode()), term1, term2));
	}

	/**
	 * Create a new transactional (READ_UNCOMMITTED) process for OnDemand/SPE prices in a specific region.
	 *
	 * @param gContext    The current global context.
	 * @param pRegion     The region configuration with price URLs.
	 * @param api         The current API name.
	 * @param serviceCode The current service code
	 * @param term1       The expected term name prefix alternative 1.
	 * @param term2       The expected term name prefix alternative 2.
	 */
	@Transactional(propagation = Propagation.SUPPORTS, isolation = Isolation.READ_UNCOMMITTED)
	public void installRegionalPrices(final UpdateContext gContext, final AwsPriceRegion pRegion, final String api,
			final String serviceCode, final AwsPriceRegion spRegion, final String term1, final String term2) {
		final var regionCode = pRegion.getRegionCode();
		final var endpoint = getCsvUrl(gContext, pRegion.getUrl());
		log.info("AWS {} OnDemand/Reserved import started for @{}>{} ...", api, regionCode, endpoint);
		nextStep(gContext, api, regionCode, 0);

		var region = locationRepository.findByName(gContext.getNode().getId(), regionCode);
		if (region == null) {
			region = installRegion(gContext, regionCode);
		}

		// Track the created instance to cache partial costs
		final var context = newContext(gContext, region, term1, term2);
		final var oldCount = context.getLocals().size();
		context.setPreviousStorage(spRepository.findByLocation(context.getNode().getId(), regionCode).stream()
				.collect(Collectors.toMap(ProvStoragePrice::getCode, Function.identity())));

		// Get the remote prices stream
		var succeed = false;
		try (var reader = new BufferedReader(new InputStreamReader(new URI(endpoint).toURL().openStream()))) {
			// Pipe to the CSV reader
			final var csvReader = newReader(reader);

			// Build the AWS instance prices from the CSV
			var csv = csvReader.read();
			while (csv != null) {

				// Persist this price
				if (isEnabled(context, csv)) {
					installPrice(context, csv);
				}

				// Read the next one
				csv = csvReader.read();
			}
			context.getPRepository().flush();

			// Purge the SKUs
			purgePrices(context);
			succeed = true;
		} catch (final IOException | URISyntaxException use) {
			// Something goes wrong for this region, stop for this region
			log.warn("AWS {} OnDemand/Reserved import failed @{}", api, region.getName(), use);
		} finally {
			// Report
			log.info("AWS {} OnDemand/Reserved import finished @{}: {} prices ({})", api, region.getName(),
					context.getPrices().size(), String.format("%+d", context.getPrices().size() - oldCount));
		}

		// Savings Plan part: only when OD succeed
		if (spRegion == null) {
			nextStep(context, api, null, 1);
		} else if (succeed) {
			nextStep(context, api, regionCode, 1);
			installSavingsPlan(gContext, context.getUrl(spRegion.getUrl()), api, serviceCode, region, context);
			nextStep(context, api + " (saving plan)", regionCode, 1);
		} else {
			nextStep(context, api, null, 2);
		}
		context.cleanup();
	}

	/**
	 * Install the instance type (if needed), the instance price term (if needed) and the on-demand price.
	 *
	 * @param context The regional update context.
	 * @param csv     The current CSV entry.
	 */
	protected abstract void installPrice(final X context, final C csv);

	/**
	 * Return the proxy of this class.
	 *
	 * @return The proxy of this class.
	 */
	public abstract AbstractAwsPriceImportVm<T, P, C, Q, X, R> newProxy();

	/**
	 * Is this record is enabled.
	 * 
	 * @param context The regional update context.
	 * @param csv     The current CSV entry.
	 * @return <code>true</code> when this record is accepted.
	 */
	protected boolean isEnabled(final X context, final C csv) {
		return isEnabledType(context, csv.getInstanceType());
	}

}
