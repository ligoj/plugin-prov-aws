/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AwsEc2Price;
import org.ligoj.app.plugin.prov.aws.catalog.vm.rds.AwsRdsPrice;
import org.ligoj.app.plugin.prov.catalog.AbstractUpdateContext;

import lombok.Getter;
import lombok.Setter;

/**
 * Context used to perform catalog update.
 */
public class UpdateContext extends AbstractUpdateContext {

	/**
	 * Mapping from storage human name to API name.
	 */
	@Getter
	private Map<String, String> mapStorageToApi = new HashMap<>();

	/**
	 * Mapping from Spot region name to API name.
	 */
	@Getter
	private Map<String, String> mapSpotToNewRegion = new HashMap<>();

	/**
	 * The current partial cost for up-front options.
	 */
	@Getter
	@Setter
	private Map<String, AwsEc2Price> partialCost;

	@Getter
	@Setter
	private Map<String, AwsRdsPrice> partialCostRds;

	/**
	 * List of SKU codes to keep after the update. Will be used to purge deleted and unused codes.
	 */
	@Getter
	private Set<String> actualCodes = new HashSet<>();
}
