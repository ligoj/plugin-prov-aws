/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

import org.ligoj.app.plugin.prov.aws.catalog.AbsractLocalContext;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AbstractAwsVmOsPrice;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AbstractCsvForBeanEc2;
import org.ligoj.app.plugin.prov.model.AbstractInstanceType;
import org.ligoj.app.plugin.prov.model.AbstractQuoteVmOs;
import org.ligoj.app.plugin.prov.model.AbstractTermPriceVmOs;
import org.ligoj.app.plugin.prov.model.ProvLocation;
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
 * @param <R> The CSV bean reader type.
 * @param <X> The context type.
 */
@Slf4j
public abstract class AbstractAwsPriceImportVmOs<T extends AbstractInstanceType, P extends AbstractTermPriceVmOs<T>, C extends AbstractAwsVmOsPrice, Q extends AbstractQuoteVmOs<P>, X extends AbsractLocalContext<T, P, C, Q>, R extends AbstractCsvForBeanEc2<C>>
		extends AbstractAwsPriceImportVm<T, P, C, Q, X> {

	@Override
	protected void copy(final C csv, final P p) {
		p.setOs(toVmOs(csv.getOs()));
	}

	@Override
	protected void copySavingsPlan(final P odPrice, final P p) {
		super.copySavingsPlan(odPrice, p);
		p.setOs(odPrice.getOs());
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
	 * Create a new transactional (READ_UNCOMMITTED) process for OnDemand/SPE prices in a specific region.
	 * 
	 * @param gContext  The current global context.
	 * @param gRegion   The region.
	 * @param final     The API name
	 * @param apiPrice  The API price endpoint.
	 * @param spIndexes The Savings Plan indexes endpoint.
	 */
	@Transactional(propagation = Propagation.SUPPORTS, isolation = Isolation.READ_UNCOMMITTED)
	public void installEC2Prices(final UpdateContext gContext, final ProvLocation gRegion, final String api,
			final String apiPrice, final Map<String, String> spIndexes) {
		final var endpoint = apiPrice.replace("%s", gRegion.getName());
		log.info("AWS {} OnDemand/Reserved import started for region {}@{} ...", api, gRegion.getName(), endpoint);
		// Track the created instance to cache partial costs
		final var region = locationRepository.findOne(gRegion.getId());
		final var context = newContext(gContext, region, TERM_ON_DEMAND, TERM_RESERVED);
		final var oldCount = context.getLocals().size();

		// Get the remote prices stream
		var succeed = false;
		try (var reader = new BufferedReader(new InputStreamReader(new URI(endpoint).toURL().openStream()))) {
			// Pipe to the CSV reader
			final var csvReader = newReader(reader);

			// Build the AWS instance prices from the CSV
			var csv = csvReader.read();
			var first = true;
			while (csv != null) {
				if (first) {
					// Complete the region human name associated to the API one
					region.setDescription(csv.getLocation());
					first = false;
				}

				// Persist this price
				installEc2(context, csv);

				// Read the next one
				csv = csvReader.read();
			}
			context.getPRepository().flush();

			// Purge the SKUs
			purgePrices(context);
			succeed = true;
		} catch (final IOException | URISyntaxException use) {
			// Something goes wrong for this region, stop for this region
			log.warn("AWS {} OnDemand/Reserved import failed for region {}", api, region.getName(), use);
		} finally {
			// Report
			log.info("AWS {} OnDemand/Reserved import finished for region {}: {} prices ({})", api, region.getName(),
					context.getPrices().size(), String.format("%+d", context.getPrices().size() - oldCount));
		}

		// Savings Plan part: only when OD succeed
		if (succeed) {
			installSavingsPlan(gContext, spIndexes, api, endpoint, region, context);
		}
	}

	/**
	 * Install the install the instance type (if needed), the instance price type (if needed) and the price.
	 *
	 * @param context The update context.
	 * @param csv     The current CSV entry.
	 */
	protected void installEc2(final X context, final C csv) {
		// Filter OS and type
		if (!isEnabledType(context, csv.getInstanceType()) || !isEnabledOs(context, csv.getOs())) {
			return;
		}

		// Up-front, partial or not
		if (!handlePartialCost(context, csv)) {
			// No up-front, cost is fixed
			final var price = newPrice(context, csv);
			final var cost = csv.getPricePerUnit() * context.getHoursMonth();
			saveAsNeeded(context, price, cost, context.getPRepository());
		}
	}

	/**
	 * Return the proxy of this class.
	 * 
	 * @return The proxy of this class.
	 */
	public abstract AbstractAwsPriceImportVmOs<T, P, C, Q, X, R> newProxy();

}
