package org.ligoj.app.plugin.prov.aws.in;

import java.util.Map;

import lombok.Getter;
import lombok.Setter;

/**
 * AWS price configuration for a specific OS.
 */
@Getter
@Setter
public class AwsInstanceSpotOsPrice {

	/**
	 * OS identifier.
	 */
	private String name;
	
	/**
	 * Prices where key is the currency
	 */
	private Map<String, String> prices;
}
