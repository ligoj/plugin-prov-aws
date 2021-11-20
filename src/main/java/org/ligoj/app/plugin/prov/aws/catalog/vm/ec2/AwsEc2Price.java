/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.ec2;

import lombok.Getter;
import lombok.Setter;

/**
 * AWS EC2 price configuration
 */
@Getter
@Setter
public class AwsEc2Price extends AbstractAwsVmOsPrice {

	private String software;
	
	/**
	 * API Volume type. Not <code>null</code> for EBS price.
	 */
	private String volume;
	
	private String capacityStatus;
}
