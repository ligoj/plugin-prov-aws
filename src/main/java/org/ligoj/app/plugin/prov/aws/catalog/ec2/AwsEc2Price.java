/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.ec2;

import org.ligoj.app.plugin.prov.aws.catalog.AwsCsvPrice;

import lombok.Getter;
import lombok.Setter;

/**
 * AWS EC2 price configuration
 */
@Getter
@Setter
public class AwsEc2Price extends AwsCsvPrice {

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
	private String licenseModel;
	private String software;
	private String networkPerformance;
	private String ebsOptimized;
	private String currentGeneration;
	private String capacityStatus;
}
