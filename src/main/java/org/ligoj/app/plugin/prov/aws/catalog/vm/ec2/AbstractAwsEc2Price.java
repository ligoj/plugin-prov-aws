/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.ec2;

import org.ligoj.app.plugin.prov.aws.catalog.AwsCsvPrice;

import lombok.Getter;
import lombok.Setter;

/**
 * Abstract AWS EC2 price configuration
 */
@Getter
@Setter
public abstract class AbstractAwsEc2Price extends AwsCsvPrice {

	private String leaseContractLength;
	private String purchaseOption;
	private String offeringClass;
	private String instanceType;
	private double cpu;
	private String physicalProcessor;
	private String clockSpeed;
	private String memory;
	private String tenancy;
	private String priceUnit;
	private String licenseModel;
	private String networkPerformance;
	private String currentGeneration;
	private String family;
	private String storage;
}
