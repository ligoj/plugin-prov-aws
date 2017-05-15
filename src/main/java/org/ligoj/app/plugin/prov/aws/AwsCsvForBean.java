package org.ligoj.app.plugin.prov.aws;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.List;

import org.ligoj.bootstrap.core.csv.AbstractCsvManager;
import org.ligoj.bootstrap.core.csv.CsvBeanReader;

/**
 * Read AWS CSV input, skipping the AWS headers and non instance type rows.
 */
public class AwsCsvForBean extends AbstractCsvManager {

	private final CsvBeanReader<AwsInstancePrice> beanReader;

	/**
	 * Build the reader parsing the CSV file from AWS to build {@link AwsInstancePrice}
	 * instances. Non AWS instances data are skipped, and headers are ignored.
	 * 
	 * @param reader
	 *            The original AWS CSV input.
	 */
	public AwsCsvForBean(final BufferedReader reader) throws IOException {
		this.beanReader = new AwsCsvReader(reader);

		// Skip until the header, to be skipped too
		String line;
		do {
			line = reader.readLine();
		} while (line != null && !line.startsWith("\"SKU\""));
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
