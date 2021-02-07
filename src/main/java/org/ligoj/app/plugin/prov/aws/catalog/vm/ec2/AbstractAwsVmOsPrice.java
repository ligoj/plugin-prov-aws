/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.ec2;

import org.ligoj.app.plugin.prov.aws.catalog.vm.AbstractAwsVmPrice;

import lombok.Getter;
import lombok.Setter;

/**
 * Abstract AWS EC2 price configuration
 */
@Getter
@Setter
public abstract class AbstractAwsVmOsPrice extends AbstractAwsVmPrice {

	private String os;

}
