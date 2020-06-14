/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.prov.aws.catalog.AbsractLocalContext;
import org.ligoj.app.plugin.prov.aws.catalog.AbstractAwsImport;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AbstractAwsEc2Price;
import org.ligoj.app.plugin.prov.catalog.ImportCatalog;
import org.ligoj.app.plugin.prov.model.AbstractInstanceType;
import org.ligoj.app.plugin.prov.model.AbstractQuoteVm;
import org.ligoj.app.plugin.prov.model.AbstractTermPrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.Rate;

/**
 * The compute part of AWS catalog import.
 * 
 * @param <T> The instance type's type.
 * @param <P> The price's type.
 * @param <C> The JSON price type.
 * @param <Q> The quote type.
 */
public abstract class AbstractAwsPriceImportVm<T extends AbstractInstanceType, P extends AbstractTermPrice<T>, C extends AbstractAwsEc2Price, Q extends AbstractQuoteVm<P>>
		extends AbstractAwsImport implements ImportCatalog<UpdateContext> {

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
	protected boolean hasUpFront(final C csv) {
		return TERM_RESERVED.equals(csv.getTermType()) && !csv.getPurchaseOption().startsWith("No ");
	}

	/**
	 * Handle partial up-front prices split into multiple price entries.
	 *
	 * @param context The current context to handle lazy sub-entities creation.
	 * @param csv     The current CSV price entry.
	 * @return <code>true</code> when the current CSV entry is associated to a RI with up-front.
	 */
	protected boolean handleUpFront(final AbsractLocalContext<T, P, C, Q> context, final C csv) {
		if (!hasUpFront(csv)) {
			return false;
		}
		// Up-front ALL/PARTIAL
		final var partialCost = context.getPartialCost();
		final var upFrontCode = toUpFrontCode(csv);
		if (partialCost.containsKey(upFrontCode)) {
			handleUpFront(context, csv, partialCost.get(upFrontCode));

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
	private void handleUpFront(final AbsractLocalContext<T, P, C, Q> context, final C csv, final C other) {
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
	 */
	protected P newPrice(final AbsractLocalContext<T, P, C, Q> context, final C csv) {
		final var type = installInstanceType(context, csv);
		final var term = installInstancePriceTerm(context, csv);
		final var price = context.getLocals().computeIfAbsent(csv.getRateCode(), context::newPrice);

		// Update the price in force mode
		return copyAsNeeded(context, price, p -> copy(context, csv, price, type, term));
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
	private void copy(final AbsractLocalContext<T, P, C, Q> context, final C csv, final P p, final T type,
			final ProvInstancePriceTerm term) {
		copy(csv, p);
		p.setLocation(context.getRegion());
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
	protected T installInstanceType(final AbsractLocalContext<T, P, C, Q> context, final C csv) {
		final var type = context.getPreviousTypes().computeIfAbsent(csv.getInstanceType(), k -> {
			final var t = context.newType();
			t.setNode(context.getNode());
			t.setCode(csv.getInstanceType());
			return t;
		});

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
	protected void purgePrices(final AbsractLocalContext<T, P, C, Q> context) {
		super.purgePrices(context, context.getLocals(), context.getPRepository(), context.getQRepository());
	}

	/**
	 * Build a new instance price type from the CSV line.
	 */
	protected ProvInstancePriceTerm installInstancePriceTerm(final AbsractLocalContext<T, P, C, Q> context,
			final C csv) {
		final var term = context.getPriceTerms().computeIfAbsent(csv.getOfferTermCode(), k -> {
			final var newTerm = new ProvInstancePriceTerm();
			newTerm.setNode(context.getNode());
			newTerm.setCode(k);
			return newTerm;
		});

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
		}, iptRepository);
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

}
