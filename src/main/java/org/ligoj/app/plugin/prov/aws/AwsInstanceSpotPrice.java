package org.ligoj.app.plugin.prov.aws;

import java.util.Collection;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

/**
 * AWS price configuration of several OS.
 */
@Getter
@Setter
public class AwsInstanceSpotPrice {

	/**
	 * Prices for each OS.
	 */
	@JsonProperty("valueColumns")
	private Collection<AwsInstanceSpotOsPrice> osPrices;

	/**
	 * Instance name.
	 */
	@JsonProperty("size")
	private String name;
}