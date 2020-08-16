/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.ec2;

import org.ligoj.app.plugin.prov.aws.catalog.AbsractLocalContext;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceTermRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstanceTypeRepository;
import org.ligoj.app.plugin.prov.dao.ProvQuoteInstanceRepository;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstanceType;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;

/**
 * Context used to perform catalog update.
 * 
 * @param <T> The instance type's type.
 * @param <P> The price's type.
 * @param <C> The CSV price type.
 * @param <Q> The quote type.
 */
public class LocalEc2Context
		extends AbsractLocalContext<ProvInstanceType, ProvInstancePrice, AwsEc2Price, ProvQuoteInstance> {

	/**
	 * Context from the parent.
	 * 
	 * @param parent      The parent context.
	 * @param ptRepository The term repository.
	 * @param tRepository The type repository.
	 * @param pRepository The price repository.
	 * @param qRepository The quote repository.
	 * @param region      The current region.
	 * @param term1       The expected term name prefix alternative 1.
	 * @param term2       The expected term name prefix alternative 2.
	 */
	public LocalEc2Context(final UpdateContext parent, final ProvInstancePriceTermRepository ptRepository, final ProvInstanceTypeRepository tRepository,
			final ProvInstancePriceRepository pRepository, final ProvQuoteInstanceRepository qRepository,
			final ProvLocation region, final String term1, final String term2) {
		super(parent, ptRepository, tRepository, pRepository, qRepository, region, parent.getInstanceTypes(), term1, term2);
	}

	@Override
	public ProvInstancePrice newPrice() {
		return new ProvInstancePrice();
	}

	@Override
	public ProvInstanceType newType() {
		return new ProvInstanceType();
	}
}
