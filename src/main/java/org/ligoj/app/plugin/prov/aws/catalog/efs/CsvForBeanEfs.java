/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.efs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ligoj.app.plugin.prov.aws.catalog.AbstractAwsCsvForBean;
import org.ligoj.app.plugin.prov.aws.catalog.AbstractAwsCsvReader;
import org.ligoj.bootstrap.core.csv.CsvBeanReader;

/**
 * Read AWS EFS CSV input, skipping the AWS headers and non instance type rows.
 */
public class CsvForBeanEfs extends AbstractAwsCsvForBean<AwsEfsPrice> {

	/**
	 * EFS CSV Mapping to Java bean property
	 */
	private static final Map<String, String> HEADERS_MAPPING = new HashMap<>();
	static {
		HEADERS_MAPPING.put("Storage Class", "storageClass");
		HEADERS_MAPPING.put("Volume Type", "volumeType");
	}

	/**
	 * Build the reader parsing the CSV file from AWS to build {@link AwsEfsPrice} instances. Non AWS instances data are
	 * skipped, and headers are ignored.
	 *
	 * @param reader The original AWS CSV input.
	 * @throws IOException When no valid CSV header can be found.
	 */
	public CsvForBeanEfs(final BufferedReader reader) throws IOException {
		super(reader, HEADERS_MAPPING, AwsEfsPrice.class);
	}

	@Override
	protected CsvBeanReader<AwsEfsPrice> newCsvReader(final Reader reader, final String[] headers,
			final Class<AwsEfsPrice> beanType) {
		return new AbstractAwsCsvReader<>(reader, headers, beanType) {

			@Override
			protected boolean isValidRaw(final List<String> rawValues) {
				// Only "Storage" pricing, no Provisioned Throughput for now
				return "Storage".equals(rawValues.get(11)) && "GB-Mo".equals(rawValues.get(8));
			}

		};
	}

}
