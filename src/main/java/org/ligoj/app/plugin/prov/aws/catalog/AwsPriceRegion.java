/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Service regional price configuration.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AwsPriceRegion {

	/**
	 * Region code.
	 */
	private String regionCode;

	/**
	 * Price URL of the selected region.
	 */
	@JsonAlias({"currentVersionUrl", "versionUrl"})
	private String url;

}
