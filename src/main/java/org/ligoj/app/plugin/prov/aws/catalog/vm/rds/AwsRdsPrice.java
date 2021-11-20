/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.rds;

import org.ligoj.app.plugin.prov.aws.catalog.vm.AbstractAwsVmPrice;

import lombok.Getter;
import lombok.Setter;

/**
 * AWS RDS price configuration
 */
@Getter
@Setter
public class AwsRdsPrice extends AbstractAwsVmPrice {

	private String engine;
	private String edition;

	private String sizeMin;
	private String sizeMax;
	private String volume;
	
	/**
	 * Optional volume name reference.
	 */
	private String volumeName;

}
