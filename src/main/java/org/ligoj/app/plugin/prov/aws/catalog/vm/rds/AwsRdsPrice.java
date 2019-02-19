/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.rds;

import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AbstractAwsEc2Price;

import lombok.Getter;
import lombok.Setter;

/**
 * AWS EC2 price configuration
 */
@Getter
@Setter
public class AwsRdsPrice extends AbstractAwsEc2Price {

	private String engine;
	private String edition;

	private String sizeMin;
	private String sizeMax;
	private String volume;

}
