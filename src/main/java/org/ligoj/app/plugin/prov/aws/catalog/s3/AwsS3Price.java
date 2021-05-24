/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.s3;

import org.ligoj.app.plugin.prov.aws.catalog.AbstractAwsStoragePrice;

import lombok.Getter;
import lombok.Setter;

/**
 * AWS S3 price configuration
 */
@Getter
@Setter
public class AwsS3Price extends AbstractAwsStoragePrice {

	private String availability;
	private String durability;
}
