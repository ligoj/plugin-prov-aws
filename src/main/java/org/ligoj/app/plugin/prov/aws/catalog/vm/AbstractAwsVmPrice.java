/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm;

import org.ligoj.app.plugin.prov.aws.catalog.AwsCsvPrice;

import lombok.Getter;
import lombok.Setter;

/**
 * Abstract AWS EC2 price configuration
 */
@Getter
@Setter
public abstract class AbstractAwsVmPrice extends AwsCsvPrice {

	private String offeringClass;
	private String instanceType;
	private double cpu;
	private double gpu;
	private String physicalProcessor;
	private String memory;
	private String tenancy;
	private String priceUnit;
	private String licenseModel;
	private String networkPerformance;
	private String currentGeneration;
	private String family;
	private String storage;
}
