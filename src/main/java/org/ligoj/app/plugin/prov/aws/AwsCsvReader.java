package org.ligoj.app.plugin.prov.aws;

import java.io.IOException;
import java.io.Reader;
import java.util.List;

import org.ligoj.bootstrap.core.csv.CsvBeanReader;
import org.ligoj.bootstrap.core.csv.CsvReader;

/**
 * Read CSV reader skipping the useless rows.
 */
public class AwsCsvReader extends CsvBeanReader<AwsInstancePrice> {

	private static final String AWS_EC2_HEADERS = "drop;offerTermCode;drop;termType;drop;drop;drop;drop;drop;pricePerUnit;drop;leaseContractLength;"
			+ "purchaseOption;offeringClass;drop;drop;drop;drop;instanceType;drop;drop;cpu;physicalProcessor;clockSpeed;memory;"
			+ "drop;drop;drop;drop;drop;drop;drop;drop;drop;drop;tenancy;drop;os;licenseModel;"
			+ "drop;drop;drop;drop;drop;drop;drop;drop;drop"
			+ ";drop;drop;ecu";

	/**
	 * CSV raw data reader.
	 */
	private final CsvReader csvReaderProxy;

	/**
	 * Build a CSV reader to build {@link AwsInstancePrice} objects.
	 * 
	 * @param reader
	 *            The CSV input, without headers and starting from the first
	 *            raw.
	 */
	public AwsCsvReader(Reader reader) {
		super(reader, AwsInstancePrice.class, AWS_EC2_HEADERS);

		// Makes visible this entry
		// TODO Remove with LB 1.6.3+
		this.csvReaderProxy = new CsvReader(reader);
	}

	@Override
	public AwsInstancePrice read() throws IOException {
		// Read the raw entries to check the build/skip option
		final List<String> rawValues = csvReaderProxy.read();

		// Build only for AWS compute instance
		if (rawValues.isEmpty() || isValidRaw(rawValues)) {
			return build(rawValues);
		}

		// Skip this entry
		return read();
	}

	/**
	 * Check the given raw is valid to build an AWS Price.
	 */
	private boolean isValidRaw(final List<String> rawValues) {
		// Only Compute Instance with a valid OS
		return rawValues.size() > 30 && "Compute Instance".equals(rawValues.get(14)) && rawValues.get(28) != null
				&& "NA".equals(rawValues.get(28));
	}
}