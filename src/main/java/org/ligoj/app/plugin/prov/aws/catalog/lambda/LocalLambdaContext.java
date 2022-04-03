/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.lambda;

import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import org.ligoj.app.plugin.prov.aws.catalog.AbstractLocalContext;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.dao.ProvFunctionPriceRepository;
import org.ligoj.app.plugin.prov.dao.ProvFunctionTypeRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceTermRepository;
import org.ligoj.app.plugin.prov.dao.ProvQuoteFunctionRepository;
import org.ligoj.app.plugin.prov.model.ProvFunctionPrice;
import org.ligoj.app.plugin.prov.model.ProvFunctionType;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvQuoteFunction;

import lombok.Getter;
import lombok.Setter;

/**
 * Context used to perform catalog update of Lambda for a specific region.
 */
@Getter
@Setter
public class LocalLambdaContext
		extends AbstractLocalContext<ProvFunctionType, ProvFunctionPrice, AwsLambdaPrice, ProvQuoteFunction> {

	private AwsLambdaPrice last;
	private ProvFunctionPrice edgePrice;
	private ProvFunctionPrice stdPrice;
	private ProvFunctionPrice provPrice;
	private ProvFunctionPrice stdPriceArm;
	private ProvFunctionPrice provPriceArm;
	private Map<Set<String>, BiConsumer<String,Double>> mapper;

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
	public LocalLambdaContext(final UpdateContext parent, final ProvInstancePriceTermRepository ptRepository,
			final ProvFunctionTypeRepository tRepository, final ProvFunctionPriceRepository pRepository,
			final ProvQuoteFunctionRepository qRepository, final ProvLocation region, final String term1,
			final String term2) {
		super(parent, ptRepository, tRepository, pRepository, qRepository, region, parent.getFunctionTypes(), term1,
				term2);
	}

	@Override
	public ProvFunctionPrice newPrice() {
		final var price = new ProvFunctionPrice();
		price.setCostRam(0d);
		return price;
	}

	@Override
	public ProvFunctionType newType() {
		return new ProvFunctionType();
	}

}
