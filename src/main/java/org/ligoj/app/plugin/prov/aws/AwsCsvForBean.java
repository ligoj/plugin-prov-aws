package org.ligoj.app.plugin.prov.aws;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ligoj.bootstrap.core.csv.AbstractCsvManager;
import org.ligoj.bootstrap.core.csv.CsvBeanReader;
import org.ligoj.bootstrap.core.csv.CsvReader;
import org.ligoj.bootstrap.core.resource.TechnicalException;

/**
 * Read AWS CSV input, skipping the AWS headers and non instance type rows.
 */
public class AwsCsvForBean extends AbstractCsvManager {

	/**
	 * EC3 CSV Mapping to Java bean property
	 */
	private static final Map<String, String> AWS_EC2_HEADERS_MAPPING = new HashMap<>();
	static {
		AWS_EC2_HEADERS_MAPPING.put("SKU", "sku");
		AWS_EC2_HEADERS_MAPPING.put("OfferTermCode", "offerTermCode");
		AWS_EC2_HEADERS_MAPPING.put("TermType", "termType");
		AWS_EC2_HEADERS_MAPPING.put("Unit", "priceUnit");
		AWS_EC2_HEADERS_MAPPING.put("PricePerUnit", "pricePerUnit");
		AWS_EC2_HEADERS_MAPPING.put("LeaseContractLength", "leaseContractLength");
		AWS_EC2_HEADERS_MAPPING.put("PurchaseOption", "purchaseOption");
		AWS_EC2_HEADERS_MAPPING.put("OfferingClass", "offeringClass");
		AWS_EC2_HEADERS_MAPPING.put("Instance Type", "instanceType");
		AWS_EC2_HEADERS_MAPPING.put("vCPU", "cpu");
		AWS_EC2_HEADERS_MAPPING.put("Physical Processor", "physicalProcessor");
		AWS_EC2_HEADERS_MAPPING.put("Clock Speed", "clockSpeed");
		AWS_EC2_HEADERS_MAPPING.put("Memory", "memory");
		AWS_EC2_HEADERS_MAPPING.put("Tenancy", "tenancy");
		AWS_EC2_HEADERS_MAPPING.put("Operating System", "os");
		AWS_EC2_HEADERS_MAPPING.put("License Model", "licenseModel");
		AWS_EC2_HEADERS_MAPPING.put("ECU", "ecu");
		AWS_EC2_HEADERS_MAPPING.put("Pre Installed S/W", "software");
	}

	private final CsvBeanReader<AwsInstancePrice> beanReader;

	/**
	 * Build the reader parsing the CSV file from AWS to build
	 * {@link AwsInstancePrice} instances. Non AWS instances data are skipped,
	 * and headers are ignored.
	 * 
	 * @param reader
	 *            The original AWS CSV input.
	 */
	public AwsCsvForBean(final BufferedReader reader) throws IOException {
		final CsvReader csvReader = new CsvReader(reader, ',');

		// Skip until the header, to be skipped too
		List<String> values;
		do {
			values = csvReader.read();
			if (values.isEmpty()) {
				throw new TechnicalException("Premature end of CSV file, headers were not found");
			}
			if (values.get(0).equals("SKU")) {
				// The real CSV header has be reached
				this.beanReader = new AwsCsvReader(reader,
						values.stream().map(v -> AWS_EC2_HEADERS_MAPPING.getOrDefault(v, "drop")).toArray(String[]::new));
				break;
			}
		} while (true);
	}

	/**
	 * Do not use this, method.
	 * 
	 * @see #read() instead
	 */
	@Override
	public <T> List<T> toBean(final Class<T> beanType, final Reader input) throws IOException {
		// Disable this method,
		return null;
	}

	/**
	 * Return a list of JPA bean re ad from the given CSV input. Headers are
	 * expected.
	 */
	public AwsInstancePrice read() throws IOException {
		return beanReader.read();
	}

}
