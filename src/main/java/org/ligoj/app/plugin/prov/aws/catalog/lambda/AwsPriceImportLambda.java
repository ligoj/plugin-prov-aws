/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.lambda;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.aws.catalog.vm.AbstractAwsPriceImportVm;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.SavingsPlanPrice.SavingsPlanProduct;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.SavingsPlanPrice.SavingsPlanRate;
import org.ligoj.app.plugin.prov.model.ProvFunctionPrice;
import org.ligoj.app.plugin.prov.model.ProvFunctionType;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvQuoteFunction;
import org.ligoj.app.plugin.prov.model.Rate;
import org.ligoj.bootstrap.core.SpringUtils;
import org.springframework.stereotype.Component;

/**
 * The provisioning Lambda price service for AWS. Manage install and update of prices.
 * 
 * @see {@link https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AWSLambda/20210304163809/af-south-1/index.csv}
 */
@Component
public class AwsPriceImportLambda extends
		AbstractAwsPriceImportVm<ProvFunctionType, ProvFunctionPrice, AwsLambdaPrice, ProvQuoteFunction, LocalLambdaContext, CsvForBeanLambda> {

	/**
	 * Service code.
	 */
	private static final String SERVICE_CODE = "AWSLambda";

	/**
	 * API name of this service.
	 */
	private static final String API = "lambda";

	/**
	 * Lambda types to aggregated price.
	 */
	private static final Map<String, Function<LocalLambdaContext, ProvFunctionPrice>> TYPE_TO_OD_PRICE = Map.of(
			"lambda-edge", LocalLambdaContext::getEdgePrice, "lambda", LocalLambdaContext::getStdPrice, "provisioned",
			LocalLambdaContext::getProvPrice, "lambda-arm", LocalLambdaContext::getStdPriceArm, "provisioned-arm",
			LocalLambdaContext::getProvPriceArm);

	@Override
	protected void installPrice(final LocalLambdaContext context, final AwsLambdaPrice csv) {
		// Ignore Free Tier
		if (!"Any".equals(csv.getLocation())) {
			context.setLast(csv);
			completePrices(context, csv.getGroup(), csv.getRateCode(), csv.getPricePerUnit());
		}
	}

	private void completePrices(final LocalLambdaContext context, final String rateType, final String code,
			final double price) {
		context.getMapper().entrySet().stream().filter(e -> e.getKey().stream().anyMatch(f -> rateType.endsWith(f)))
				.findFirst().ifPresent(e -> e.getValue().accept(code, price));
	}

	@Override
	public void install(final UpdateContext context) throws IOException {
		// Get the remote prices stream
		installPrices(context, API, SERVICE_CODE, TERM_ON_DEMAND, null);
	}

	@Override
	protected boolean filterSPProduct(final SavingsPlanProduct product) {
		return "ComputeSavingsPlans".equals(product.getProductFamily());
	}

	@Override
	protected boolean isEnabled(final LocalLambdaContext context, final AwsLambdaPrice csv) {
		return true;
	}

	@Override
	protected void purgePrices(final LocalLambdaContext context) {
		final var last = context.getLast();
		if (last != null) {
			// At least one price in this region
			saveAsNeededLambda(context, aggPrice -> last);

			// Prevent multiples calls
			context.setLast(null);
		}

		super.purgePrices(context);
	}

	@Override
	protected void copy(final AwsLambdaPrice csv, final ProvFunctionPrice p) {
		p.setIncrementRam(1d / 1024d); // 1MiB
		p.setIncrementCpu(1d);
		p.setMinCpu(0d);
		p.setMaxCpu(4d);
		p.setMaxRam(10d);
		p.setMinRam(128d / 1024d); // 128MiB
		p.setMinRamRatio(0d);
		p.setCostCpu(0d);
		p.setCostRam(0d); // No provisioned price by default
		p.setIncrementDuration(1d);
		p.setMinDuration(1d);
		p.setMaxDuration(900000d); // 15min
	}

	@Override
	protected void copySavingsPlan(final ProvFunctionPrice odPrice, final ProvFunctionPrice p) {
		super.copySavingsPlan(odPrice, p);
		copy(null, p);
		p.setCostRequests(odPrice.getCostRequests());
	}

	@Override
	protected void copy(final LocalLambdaContext context, final AwsLambdaPrice csv, final ProvFunctionType t) {
		final var provisioned = t.getCode().startsWith("provisioned");
		t.setName(t.getCode());
		t.setConstant(provisioned);
		t.setAutoScale(true);
		t.setCpu(0);
		t.setProcessor(t.getCode().endsWith("-arm") ? "ARM" : "INTEL");
		t.setPhysical(Boolean.FALSE);
		t.setStorageRate(Rate.MEDIUM);
		t.setCpuRate(Rate.MEDIUM);
		t.setRamRate(Rate.MEDIUM);
		t.setNetworkRate(Rate.MEDIUM);
		t.setEdge("lambda-edge".equals(t.getCode()));
		t.setDescription((provisioned ? "Provisioned Concurrency " : "Non provisioned ") + t.getProcessor());
	}

	@Override
	protected LocalLambdaContext newContext(final UpdateContext gContext, final ProvLocation region, final String term1,
			String term2) {
		final var context = new LocalLambdaContext(gContext, iptRepository, ftRepository, fpRepository, qfRepository,
				region, term1, term2);
		setupMaper(context);
		return context;
	}

	private void setupMaper(final LocalLambdaContext context) {
		final var stdPrice = new ProvFunctionPrice();
		final var edgePrice = new ProvFunctionPrice();
		final var stdPriceArm = new ProvFunctionPrice();
		final var provPrice = new ProvFunctionPrice();
		final var provPriceArm = new ProvFunctionPrice();
		context.setEdgePrice(edgePrice);
		context.setStdPrice(stdPrice);
		context.setStdPriceArm(stdPriceArm);
		context.setProvPrice(provPrice);
		context.setProvPriceArm(provPriceArm);

		// Prepare the mapping to aggregate CSV and JSON entries related to specific term and region
		context.setMapper(Map.of(Set.of("AWS-Lambda-Duration-Provisioned", "Lambda-Provisioned-GB-Second"), (c, p) -> {
			provPrice.setCostRamRequestConcurrency(p);
			provPrice.setCode(c);
		}, Set.of("AWS-Lambda-Duration-Provisioned-ARM", "Lambda-Provisioned-GB-Second-ARM"), (c, p) -> {
			provPriceArm.setCostRamRequestConcurrency(p);
			provPriceArm.setCode(c);
		}, Set.of("AWS-Lambda-Duration", "Lambda-GB-Second"), (c, p) -> {
			provPrice.setCostRamRequest(p);
			stdPrice.setCostRamRequest(p);
			stdPrice.setCode(c);
		}, Set.of("AWS-Lambda-Edge-Duration"), (c, p) -> {
			edgePrice.setCostRamRequest(p);
			edgePrice.setCode(c);
		}, Set.of("AWS-Lambda-Duration-ARM", "Lambda-GB-Second-ARM"), (c, p) -> {
			provPriceArm.setCostRamRequest(p);
			stdPriceArm.setCostRamRequest(p);
			stdPriceArm.setCode(c);
		}, Set.of("AWS-Lambda-Edge-Requests"), (c, p) -> {
			edgePrice.setCostRequests(p);
		}, Set.of("AWS-Lambda-Requests", "Request"), (c, p) -> {
			provPrice.setCostRequests(p);
			stdPrice.setCostRequests(p);
		}, Set.of("AWS-Lambda-Requests-ARM", "Request-ARM"), (c, p) -> {
			provPriceArm.setCostRequests(p);
			stdPriceArm.setCostRequests(p);
		}, Set.of("Lambda-Provisioned-Concurrency"), (c, p) -> provPrice.setCostRam(p),
				Set.of("Lambda-Provisioned-Concurrency-ARM"), (c, p) -> provPriceArm.setCostRam(p)));
	}

	@Override
	protected Stream<String> installSavingsPlanRates(final LocalLambdaContext context, final String serviceCode,
			final ProvInstancePriceTerm term, final Map<String, ProvFunctionPrice> previousOd, final String odTermCode,
			final Collection<SavingsPlanRate> rates) {

		// First pass, collect and aggregate prices
		setupMaper(context);
		final var result = super.installSavingsPlanRates(context, serviceCode, term, previousOd, odTermCode, rates)
				.filter(Objects::nonNull).toList();

		// Then persist the aggregated prices
		saveAsNeededLambda(context, aggPrice -> {
			final var csv = new AwsLambdaPrice();
			csv.setOfferTermCode(term.getCode());
			return csv;
		});
		return result.stream();
	}

	private void saveAsNeededLambda(final LocalLambdaContext context,
			final Function<ProvFunctionPrice, AwsLambdaPrice> csvProvider) {
		TYPE_TO_OD_PRICE.forEach((typeName, p) -> {
			final var aggPrice = p.apply(context);
			if (aggPrice.getCode() != null) {
				// At least one price of this type
				final var csv = csvProvider.apply(aggPrice);
				csv.setInstanceType(typeName);
				csv.setRateCode(aggPrice.getCode());
				final var price = newPrice(context, csv);
				saveAsNeeded(context, price, aggPrice);
			}
		});
	}

	/**
	 * Save the price from the aggregated price.
	 * 
	 * @param context  The regional update context.
	 * @param price    The target price entity to save.
	 * @param aggPrice The aggregated price from request, GB.s and provisioning costs. This not the actual price to be
	 *                 persisted.
	 * @return The persisted entity.
	 */
	private ProvFunctionPrice saveAsNeeded(final LocalLambdaContext context, final ProvFunctionPrice price,
			final ProvFunctionPrice aggPrice) {
		return saveAsNeeded(context, price, price.getCostRamRequest(),
				aggPrice.getCostRamRequest() * context.getSecondsMonth(), (cR, c) -> {
					price.setCostRamRequest(cR);
					price.setCostRequests(round3Decimals(aggPrice.getCostRequests() * 1e6d));
					price.setCostRamRequestConcurrency(
							round3Decimals(aggPrice.getCostRamRequestConcurrency() * context.getSecondsMonth()));
					saveInitialCost(context, price, c);
				}, context.getPRepository()::save);
	}

	@Override
	protected String installSavingsPlanPrice(final LocalLambdaContext context, final ProvInstancePriceTerm term,
			final SavingsPlanRate jsonPrice, final Map<String, ProvFunctionPrice> previousOd, final String odTermCode) {
		completePrices(context, jsonPrice.getDiscountedUsageType(), jsonPrice.getRateCode(),
				jsonPrice.getDiscountedRate().getPrice());
		return null;
	}

	@Override
	protected CsvForBeanLambda newReader(final BufferedReader reader) throws IOException {
		return new CsvForBeanLambda(reader);
	}

	@Override
	public AbstractAwsPriceImportVm<ProvFunctionType, ProvFunctionPrice, AwsLambdaPrice, ProvQuoteFunction, LocalLambdaContext, CsvForBeanLambda> newProxy() {
		return SpringUtils.getBean(AwsPriceImportLambda.class);
	}
}
