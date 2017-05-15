package org.ligoj.app.plugin.prov.aws;

import lombok.Getter;
import lombok.Setter;

/**
 * AWS price configuration
 */
@Getter
@Setter
public class AwsInstancePrice {

	/**
	 * Ignored property
	 */
	private String drop;
	private String offerTermCode;
	private String termType;
	private double pricePerUnit;
	private String leaseContractLength;
	private String purchaseOption;
	private String offeringClass;
	private String instanceType;
	private double cpu;
	private String physicalProcessor;
	private String clockSpeed;
	private String memory;
	private String tenancy;
	private String os;
	private String ecu;
	private String priceUnit;
	private String sku;
	private String licenseModel;
	private String software;
}
