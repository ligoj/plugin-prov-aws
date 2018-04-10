/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.in;

import java.io.IOException;
import java.io.Reader;
import java.util.List;

import org.ligoj.bootstrap.core.csv.CsvBeanReader;
import org.ligoj.bootstrap.core.csv.CsvReader;

/**
 * Read CSV reader skipping the useless rows.
 */
public class AwsCsvReader<T> extends CsvBeanReader<T> {

	/**
	 * CSV raw data reader.
	 */
	private final CsvReader csvReaderProxy;

	/**
	 * Build a CSV reader to build {@link AwsEc2Price} objects.
	 * 
	 * @param reader
	 *            The CSV input, without headers and starting from the first raw.
	 * @param headers
	 *            The header used to parse the CSV file.
	 * @param beanType
	 *            The target bean type.
	 */
	public AwsCsvReader(final Reader reader, final String[] headers, final Class<T> beanType) {
		super(reader, beanType, headers);

		// Makes visible this entry
		this.csvReaderProxy = new CsvReader(reader, ',');
	}

	@Override
	public T read() throws IOException {
		// Read the raw entries to check the build/skip option
		final List<String> rawValues = csvReaderProxy.read();

		// Build only for AWS compute instance
		if (rawValues.isEmpty()) {
			// EOF
			return null;
		}
		if (isValidRaw(rawValues)) {
			return build(rawValues);
		}

		// Skip this entry
		return read();
	}

	/**
	 * Check the given raw is valid to build an AWS Price. When invalid, the record is dropped.
	 * 
	 * @param rawValues
	 *            The column of the current record.
	 * @return <code>true</code> when this record can be used to build a bean.
	 */
	protected boolean isValidRaw(final List<String> rawValues) {
		return true;
	}
}
