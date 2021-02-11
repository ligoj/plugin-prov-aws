/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.prov.aws.catalog.AbsractLocalContext;
import org.ligoj.app.plugin.prov.aws.catalog.AbstractAwsImport;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.SavingsPlanIndex;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.SavingsPlanPrice;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.SavingsPlanPrice.SavingsPlanRate;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.SavingsPlanPrice.SavingsPlanTerm;
import org.ligoj.app.plugin.prov.catalog.ImportCatalog;
import org.ligoj.app.plugin.prov.model.AbstractInstanceType;
import org.ligoj.app.plugin.prov.model.AbstractQuoteVm;
import org.ligoj.app.plugin.prov.model.AbstractTermPrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.Rate;
import org.ligoj.bootstrap.core.curl.CurlProcessor;

import lombok.extern.slf4j.Slf4j;

/**
 * The compute part of AWS catalog import.
 * 
 * @param <T> The instance type's type.
 * @param <P> The price's type.
 * @param <C> The JSON price type.
 * @param <Q> The quote type.
 */
@Slf4j
public abstract class AbstractAwsPriceImportVm<T extends AbstractInstanceType, P extends AbstractTermPrice<T>, C extends AbstractAwsVmPrice, Q extends AbstractQuoteVm<P>, X extends AbsractLocalContext<T, P, C, Q>>
		extends AbstractAwsImport implements ImportCatalog<UpdateContext> {

	protected static final String TERM_SPOT_CODE = "spot";
	protected static final String TERM_SPOT = "Spot";

	private static final String TERM_COMPUTE_SP = "Compute Savings Plan";
	private static final String TERM_EC2_SP = "EC2 Savings Plan";

	/**
	 * The EC2 Savings Plan prices end-point, a JSON file. Contains the URL for each regions.
	 */
	private static final String SAVINGS_PLAN = "https://pricing.us-east-1.amazonaws.com/savingsPlan/v1.0/aws/AWSComputeSavingsPlan/current/region_index.json";

	/**
	 * Configuration key used for AWS URL Savings Plan prices.
	 */
	public static final String CONF_URL_API_SAVINGS_PLAN = String.format(CONF_URL_API_PRICES, "savings-plan");

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
	 * @param context The current context to handle lazy sub-entities creation.
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
	 * @param context    The current context to handle lazy sub-entities creation.
	 * @param csv        The current CSV price entry.
	 * @param other      The previous CSV price entry.
	 * @param repository The repository managing the price entity.
	 */
	protected void handlePartialCost(final X context, final C csv, final C other) {
		final C quantity;
		final C hourly;
		if (csv.getPriceUnit().equals("Quantity")) {
			// Up-front part
			quantity = csv;
			hourly = other;
		} else {
			// Hourly part
			quantity = other;
			hourly = csv;
		}

		// Price code is based on the hourly term code
		final var price = newPrice(context, hourly);

		// Round the computed hourly cost and save as needed
		final var initCost = quantity.getPricePerUnit() / price.getTerm().getPeriod();
		final var cost = hourly.getPricePerUnit() * context.getHoursMonth() + initCost;
		context.getPrices().add(price.getCode());
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
	 * @param context The current context to handle lazy sub-entities creation.
	 * @param csv     The current CSV entry.
	 * @return The new updated price entity.
	 */
	protected P newPrice(final X context, final C csv) {
		final var price = context.getLocals().computeIfAbsent(csv.getRateCode(), context::newPrice);

		// Update the price in force mode
		return copyAsNeeded(context, price, p -> copy(context, csv, price, installInstanceType(context, csv),
				installInstancePriceTerm(context, csv)));
	}

	/**
	 * Copy a CSV price entry to a price entity.
	 * 
	 * @param csv The current CSV entry.
	 * @param p   The target price entity.
	 */
	protected abstract void copy(final C csv, final P p);

	/**
	 * Copy a CSV price entry to a price entity.
	 * 
	 * @param context The current context to handle lazy sub-entities creation.
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
	 * @param context The current context to handle lazy sub-entities creation.
	 * @param csv     The current CSV entry.
	 * @return Either the previous entity, either a new one. Never <code>null</code>.
	 */
	protected T installInstanceType(final X context, final C csv) {
		final var sharedType = context.getPreviousTypes().computeIfAbsent(csv.getInstanceType(), code -> {
			final var t = context.newType();
			t.setNode(context.getNode());
			t.setCode(code);
			return t;
		});

		final var type = context.getLocalTypes().computeIfAbsent(sharedType.getCode(),
				code -> ObjectUtils.defaultIfNull(context.getTRepository().findBy("code", code), sharedType));

		// Update the statistics only once
		return copyAsNeeded(context, type, t -> {
			t.setCpu(csv.getCpu());
			t.setAutoScale(true);
			t.setName(t.getCode());
			t.setConstant(!t.getName().startsWith("t") && !t.getName().startsWith("db.t"));
			t.setPhysical(t.getName().contains("metal"));
			t.setProcessor(StringUtils
					.trimToNull(RegExUtils.removeAll(csv.getPhysicalProcessor(), "(Variable|\\s*Family|\\([^)]*\\))")));
			t.setDescription(ArrayUtils.toString(ArrayUtils
					.removeAllOccurrences(new String[] { csv.getStorage(), csv.getNetworkPerformance() }, null)));

			// Convert GiB to MiB, and rounded
			final var memoryStr = StringUtils.removeEndIgnoreCase(csv.getMemory(), " GiB").replace(",", "");
			t.setRam((int) Math.round(Double.parseDouble(memoryStr) * 1024d));

			// Rating
			t.setCpuRate(getRate("cpu", csv));
			t.setRamRate(getRate("ram", csv));
			t.setNetworkRate(getRate("network", csv, csv.getNetworkPerformance()));
			t.setStorageRate(toStorage(csv));
		}, context.getTRepository());
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
	 * @param context The local update context.
	 */
	protected void purgePrices(final X context) {
		super.purgePrices(context, context.getLocals(), context.getPRepository(), context.getQRepository());
	}

	/**
	 * Build a new instance price term from the CSV line.
	 * 
	 * @param context The local update context.
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
		final var sharedTerm = context.getPriceTerms().computeIfAbsent(code, k -> {
			final var newTerm = new ProvInstancePriceTerm();
			newTerm.setNode(context.getNode());
			newTerm.setCode(k);
			return newTerm;
		});

		return context.getLocalPriceTerms().computeIfAbsent(sharedTerm.getCode(),
				c -> ObjectUtils.defaultIfNull(iptRepository.findBy("code", c), sharedTerm));
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
	 * Download and install Savings Plan prices from AWS endpoint.
	 */
	private void installSavingsPlan(final X context, final String api, final String endpoint,
			final Map<String, P> previousOd) {
		final var region = context.getRegion();
		// Install SavingPlan prices on this region
		if (endpoint == null) {
			// No end-point found for SP/region
			log.info("AWS {} No Savings Plan prices on region {}", api, region.getName());
			return;
		}
		final var odCode = getOnDemandCode(previousOd);
		if (odCode == null) {
			// No OD found for SP/region
			log.warn("AWS {} No OnDemand prices on region {}, Savings Plan is ignored", api, region.getName());
			return;
		}

		final var oldCount = context.getLocals().size();
		try (var curl = new CurlProcessor()) {
			// Get the remote prices stream
			final var rawJson = curl.get(endpoint);
			final var sps = objectMapper.readValue(rawJson, SavingsPlanPrice.class).getTerms().getSavingsPlan();
			final var skuErrors = sps.stream().flatMap(sp -> installSavingsPlanRates(context,
					newSavingsPlanTerm(context, sp), previousOd, odCode, sp.getRates())).filter(Objects::nonNull)
					.collect(Collectors.toList());
			if (!skuErrors.isEmpty()) {
				// At least one SKU as not been resolved
				log.warn("AWS {} Savings Plan import errors for region {} with {} unresolved SKUs, first : {}", api,
						region.getName(), skuErrors.size(), skuErrors.get(0));
			}

			// Purge the SKUs
			purgePrices(context);
		} catch (final IOException | IllegalArgumentException use) {
			// Something goes wrong for this region, stop for this region
			log.warn("AWS {} Savings Plan import failed for region {}", api, region.getName(), use);
		} finally {
			// Report
			log.info("AWS {} Savings Plan import finished for region {}: {} prices ({})", api, region.getName(),
					context.getPrices().size(), String.format("%+d", context.getPrices().size() - oldCount));
			context.cleanup();
		}
	}

	protected Stream<String> installSavingsPlanRates(final X context, final ProvInstancePriceTerm term,
			final Map<String, P> previousOd, final String odCode, final Collection<SavingsPlanRate> rates) {
		return rates.stream().map(r -> installSavingsPlanPrices(context, term, r, previousOd, odCode));
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
	 * Install the prices related to a term. The instance price type is reused from the discounted OnDemand price, and
	 * must exists.
	 */
	protected String installSavingsPlanPrices(final X context, final ProvInstancePriceTerm term,
			final SavingsPlanRate jsonPrice, final Map<String, P> previousOd, final String odCode2) {
		if (jsonPrice.getDiscountedUsageType().contains("Unused")) {
			// Ignore this usage
			return null;
		}

		// Get the related OD Price // K4EXFQ5YFQCP98EN.JRTCKXETXF.6YS6EN2CT7|4.0|8.0 //
		// HB5V2X8TXQUTDZBV.JRTCKXETXF.6YS6EN2CT7|4.0|8.0
		final var odPrice = previousOd.get(jsonPrice.getDiscountedSku() + odCode2);
		if (odPrice == null) {
			return jsonPrice.getDiscountedSku();
		}

		// Add this code to the existing SKU codes
		final var price = newSavingPlanPrice(context, odPrice, jsonPrice, term);
		final var cost = jsonPrice.getDiscountedRate().getPrice() * context.getHoursMonth();

		// Save the price as needed with up-front computation
		context.getPrices().add(price.getCode());
		saveAsNeeded(context, price, price.getCost(), cost, (cR, c) -> {
			price.setCost(cR);
			price.setCostPeriod(round3Decimals(c * Math.max(1, term.getPeriod())));

			if (!term.getInitialCost().booleanValue()) {
				// No up-front
				price.setInitialCost(0d);
			} else if (term.getName().contains("Partial")) {
				// Partial up-front
				price.setInitialCost(round3Decimals(price.getCostPeriod() * 0.5d));
			} else {
				// All up-front
				price.setInitialCost(price.getCostPeriod());
			}
		}, context.getPRepository()::save);

		// No error
		return null;
	}

	protected void copySavingsPlan(final P odPrice, final P p) {
		// Nothing to do by default
	}

	/**
	 * Return the savings plan URLs
	 */
	protected Map<String, String> getSavingsPlanUrls(final UpdateContext context) throws IOException {
		return getSavingsPlanUrls(context, configuration.get(CONF_URL_API_SAVINGS_PLAN, SAVINGS_PLAN));
	}

	/**
	 * Return the savings plan URLs
	 */
	private Map<String, String> getSavingsPlanUrls(final UpdateContext context, final String endpoint)
			throws IOException {
		final var result = context.getSavingsPlanUrls();
		log.info("AWS Savings plan indexes...");
		try (var curl = new CurlProcessor()) {
			// Get the remote prices stream
			final var rawJson = StringUtils.defaultString(curl.get(endpoint), "{\"regions\":[]}");
			final var baseParts = StringUtils.splitPreserveAllTokens(endpoint, "/");
			final var base = baseParts[0] + "//" + baseParts[2];

			// All regions are considered
			Arrays.stream(objectMapper.readValue(rawJson, SavingsPlanIndex.class).getRegions())
					.forEach(rConf -> result.put(rConf.getRegionCode(), base + rConf.getVersionUrl()));
			nextStep(context, null, 0);
		} finally {
			// Report
			log.info("AWS Savings plan indexes: {}", result.size());
			nextStep(context, null, 1);
		}
		return result;
	}

	protected void installSavingsPlan(final UpdateContext gContext, final Map<String, String> spIndexes,
			final String api, final String endpoint, final ProvLocation region, final X context) {
		final var spEndpoint = spIndexes.get(region.getName());
		log.info("AWS {} Savings Plan import started for region {}@{} ...", api, region, endpoint);
		final var spContext = newContext(gContext, region, TERM_EC2_SP, TERM_COMPUTE_SP);
		spContext.setLocalTypes(context.getLocalTypes());
		spContext.setRegion(context.getRegion());
		installSavingsPlan(spContext, api, spEndpoint, context.getLocals());
		spContext.cleanup();
	}

	protected abstract X newContext(final UpdateContext gContext, final ProvLocation region, final String term1,
			final String term2);

	/**
	 * Return the rate code without SKU part of the current On Demand session.
	 * 
	 * @param previousOd The previous On Demand prices.
	 * @return The rate code without SKU part of the current On Demand session and looks like: <code>.JRTCKXETXF</code>.
	 *         <code>null</code> when not found.
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
		final var price = context.getLocals().computeIfAbsent(jsonPrice.getRateCode(), context::newPrice);
		return copyAsNeeded(context, price, p -> {
			p.setLocation(context.getRegion());
			p.setType(type);
			p.setTerm(term);
			p.setPeriod(term.getPeriod());
			newSavingPlanPrice(odPrice, p);
		});
	}

	/**
	 * Install or update a EC2 price
	 */
	protected void newSavingPlanPrice(final P odPrice, final P p) {
		p.setLicense(odPrice.getLicense());
		copySavingsPlan(odPrice, p);
	}

}
