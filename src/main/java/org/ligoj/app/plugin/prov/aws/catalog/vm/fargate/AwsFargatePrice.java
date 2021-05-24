/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.fargate;

import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AbstractAwsVmOsPrice;

import lombok.Getter;
import lombok.Setter;

/**
 * AWS Fargate price configuration
 */
@Getter
@Setter
public class AwsFargatePrice extends AbstractAwsVmOsPrice {

	private String usageType;
	
	private double ramGb;

}
