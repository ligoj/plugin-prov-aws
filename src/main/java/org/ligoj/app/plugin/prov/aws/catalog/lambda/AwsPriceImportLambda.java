/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.lambda;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.ligoj.app.plugin.prov.aws.catalog.AbstractAwsImport;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.catalog.ImportCatalog;
import org.ligoj.app.plugin.prov.model.AbstractCodedEntity;
import org.ligoj.app.plugin.prov.model.ProvFunctionPrice;
import org.ligoj.app.plugin.prov.model.ProvFunctionType;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * The provisioning Lambda price service for AWS. Manage install and update of prices.
 * 
 * @see {@link https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AWSLambda/20210304163809/af-south-1/index.csv}
 */
@Slf4j
@Component
public class AwsPriceImportLambda extends AbstractAwsImport implements ImportCatalog<UpdateContext> {

	private static final String AWS_PRICES_PATH = "/offers/v1.0/aws/index.json";

	private static final String AWS_PRICES_BASE = "https://pricing.us-east-1.amazonaws.com";

	/**
	 * Configuration key used for {@link #AWS_PRICES_BASE}
	 */
	public static final String CONF_URL_AWS_PRICES = String.format(CONF_URL_API_PRICES, "aws");

	@Override
	public void install(final UpdateContext context) throws IOException {
		log.info("AWS Lambda prices ...");
		nextStep(context, "lambda", null, 0);

		// Track the created instance to cache partial costs
		final var previous = spRepository.findByLocation(context.getNode().getId(), null).stream()
				.filter(p -> p.getType().getCode().startsWith("lambda")).collect(Collectors
						.toMap(p2 -> p2.getLocation().getName() + p2.getType().getCode(), Function.identity()));
		context.setPreviousStorage(previous);
		context.setStorageTypes(previous.values().stream().map(ProvStoragePrice::getType).distinct()
				.collect(Collectors.toMap(AbstractCodedEntity::getCode, Function.identity())));

		final var basePrice = configuration.get(CONF_URL_AWS_PRICES, AWS_PRICES_BASE);
		var priceCounter = 0;
		try (var reader = new BufferedReader(
				new InputStreamReader(new URL(basePrice + AWS_PRICES_PATH).openStream()))) {
			final var offers = objectMapper.readValue(reader, AwsPriceIndex.class).getOffers();
			final var offer = offers.get("AWSLambda");

			final var stdPrice = new ProvFunctionPrice();
			final var provPrice = new ProvFunctionPrice();
			final Map<String, Consumer<AwsLambdaPrice>> mapper = Map.of("AWS-Lambda-Duration-Provisioned", d -> {
				provPrice.setCostRam(provPrice.getCostRam() + d.getPricePerUnit());
				provPrice.setCode(d.getRateCode());
			}, "AWS-Lambda-Requests", d -> {
				stdPrice.setCostRequests(d.getPricePerUnit());
				provPrice.setCostRequests(d.getPricePerUnit());
			}, "AWS-Lambda-Provisioned-Concurrency",
					d -> provPrice.setCostRam(provPrice.getCostRam() + d.getPricePerUnit()), "AWS-Lambda-Duration",
					d -> {
						stdPrice.setCostRam(d.getPricePerUnit());
						stdPrice.setCode(d.getRateCode());
					});

			// Get the remote prices stream
			try (var reader2 = new BufferedReader(
					new InputStreamReader(new URL(basePrice + offer.getCurrentRegionIndexUrl()).openStream()))) {
				final var regions = objectMapper.readValue(reader, AwsPriceRegions.class).getRegions();
				for (var region : regions.values().stream().filter(r -> isEnabledRegion(context, r.getRegionCode()))
						.collect(Collectors.toList())) {
					final var regionUrl = basePrice + region.getCurrentVersionUrl().replaceAll("\\.json$", "\\.csv$");
					stdPrice.setCostRam(0d);
					stdPrice.setCostRequests(0d);
					provPrice.setCostRam(0d);
					provPrice.setCostRequests(0d);
					try (var reader3 = new BufferedReader(new InputStreamReader(new URL(regionUrl).openStream()))) {
						// Pipe to the CSV reader
						final var csvReader = new CsvForBeanLambda(reader3);

						// Build the AWS storage prices from the CSV
						var csv = csvReader.read();
						while (csv != null) {
							stdPrice.setLocation(installRegion(context, region.getRegionCode(), csv.getLocation()));
							mapper.getOrDefault(csv.getGroup(), Function.identity()::apply).accept(csv);

							// Read the next one
							csv = csvReader.read();
						}
						installLambdaPrice(context, stdPrice);
						installLambdaPrice(context, provPrice);
						priceCounter += 2;
					}
				}
			}
		} finally {
			// Report
			log.info("AWS Lambda finished : {} prices", priceCounter);
			nextStep(context, "lambda", null, 1);
		}

	}

	private void installLambdaPrice(final UpdateContext context, final ProvFunctionPrice csv) {
		final var name = "";
		final var type = context.getFunctionTypes().computeIfAbsent(name, n2 -> {
			// New storage type
			final var newType = new ProvFunctionType();
			newType.setCode(n2);
			newType.setNode(context.getNode());
			return newType;
		});

		copyAsNeeded(context, type, t -> {
			// Update storage details
			t.setName(t.getCode());
		}, ftRepository);

		// Update the price as needed
		final var price = context.getPreviousFunction().computeIfAbsent(csv.getCode(), c -> {
			final var p = new ProvFunctionPrice();
			p.setCode(c);
			return p;
		});

		copyAsNeeded(context, price, p -> {
			p.setLocation(p.getLocation());
			p.setType(type);
		});
		saveAsNeeded(context, price, csv.getCostRam(), fpRepository);
	}
}
