/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.lambda;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AbstractCsvForBeanEc2;

/**
 * Read AWS Lambda CSV input, skipping the AWS headers and non data type rows.
 */
public class CsvForBeanLambda extends AbstractCsvForBeanEc2<AwsLambdaPrice> {

	/**
	 * Lambda Mapping to Java bean property
	 */
	private static final Map<String, String> HEADERS_MAPPING = new HashMap<>();
	static {
		HEADERS_MAPPING.put("Group", "group");
	}

	/**
	 * Build the reader parsing the CSV file from AWS to build {@link AwsLambdaPrice} objects.
	 *
	 * @param reader The original AWS CSV input.
	 * @throws IOException When no valid CSV header can be found.
	 */
	public CsvForBeanLambda(final BufferedReader reader) throws IOException {
		super(reader, HEADERS_MAPPING, AwsLambdaPrice.class);
	}

	@Override
	public boolean isValidRaw(List<String> rawValues) {
		return !(rawValues.get(4).contains("Free Tier"));
	}
}
