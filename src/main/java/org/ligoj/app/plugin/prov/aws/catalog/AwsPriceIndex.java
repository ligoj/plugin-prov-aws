/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Root AWS bulk prices.
 *
 * @see <a href="https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/comprehend/current/index.json">Sample</a>
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
