/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Service price configuration.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AwsPriceOffer {

	/**
	 * AWS all regions in one file URL.
	 */
	private String currentVersionUrl;
	
	/**
	 * Single region index file.
	 */
	private String currentRegionIndexUrl;
	
	/**
	 * Single region index file for savings plan.
	 */
	private String currentSavingsPlanIndexUrl;
	
}
