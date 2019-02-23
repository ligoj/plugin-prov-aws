/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import java.util.HashMap;
import java.util.Map;

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

}
