package org.ligoj.app.plugin.prov.aws.in;

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

	/**
	 * Related region.
	 */
	private String location;
}
