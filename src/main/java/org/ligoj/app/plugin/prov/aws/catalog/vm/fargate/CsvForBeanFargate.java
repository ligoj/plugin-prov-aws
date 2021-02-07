/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.fargate;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AbstractCsvForBeanEc2;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AwsEc2Price;

/**
 * Read AWS EC2 CSV input, skipping the AWS headers and non instance type rows.
 */
public class CsvForBeanFargate extends AbstractCsvForBeanEc2<AwsFargatePrice> {

	/**
	 * EC2 CSV Mapping to Java bean property
	 */
	private static final Map<String, String> HEADERS_MAPPING = new HashMap<>();
	static {
		HEADERS_MAPPING.put("usageType", "usageType");
	}

	/**
	 * Build the reader parsing the CSV file from AWS to build {@link AwsEc2Price} instances. Non AWS instances data are
	 * skipped, and headers are ignored.
	 *
	 * @param reader The original AWS CSV input.
	 * @throws IOException When CSV content cannot be read.
	 */
	public CsvForBeanFargate(final BufferedReader reader) throws IOException {
		super(reader, HEADERS_MAPPING, AwsFargatePrice.class);
	}

	@Override
	public boolean isValidRaw(final List<String> rawValues) {
		return rawValues.size() > 20 && "hours".equals(rawValues.get(8));
	}

}
