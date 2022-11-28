/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AwsEc2Price;
import org.ligoj.bootstrap.core.csv.AbstractCsvManager;
import org.ligoj.bootstrap.core.csv.CsvBeanReader;
import org.ligoj.bootstrap.core.csv.CsvReader;
import org.ligoj.bootstrap.core.resource.TechnicalException;

/**
 * Read AWS CSV input, skipping the AWS headers and non instance type rows.
 *
 * @param <T> The target bean type.
 */
public abstract class AbstractAwsCsvForBean<T> extends AbstractCsvManager {

	private final CsvBeanReader<T> beanReader;

	/**
	 * Standard CSV Mapping to Java bean property
	 */
	private static final Map<String, String> HEADERS_MAPPING = new HashMap<>();
	static {
		HEADERS_MAPPING.put("SKU", "sku");
		HEADERS_MAPPING.put("RateCode", "rateCode");
		HEADERS_MAPPING.put("OfferTermCode", "offerTermCode");
		HEADERS_MAPPING.put("TermType", "termType");
		HEADERS_MAPPING.put("PricePerUnit", "pricePerUnit");
		HEADERS_MAPPING.put("Location", "location");
	}

	/**
	 * Build the reader parsing the CSV file from AWS to build {@link AwsEc2Price} instances. Non AWS instances data are
	 * skipped, and headers are ignored.
	 *
	 * @param reader    The original AWS CSV input.
	 * @param mapping   The mapping table for header.
	 * @param beanType  The target bean type.
	 * @throws IOException When the CSV content cannot be read.
	 */
	protected AbstractAwsCsvForBean(final BufferedReader reader, final Map<String, String> mapping,
			final Class<T> beanType) throws IOException {
		this(reader, mapping, beanType, ',');
	}

	/**
	 * Build the reader parsing the CSV file from AWS to build {@link AwsEc2Price} instances. Non AWS instances data are
	 * skipped, and headers are ignored.
	 *
	 * @param reader   The original AWS CSV input.
	 * @param mapping  The mapping table for header.
	 * @param beanType The target bean type.
	 * @throws IOException When the CSV content cannot be read.
	 */
	protected AbstractAwsCsvForBean(final BufferedReader reader, final Map<String, String> mapping,
			final Class<T> beanType, final char separator) throws IOException {

		// Complete the standard mappings
		final var mMapping = new HashMap<>(HEADERS_MAPPING);
		mMapping.putAll(mapping);

		final var csvReader = new CsvReader(reader, separator);

		// Skip until the header, to be skipped too
		List<String> values;
		do {
			values = csvReader.read();
			if (values.isEmpty()) {
				throw new TechnicalException("Premature end of CSV file, headers were not found");
			}
			if (isHeaderRow(values)) {
				// The real CSV header has be reached
				this.beanReader = newCsvReader(reader,
						values.stream().map(v -> mMapping.getOrDefault(v, "drop")).toArray(String[]::new), beanType);
				break;
			}
		} while (true);
	}

	/**
	 * Return <code>true</code> when the given values correspond to the headers row. This function is called until it
	 * returns <code>true</code>.
	 * 
	 * @param values The split row values according to the CSV separator.
	 * @return <code>true</code> when the given values correspond to the headers row.
	 */
	protected boolean isHeaderRow(final List<String> values) {
		return values.get(0).equals("SKU");
	}

	protected abstract CsvBeanReader<T> newCsvReader(final Reader reader, final String[] headers,
			final Class<T> beanType);

	/**
	 * Return a list of JPA bean re ad from the given CSV input. Headers are expected.
	 *
	 * @return The bean read from the next CSV record.
	 * @throws IOException When the CSV record cannot be read.
	 */
	public T read() throws IOException {
		return beanReader.read();
	}

}
