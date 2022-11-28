/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Setter;

/**
 * Service regional price configuration.
 *
 * @see <a href="https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AWSLambda/current/region_index.json">Sample"></a>
 * @see <a href="https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AWSLambda/20210304163809/af-south-1/index.json">Sample</a>
 * @see <a href="https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AWSLambda/20210304163809/af-south-1/index.csv">Sample</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AwsSPPriceRegions implements RegionalPrices {

	/**
	 * AWS service code.
	 */
	@Setter
	private List<AwsPriceRegion> regions;

	@Override
	public Map<String, AwsPriceRegion> getPRegions() {
		return regions.stream().collect(Collectors.toMap(AwsPriceRegion::getRegionCode, Function.identity()));
	}

}
