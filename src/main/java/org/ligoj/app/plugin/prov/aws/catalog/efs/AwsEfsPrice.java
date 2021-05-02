/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.efs;

import org.ligoj.app.plugin.prov.aws.catalog.AwsCsvPrice;

import lombok.Getter;
import lombok.Setter;

/**
 * AWS EFS price configuration
 */
@Getter
@Setter
public class AwsEfsPrice extends AwsCsvPrice {

	private String storageClass;
}
