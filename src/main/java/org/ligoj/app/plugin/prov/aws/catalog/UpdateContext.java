/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.ligoj.app.model.Node;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AwsEc2Price;
import org.ligoj.app.plugin.prov.aws.catalog.vm.rds.AwsRdsPrice;
import org.ligoj.app.plugin.prov.model.ProvDatabasePrice;
import org.ligoj.app.plugin.prov.model.ProvDatabaseType;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvInstanceType;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.ligoj.app.plugin.prov.model.ProvStorageType;

import lombok.Getter;
import lombok.Setter;

/**
 * Context used to perform catalog update.
 */
public class UpdateContext {
	/**
	 * The related AWS {@link Node}
	 */
	@Getter
	@Setter
	private Node node;

	/**
	 * Mapping from API region identifier to region name.
	 */
	@Getter
	private Map<String, ProvLocation> mapRegionToName = new HashMap<>();

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
	 * The previously installed instance types. Key is the instance name.
	 */
	@Getter
	@Setter
	private Map<String, ProvInstanceType> instanceTypes;

	/**
	 * The previously installed database types. Key is the instance name.
	 */
	@Getter
	@Setter
	private Map<String, ProvDatabaseType> databaseTypes;

	/**
	 * The already merge instance types.
	 */
	@Getter
	private Set<String> instanceTypesMerged = new HashSet<>();

	/**
	 * The previously installed price terms.
	 */
	@Getter
	@Setter
	private Map<String, ProvInstancePriceTerm> priceTerms;

	/**
	 * The previous installed EC2 prices.
	 */
	@Getter
	@Setter
	private Map<String, ProvInstancePrice> previous;

	/**
	 * The previous installed Database prices.
	 */
	@Getter
	@Setter
	private Map<String, ProvDatabasePrice> previousDatabase;

	/**
	 * The previous installed storage prices.
	 */
	@Getter
	@Setter
	private Map<String, ProvStoragePrice> previousStorage;

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
	 * The available regions.
	 */
	@Getter
	@Setter
	private Map<String, ProvLocation> regions;

	/**
	 * The available merged regions.
	 */
	@Getter
	private Set<String> regionsMerged = new HashSet<>();

	/**
	 * The accepted and existing storage type.
	 */
	@Getter
	@Setter
	private Map<String, ProvStorageType> storageTypes;

	/**
	 * The merged storage type.
	 */
	@Getter
	@Setter
	private Map<String, ProvStorageType> storageTypesMerged;

	/**
	 * Valid OS pattern.
	 */
	@Getter
	@Setter
	private Pattern validOs;

	/**
	 * Valid instance type pattern.
	 */
	@Getter
	@Setter
	private Pattern validInstanceType;

	/**
	 * Valid instance region pattern.
	 */
	@Getter
	@Setter
	private Pattern validRegion;

}
