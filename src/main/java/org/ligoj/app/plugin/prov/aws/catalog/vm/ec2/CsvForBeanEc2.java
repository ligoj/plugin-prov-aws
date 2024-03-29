/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.ec2;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read AWS EC2 CSV input, skipping the AWS headers and non instance type rows.
 */
public class CsvForBeanEc2 extends AbstractCsvForBeanEc2<AwsEc2Price> {

	/**
	 * EC2 CSV Mapping to Java bean property
	 */
	private static final Map<String, String> HEADERS_MAPPING = new HashMap<>();
	static {
		HEADERS_MAPPING.put("Tenancy", "tenancy");
		HEADERS_MAPPING.put("Operating System", "os");
		HEADERS_MAPPING.put("Pre Installed S/W", "software");
		HEADERS_MAPPING.put("Storage", "storage");
		HEADERS_MAPPING.put("Volume API Name", "volume");
		HEADERS_MAPPING.put("CapacityStatus", "capacityStatus");
		HEADERS_MAPPING.put("Capacity Status", "capacityStatus");
	}

	private static final Set<String> ACCEPTED_FAMILY = Set.of("Provisioned Throughput", "Storage", "System Operation",
			"Storage Snapshot", "Compute Instance", "Compute Instance (bare metal)");

	/**
	 * Build the reader parsing the CSV file from AWS to build {@link AwsEc2Price} instances. Non AWS instances data are
	 * skipped, and headers are ignored.
	 *
	 * @param reader The original AWS CSV input.
	 * @throws IOException When CSV content cannot be read.
	 */
	public CsvForBeanEc2(final BufferedReader reader) throws IOException {
		super(reader, HEADERS_MAPPING, AwsEc2Price.class);
	}

	@Override
	public boolean isValidRaw(final List<String> rawValues) {
		// Only Compute instance [bare metal] for now
		// Only Tenancy compliant : no "host"
		// No outpost
		return rawValues.size() > 49
				&& ("AWS Region".equals(rawValues.get(17))
						|| "AWS Region".equals(rawValues.get(18)) /* Location Type: No outpost */)
				&& ACCEPTED_FAMILY.contains(rawValues.get(15)) /* Product Family */
				&& !"Host".equals(rawValues.get(36)) /* No dedicated host, new & old placement */;
	}

}
