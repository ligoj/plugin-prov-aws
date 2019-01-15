/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.ec2;

import lombok.Getter;
import lombok.Setter;

/**
 * AWS EC2 price configuration
 */
@Getter
@Setter
public class AwsEc2Price extends AbstractAwsEc2Price {

	private String os;
	private String software;
	private String ebsOptimized;
	private String capacityStatus;
}
