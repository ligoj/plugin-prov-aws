/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import java.util.Collection;

import org.ligoj.bootstrap.core.NamedBean;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * EBS region prices JSON file structure.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class EbsRegion extends AwsRegionPrices {
	
	/**
	 * EBS types and corresponding prices
	 */
	private Collection<EbsType> types;

	/**
	 * EBS prices
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class EbsType extends NamedBean<Integer> {

		/**
		 * SID
		 */
		private static final long serialVersionUID = 1L;
		private transient Collection<EbsValue> values;
	}

	/**
	 * EBS price
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class EbsValue extends AwsPrice {
		private String rate;
	}
}
