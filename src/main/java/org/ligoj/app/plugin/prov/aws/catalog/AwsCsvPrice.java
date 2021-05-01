/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import lombok.Getter;
import lombok.Setter;

/**
 * AWS price configuration for CSV file.
 */
@Getter
@Setter
public class AwsCsvPrice {

	/**
	 * Ignored property
	 */
	private String drop;

	private String offerTermCode;
	private String termType;
	private double pricePerUnit;
	private String sku;
	private String rateCode;
	private String leaseContractLength;
	private String purchaseOption;

	/**
	 * Related region.
	 */
	private String location;
}
