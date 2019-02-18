/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.s3;

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
 * Read AWS S3 CSV input, skipping the AWS headers and non instance type rows.
 */
public class CsvForBeanS3 extends AbstractAwsCsvForBean<AwsS3Price> {

	/**
	 * EFS S3 Mapping to Java bean property
	 */
	private static final Map<String, String> HEADERS_MAPPING = new HashMap<>();
	static {
		HEADERS_MAPPING.put("Volume Type", "volumeType");
		HEADERS_MAPPING.put("Storage Class", "storageClass");
		HEADERS_MAPPING.put("Availability", "availability");
		HEADERS_MAPPING.put("Durability", "durability");
	}

	/**
	 * Build the reader parsing the CSV file from AWS to build {@link AwsS3Price} objects.
	 * 
	 * @param reader
	 *            The original AWS CSV input.
	 * @throws IOException
	 *             When no valid CSV header can be found.
	 */
	public CsvForBeanS3(final BufferedReader reader) throws IOException {
		super(reader, HEADERS_MAPPING, AwsS3Price.class);
	}

	@Override
	protected CsvBeanReader<AwsS3Price> newCsvReader(final Reader reader, final String[] headers,
			final Class<AwsS3Price> beanType) {
		return new AbstractAwsCsvReader<>(reader, headers, beanType) {

			@Override
			protected boolean isValidRaw(final List<String> rawValues) {
				// Only starting range = 0
				// Only "Product Family" = "Storage"
				// No "Storage Class" = "Tags"
				return "0".equals(rawValues.get(6)) && !"Tags".equals(rawValues.get(16))
						&& "Storage".equals(rawValues.get(11));
			}

		};
	}
}
