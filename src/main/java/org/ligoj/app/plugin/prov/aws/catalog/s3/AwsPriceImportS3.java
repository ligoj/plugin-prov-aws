/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.s3;

import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.prov.aws.catalog.AbstractAwsPriceImportMultiRegion;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.model.ProvStorageOptimized;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.app.plugin.prov.model.Rate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Objects;

/**
 * The provisioning S3 price service for AWS. Manage install or update of prices.
 */
@Component
public class AwsPriceImportS3 extends AbstractAwsPriceImportMultiRegion<AwsS3Price, CsvForBeanS3> {

	/**
	 * Service code.
	 */
	private static final String SERVICE_CODE = "AmazonS3";

	private static final String API = "s3";
	
	@Override
	public void install(final UpdateContext context) throws IOException {
		installPrices(context, API, SERVICE_CODE);
	}

	@Override
	protected void update(final AwsS3Price csv, final ProvStorageType t) {
		t.setName(t.getCode());
		t.setAvailability(toPercent(csv.getAvailability()));
		t.setDurability9(StringUtils.countMatches(Objects.toString(csv.getDurability()), '9'));
		t.setOptimized(ProvStorageOptimized.DURABILITY);
		t.setNetwork("443/tcp");
		t.setLatency(t.getCode().equals("glacier") ? Rate.WORST : Rate.MEDIUM);
		t.setDescription("{\"class\":\"" + csv.getStorageClass() + "\",\"type\":\"" + csv.getVolumeType() + "\"}");
	}

	@Override
	protected CsvForBeanS3 newReader(final BufferedReader reader) throws IOException {
		return new CsvForBeanS3(reader);
	}
}
