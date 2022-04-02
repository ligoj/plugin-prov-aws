/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.fargate;

import org.ligoj.app.plugin.prov.aws.catalog.AbstractLocalContext;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.dao.ProvContainerPriceRepository;
import org.ligoj.app.plugin.prov.dao.ProvContainerTypeRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceTermRepository;
import org.ligoj.app.plugin.prov.dao.ProvQuoteContainerRepository;
import org.ligoj.app.plugin.prov.model.ProvContainerPrice;
import org.ligoj.app.plugin.prov.model.ProvContainerType;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvQuoteContainer;

/**
 * Context used to perform catalog update.
 */
public class LocalFargateContext
		extends AbstractLocalContext<ProvContainerType, ProvContainerPrice, AwsFargatePrice, ProvQuoteContainer> {

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
	public LocalFargateContext(final UpdateContext parent, final ProvInstancePriceTermRepository ptRepository,
			final ProvContainerTypeRepository tRepository, final ProvContainerPriceRepository pRepository,
			final ProvQuoteContainerRepository qRepository, final ProvLocation region, final String term1,
			final String term2) {
		super(parent, ptRepository, tRepository, pRepository, qRepository, region, parent.getContainerTypes(), term1,
				term2);
	}

	@Override
	public ProvContainerPrice newPrice() {
		return new ProvContainerPrice();
	}

	@Override
	public ProvContainerType newType() {
		return new ProvContainerType();
	}
}
