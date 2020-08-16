/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AbstractAwsEc2Price;
import org.ligoj.app.plugin.prov.catalog.AbstractUpdateContext;
import org.ligoj.app.plugin.prov.dao.BaseProvInstanceTypeRepository;
import org.ligoj.app.plugin.prov.dao.BaseProvQuoteRepository;
import org.ligoj.app.plugin.prov.dao.BaseProvTermPriceRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceTermRepository;
import org.ligoj.app.plugin.prov.model.AbstractCodedEntity;
import org.ligoj.app.plugin.prov.model.AbstractInstanceType;
import org.ligoj.app.plugin.prov.model.AbstractQuoteVm;
import org.ligoj.app.plugin.prov.model.AbstractTermPrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvLocation;

import lombok.Getter;
import lombok.Setter;

/**
 * Context used to perform catalog update.
 *
 * @param <T> The instance type's type.
 * @param <P> The price's type.
 * @param <C> The CSV price type.
 * @param <Q> The quote type.
 */
public abstract class AbsractLocalContext<T extends AbstractInstanceType, P extends AbstractTermPrice<T>, C extends AbstractAwsEc2Price, Q extends AbstractQuoteVm<P>>
		extends AbstractUpdateContext {

	@Getter
	@Setter
	private ProvLocation region;

	/**
	 * The current partial cost for up-front options.
	 */
	@Getter
	private final Map<String, C> partialCost = new HashMap<>();

	/**
	 * The previous installed local prices. Key is the code.
	 */
	@Getter
	private final Map<String, P> locals;

	/**
	 * The previous installed types. Key is the code.
	 */
	@Getter
	private Map<String, T> previousTypes;

	@Getter
	private final BaseProvInstanceTypeRepository<T> tRepository;

	@Getter
	private final BaseProvTermPriceRepository<T, P> pRepository;

	@Getter
	private final BaseProvQuoteRepository<Q> qRepository;

	/**
	 * The locally (current transaction) installed types. Key is the database code.
	 */
	@Getter
	@Setter
	protected Map<String, T> localTypes = new HashMap<>();

	/**
	 * The previously installed price term's codes.
	 */
	@Getter
	protected Map<String, ProvInstancePriceTerm> localPriceTerms = new HashMap<>();

	/**
	 * Context from the parent.
	 *
	 * @param parent        The parent context.
	 * @param ptRepository  The term repository.
	 * @param tRepository   The type repository.
	 * @param pRepository   The price repository.
	 * @param qRepository   The quote repository.
	 * @param region        The current region.
	 * @param previousTypes The previous types.
	 * @param term1         The expected term name prefix alternative 1.
	 * @param term2         The expected term name prefix alternative 2.
	 */
	protected AbsractLocalContext(final UpdateContext parent, final ProvInstancePriceTermRepository ptRepository,
			final BaseProvInstanceTypeRepository<T> tRepository, final BaseProvTermPriceRepository<T, P> pRepository,
			final BaseProvQuoteRepository<Q> qRepository, final ProvLocation region, final Map<String, T> previousTypes,
			final String term1, final String term2) {
		super(parent);
		this.tRepository = tRepository;
		this.pRepository = pRepository;
		this.qRepository = qRepository;
		this.region = region;
		this.previousTypes = previousTypes;
		this.localTypes = tRepository.findAllBy("node.id", node.getId()).stream()
				.collect(Collectors.toMap(AbstractCodedEntity::getCode, Function.identity()));
		this.localPriceTerms = ptRepository.findByLocation(node.getId(), region.getName(), term1, term2).stream()
				.collect(Collectors.toMap(ProvInstancePriceTerm::getCode, Function.identity()));
		this.locals = pRepository.findByLocation(node.getId(), region.getName(), term1, term2).stream()
				.collect(Collectors.toMap(AbstractTermPrice::getCode, Function.identity()));
	}

	/**
	 * Return a new price from a code.
	 *
	 * @param code The code to assign.
	 * @return The new price with updated code.
	 */
	public P newPrice(final String code) {
		var price = newPrice();
		price.setCode(code);
		return price;
	}

	/**
	 * Return a new price instance.
	 *
	 * @return A new price instance.
	 */
	public abstract P newPrice();

	/**
	 * Return a new type instance.
	 *
	 * @return A new type instance.
	 */
	public abstract T newType();

	/**
	 * Release pointers.
	 */
	public void cleanup() {
		this.locals.clear();
		this.partialCost.clear();
		this.region = null;
		this.previousTypes = null;
		setStorageTypes(null);
	}
}
