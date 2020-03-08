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
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.ligoj.app.plugin.prov.aws.catalog.AbstractAwsCsvForBean;
import org.ligoj.app.plugin.prov.aws.catalog.AbstractAwsCsvReader;
import org.ligoj.bootstrap.core.csv.CsvBeanReader;

/**
 * Read AWS EC2 CSV input, skipping the AWS headers and non instance type rows.
 * 
 * @param <P> Target EC2 price type.
 */
public abstract class AbstractCsvForBeanEc2<P extends AbstractAwsEc2Price> extends AbstractAwsCsvForBean<P> {

	/**
	 * EC2 CSV Mapping to Java bean property
	 */
	protected static final Map<String, String> HEADERS_MAPPING = new HashMap<>();
	static {
		HEADERS_MAPPING.put("Unit", "priceUnit");
		HEADERS_MAPPING.put("LeaseContractLength", "leaseContractLength");
		HEADERS_MAPPING.put("PurchaseOption", "purchaseOption");
		HEADERS_MAPPING.put("OfferingClass", "offeringClass");
		HEADERS_MAPPING.put("Instance Type", "instanceType");
		HEADERS_MAPPING.put("vCPU", "cpu");
		HEADERS_MAPPING.put("Physical Processor", "physicalProcessor");
		HEADERS_MAPPING.put("Memory", "memory");
		HEADERS_MAPPING.put("License Model", "licenseModel");
		HEADERS_MAPPING.put("Network Performance", "networkPerformance");
		HEADERS_MAPPING.put("Current Generation", "currentGeneration");
		HEADERS_MAPPING.put("Product Family", "family");
	}

	/**
	 * Build the reader parsing the CSV file from AWS to build {@link AwsEc2Price}
	 * instances. Non AWS instances data are skipped, and headers are ignored.
	 *
	 * @param reader  The original AWS CSV input.
	 * @param mapping Additional header mapping.
	 * @param type    Target EC2 price class.
	 * @throws IOException When CSV content cannot be read.
	 */
	public AbstractCsvForBeanEc2(final BufferedReader reader, Map<String, String> mapping, final Class<P> type)
			throws IOException {
		super(reader, Stream.of(HEADERS_MAPPING, mapping).flatMap(m -> m.entrySet().stream())
				.collect(Collectors.toMap(Entry::getKey, Entry::getValue)), type);
	}

	@Override
	protected CsvBeanReader<P> newCsvReader(final Reader reader, final String[] headers, final Class<P> beanType) {
		return new AbstractAwsCsvReader<>(reader, headers, beanType) {

			@Override
			protected boolean isValidRaw(final List<String> rawValues) {
				return AbstractCsvForBeanEc2.this.isValidRaw(rawValues);
			}

		};
	}

	/**
	 * Validate a CSV raw
	 *
	 * @param rawValues The row raw values.
	 * @return <code>true</code> when this row is accepted.
	 */
	public abstract boolean isValidRaw(final List<String> rawValues);

}
