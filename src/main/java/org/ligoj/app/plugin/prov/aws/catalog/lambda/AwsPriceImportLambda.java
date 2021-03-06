/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.lambda;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Map;
import java.util.function.Function;

import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.aws.catalog.vm.AbstractAwsPriceImportVm;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.SavingsPlanPrice.SavingsPlanProduct;
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

	@Override
	protected void installPrice(final LocalLambdaContext context, final AwsLambdaPrice csv) {
		context.setLast(csv);
		context.getMapper().getOrDefault(csv.getGroup(), Function.identity()::apply).accept(csv);
	}

	@Override
	protected void purgePrices(final LocalLambdaContext context) {
		final var last = context.getLast();
		if (last != null) {
			// At least one price
			final var region = installRegion(context, context.getRegion().getName(), last.getLocation());
			final var term = installInstancePriceTerm(context, last);
			installLambdaPrice(context, region, term, context.getStdPrice(), last, "lambda");
			installLambdaPrice(context, region, term, context.getProvPrice(), last, "provisionned");
		}

		super.purgePrices(context);
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

	private void installLambdaPrice(final LocalLambdaContext context, final ProvLocation region,
			final ProvInstancePriceTerm term, final ProvFunctionPrice tempPrice, final AwsLambdaPrice csv,
			final String typeName) {
		csv.setInstanceType(typeName);
		csv.setRateCode(tempPrice.getCode());
		final var price = newPrice(context, csv);
		saveAsNeeded(context, price, price.getCostRamRequest(),
				tempPrice.getCostRamRequest() * 3600 * context.getHoursMonth(), (cR, c) -> {
					price.setCostRamRequest(cR);
					price.setCostRequests(tempPrice.getCostRequests() * 1000000);
					price.setCostRamRequestConcurrency(tempPrice.getCostRamRequestConcurrency());
					price.setCostPeriod(round3Decimals(c * price.getTerm().getPeriod()));
				}, context.getPRepository()::save);
	}

	@Override
	protected void copy(final AwsLambdaPrice csv, final ProvFunctionPrice p) {
		p.setIncrementRam(1d / 1024d);
		p.setIncrementCpu(1d);
		p.setMaxRam(10d);
		p.setMinRam(128d / 1024d);
		p.setMinRamRatio(0d);
		p.setCostCpu(0d);
		p.setCostRam(0d);
		p.setIncrementDuration(1d);
		p.setMinDuration(1d);
		p.setMaxDuration(900000d); // 15min
	}

	@Override
	protected void copySavingsPlan(final ProvFunctionPrice odPrice, final ProvFunctionPrice p) {
		super.copySavingsPlan(odPrice, p);
		copy(null, p);
	}

	@Override
	protected void copy(final AwsLambdaPrice csv, final ProvFunctionType t) {
		t.setName(t.getCode());
		t.setConstant("provisionned".equals(t.getCode()));
		t.setAutoScale(true);
		t.setCpu(0);
		t.setPhysical(Boolean.FALSE);
		t.setStorageRate(Rate.MEDIUM);
		t.setCpuRate(Rate.MEDIUM);
		t.setRamRate(Rate.MEDIUM);
		t.setNetworkRate(Rate.MEDIUM);
	}

	@Override
	protected LocalLambdaContext newContext(UpdateContext gContext, ProvLocation region, String term1, String term2) {
		final var context = new LocalLambdaContext(gContext, iptRepository, ftRepository, fpRepository, qfRepository,
				region, term1, term2);
		final var stdPrice = new ProvFunctionPrice();
		final var provPrice = new ProvFunctionPrice();
		context.setStdPrice(stdPrice);
		context.setProvPrice(provPrice);

		// Prepare the mapper to aggregate CSV entries
		context.setMapper(Map.of("AWS-Lambda-Duration-Provisioned", d -> {
			provPrice.setCostRamRequestConcurrency(d.getPricePerUnit());
			provPrice.setCode(d.getRateCode());
		}, "AWS-Lambda-Requests", d -> {
			stdPrice.setCostRequests(d.getPricePerUnit());
			provPrice.setCostRequests(d.getPricePerUnit());
		}, "AWS-Lambda-Provisioned-Concurrency", d -> provPrice.setCostRam(d.getPricePerUnit()), "AWS-Lambda-Duration",
				d -> {
					stdPrice.setCostRamRequest(d.getPricePerUnit());
					provPrice.setCostRamRequest(d.getPricePerUnit());
					stdPrice.setCode(d.getRateCode());
				}));
		return context;
	}

	@Override
	protected CsvForBeanLambda newReader(BufferedReader reader) throws IOException {
		return new CsvForBeanLambda(reader);
	}

	@Override
	public AbstractAwsPriceImportVm<ProvFunctionType, ProvFunctionPrice, AwsLambdaPrice, ProvQuoteFunction, LocalLambdaContext, CsvForBeanLambda> newProxy() {
		return SpringUtils.getBean(AwsPriceImportLambda.class);
	}
}
