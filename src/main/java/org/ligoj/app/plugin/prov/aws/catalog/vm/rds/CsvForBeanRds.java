/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.rds;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AbstractCsvForBeanEc2;

/**
 * Read AWS RDS CSV input, skipping the AWS headers and non instance type rows.
 */
public class CsvForBeanRds extends AbstractCsvForBeanEc2<AwsRdsPrice> {

	/**
	 * RDS CSV Mapping to Java bean property
	 */
	private static final Map<String, String> HEADERS_MAPPING = new HashMap<>();
	static {
		HEADERS_MAPPING.put("Database Engine", "engine");
		HEADERS_MAPPING.put("Database Edition", "edition");
		HEADERS_MAPPING.put("Min Volume Size", "sizeMin");
		HEADERS_MAPPING.put("Max Volume Size", "sizeMax");
		HEADERS_MAPPING.put("Volume Type", "volume");
		HEADERS_MAPPING.put("Storage Media", "storage");
	}

	/**
	 * Build the reader parsing the CSV file from AWS to build {@link AwsRdsPrice} instances. Non AWS instances data are
	 * skipped, and headers are ignored.
	 *
	 * @param reader The original AWS CSV input.
	 * @throws IOException When CSV content cannot be read.
	 */
	public CsvForBeanRds(final BufferedReader reader) throws IOException {
		super(reader, HEADERS_MAPPING, AwsRdsPrice.class);
	}

	@Override
	public boolean isValidRaw(List<String> rawValues) {
		// Only Single-AZ
		// Only "Database Instance" and "Database Storage" products
		// No outpost
		return rawValues.size() > 37 && "Single-AZ".equals(rawValues.get(37)) && "AWS Region".equals(rawValues.get(18))
				&& ("Database Instance".equals(rawValues.get(15)) || "Database Storage".equals(rawValues.get(15)));
	}

}
