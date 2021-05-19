/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.lambda;

import org.ligoj.app.plugin.prov.aws.catalog.AwsCsvPrice;

import lombok.Getter;
import lombok.Setter;

/**
 * AWS Lambda price configuration
 */
@Getter
@Setter
public class AwsLambdaPrice extends AwsCsvPrice {

	private String group;
}
