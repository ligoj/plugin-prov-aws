/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import java.util.Collection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * AWS prices JSON file structure.
 * 
 * @param <T> The region pricing container type.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AwsPrices<T extends AwsRegionPrices> {

	private StorageConfig<T> config;

	/**
	 * The JSON storage configuration.
	 *
	 * @param <T> The region pricing type.
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class StorageConfig<T> {
		private Collection<T> regions;
	}
}
