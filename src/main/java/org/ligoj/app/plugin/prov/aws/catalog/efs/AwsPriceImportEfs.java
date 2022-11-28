/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.efs;

import java.io.BufferedReader;
import java.io.IOException;

import org.ligoj.app.plugin.prov.aws.catalog.AbstractAwsPriceImportMultiRegion;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.springframework.stereotype.Component;

/**
 * The provisioning EFS price service for AWS. Manage install or update of prices.
 *
 * @see <a href="https://docs.aws.amazon.com/efs/latest/ug/storage-classes.html">EFS classes</a>
 */
@Component
public class AwsPriceImportEfs extends AbstractAwsPriceImportMultiRegion<AwsEfsPrice, CsvForBeanEfs> {

	/**
	 * Service code.
	 */
	private static final String SERVICE_CODE = "AmazonEFS";

	private static final String API = "efs";

	@Override
	public void install(final UpdateContext context) throws IOException {
		installPrices(context, API, SERVICE_CODE);
	}

	@Override
	protected void update(final AwsEfsPrice csv, final ProvStorageType t) {
		// Nothing to do;
	}

	@Override
	protected CsvForBeanEfs newReader(final BufferedReader reader) throws IOException {
		return new CsvForBeanEfs(reader);
	}
}
