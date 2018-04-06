package org.ligoj.app.plugin.prov.aws.in;

import lombok.Getter;
import lombok.Setter;

/**
 * AWS S3 price configuration
 */
@Getter
@Setter
public class AwsS3Price extends AwsCsvPrice {

	private String availability;
	private String volumeType;
	private String durability;
	private String storageClass;
}
