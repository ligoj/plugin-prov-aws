package org.ligoj.app.plugin.prov.aws.in;

import java.util.Collection;
import java.util.Map;

import org.ligoj.bootstrap.core.INamableBean;
import org.ligoj.bootstrap.core.NamedBean;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

/**
 * S3 region prices JSON file structure.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class S3Region extends AwsRegionPrices {
	private Collection<S3Type> tiers;

	/**
	 * S3 types and related prices.
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class S3Type extends NamedBean<Integer> {
		private Collection<S3Value> storageTypes;
	}

	/**
	 * S3 prices
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class S3Value extends AwsPrice implements INamableBean<Integer> {

		@JsonProperty("type")
		private String name;

		/**
		 * Prices where key is the currency
		 */
		private Map<String, String> prices;
	}

}
