/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.ec2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ligoj.app.plugin.prov.aws.catalog.AbstractAwsCsvForBean;
import org.ligoj.app.plugin.prov.aws.catalog.AbstractAwsCsvReader;
import org.ligoj.app.plugin.prov.catalog.Co2Data;
import org.ligoj.bootstrap.core.csv.CsvBeanReader;

/**
 * Read CO2 dataset input, skipping the headers, coment and separator definition.
 */
public class CsvForBeanCo2 extends AbstractAwsCsvForBean<Co2Data> {

	/**
	 * EC2 CSV Mapping to Java bean property
	 */
	private static final Map<String, String> HEADERS_MAPPING = new HashMap<>();
	static {
		HEADERS_MAPPING.put("Instance type", "type");
		HEADERS_MAPPING.put("Type", "type");
		HEADERS_MAPPING.put("Instance Hourly Manufacturing Emissions", "value");
		HEADERS_MAPPING.put("co2", "value");
	}

	/**
	 * Build the reader parsing the CSV file from AWS to build {@link AwsEc2Price} instances. Non AWS instances data are
	 * skipped, and headers are ignored.
	 *
	 * @param reader The original AWS CSV input.
	 * @throws IOException When CSV content cannot be read.
	 */
	public CsvForBeanCo2(final BufferedReader reader) throws IOException {
		super(reader, HEADERS_MAPPING, Co2Data.class);
	}

	@Override
	protected CsvBeanReader<Co2Data> newCsvReader(final Reader reader, final String[] headers,
			final Class<Co2Data> beanType) {
		return new AbstractAwsCsvReader<>(reader, headers, beanType) {

			@Override
			protected boolean isValidRaw(final List<String> rawValues) {
				return rawValues.size() > 1;
			}

		};
	}

	@Override
	protected boolean isHeaderRow(final List<String> values) {
		// No extra padding before headers
		return true;
	}
}
