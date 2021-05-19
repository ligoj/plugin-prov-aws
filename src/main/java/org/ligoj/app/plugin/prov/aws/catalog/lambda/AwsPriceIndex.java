/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.lambda;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Root AWS bulk prices.
 * 
 * @see {@link https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/comprehend/current/index.json}
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AwsPriceIndex {

	/**
	 * All services.
	 */
	private Map<String, AwsPriceOffer> offers;

}
