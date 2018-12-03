/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.ec2;

import java.util.Collection;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

/**
 * AWS price configuration of several OS.
 */
@Getter
@Setter
public class AwsEc2SpotPrice {

	/**
	 * Prices for each OS.
	 */
	@JsonProperty("valueColumns")
	private Collection<AwsEc2SpotOsPrice> osPrices;

	/**
	 * Instance name.
	 */
	@JsonProperty("size")
	private String name;
}
