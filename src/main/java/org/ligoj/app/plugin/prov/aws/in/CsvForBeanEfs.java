/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.in;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ligoj.bootstrap.core.csv.CsvBeanReader;

/**
 * Read AWS EFS CSV input, skipping the AWS headers and non instance type rows.
 */
public class CsvForBeanEfs extends AbstractAwsCsvForBean<AwsCsvPrice> {

	/**
	 * EFS CSV Mapping to Java bean property
	 */
	private static final Map<String, String> HEADERS_MAPPING = new HashMap<>();

	/**
	 * Build the reader parsing the CSV file from AWS to build {@link AwsPrice}
	 * instances. Non AWS instances data are skipped, and headers are ignored.
	 *
	 * @param reader
	 *            The original AWS CSV input.
	 * @throws IOException
	 *             When no valid CSV header can be found.
	 */
	public CsvForBeanEfs(final BufferedReader reader) throws IOException {
		super(reader, HEADERS_MAPPING, AwsCsvPrice.class);
	}

	@Override
	protected CsvBeanReader<AwsCsvPrice> newCsvReader(final Reader reader, final String[] headers, final Class<AwsCsvPrice> beanType) {
		return new AwsCsvReader<>(reader, headers, beanType) {

			@Override
			protected boolean isValidRaw(final List<String> rawValues) {
				// Only "Storage" pricing, no Provisioned Throughput for now
				return "Storage".equals(rawValues.get(11));
			}

		};
	}

}
