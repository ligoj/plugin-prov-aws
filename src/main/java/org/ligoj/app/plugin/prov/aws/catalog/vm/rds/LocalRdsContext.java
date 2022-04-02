/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.rds;

import org.ligoj.app.plugin.prov.aws.catalog.AbstractLocalContext;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.dao.ProvDatabasePriceRepository;
import org.ligoj.app.plugin.prov.dao.ProvDatabaseTypeRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceTermRepository;
import org.ligoj.app.plugin.prov.dao.ProvQuoteDatabaseRepository;
import org.ligoj.app.plugin.prov.model.ProvDatabasePrice;
import org.ligoj.app.plugin.prov.model.ProvDatabaseType;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvQuoteDatabase;

/**
 * Context used to perform catalog update.
 */
public class LocalRdsContext
		extends AbstractLocalContext<ProvDatabaseType, ProvDatabasePrice, AwsRdsPrice, ProvQuoteDatabase> {

	/**
	 * Context from the parent.
	 *
	 * @param parent       The parent context.
	 * @param ptRepository The term repository.
	 * @param tRepository  The type repository.
	 * @param pRepository  The price repository.
	 * @param qRepository  The quote repository.
	 * @param region       The current region.
	 * @param term1        The expected term name prefix alternative 1.
	 * @param term2        The expected term name prefix alternative 2.
	 */
	public LocalRdsContext(final UpdateContext parent, final ProvInstancePriceTermRepository ptRepository,
			final ProvDatabaseTypeRepository tRepository, final ProvDatabasePriceRepository pRepository,
			final ProvQuoteDatabaseRepository qRepository, final ProvLocation region, final String term1,
			final String term2) {
		super(parent, ptRepository, tRepository, pRepository, qRepository, region, parent.getDatabaseTypes(), term1,
				term2);
	}

	@Override
	public ProvDatabasePrice newPrice() {
		return new ProvDatabasePrice();
	}

	@Override
	public ProvDatabaseType newType() {
		return new ProvDatabaseType();
	}
}
