package org.ligoj.app.plugin.prov.aws.in;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.ligoj.bootstrap.core.csv.CsvBeanReader;

/**
 * Read AWS EC2 CSV input, skipping the AWS headers and non instance type rows.
 */
public class CsvForBeanEc2 extends AbstractAwsCsvForBean<AwsEc2Price> {

	/**
	 * EC2 CSV Mapping to Java bean property
	 */
	private static final Map<String, String> HEADERS_MAPPING = new HashMap<>();
	static {
		HEADERS_MAPPING.put("Unit", "priceUnit");
		HEADERS_MAPPING.put("LeaseContractLength", "leaseContractLength");
		HEADERS_MAPPING.put("PurchaseOption", "purchaseOption");
		HEADERS_MAPPING.put("OfferingClass", "offeringClass");
		HEADERS_MAPPING.put("Instance Type", "instanceType");
		HEADERS_MAPPING.put("vCPU", "cpu");
		HEADERS_MAPPING.put("Physical Processor", "physicalProcessor");
		HEADERS_MAPPING.put("Clock Speed", "clockSpeed");
		HEADERS_MAPPING.put("Memory", "memory");
		HEADERS_MAPPING.put("Tenancy", "tenancy");
		HEADERS_MAPPING.put("Operating System", "os");
		HEADERS_MAPPING.put("License Model", "licenseModel");
		HEADERS_MAPPING.put("ECU", "ecu");
		HEADERS_MAPPING.put("Pre Installed S/W", "software");
	}

	/**
	 * Build the reader parsing the CSV file from AWS to build {@link AwsEc2Price} instances. Non AWS instances data are
	 * skipped, and headers are ignored.
	 * 
	 * @param reader
	 *            The original AWS CSV input.
	 * @throws IOException
	 *             When CSV content cannot be read.
	 */
	public CsvForBeanEc2(final BufferedReader reader) throws IOException {
		super(reader, HEADERS_MAPPING, AwsEc2Price.class);
	}

	@Override
	protected CsvBeanReader<AwsEc2Price> newCsvReader(final Reader reader, final String[] headers,
			final Class<AwsEc2Price> beanType) {
		return new AwsCsvReader<>(reader, headers, beanType) {

			@Override
			protected boolean isValidRaw(final List<String> rawValues) {
				// Only Compute Instance with a valid OS
				// Only compute instance for now
				// Only OS compliant
				// No dedicated host for now
				return rawValues.size() > 38 && "Compute Instance".equals(rawValues.get(14))
						&& StringUtils.isNotEmpty(rawValues.get(37)) && !"NA".equals(rawValues.get(37))
						&& !"Host".equals(rawValues.get(35));
			}

		};
	}

}
