/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.in;

import java.util.Collection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Spot region prices JSON file structure.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class SpotRegion extends AwsRegionPrices {
	private Collection<SpotInstanceType> instanceTypes;

	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class SpotInstanceType {
		private Collection<AwsEc2SpotPrice> sizes;
	}

}
