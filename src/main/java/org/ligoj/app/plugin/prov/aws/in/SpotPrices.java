package org.ligoj.app.plugin.prov.aws.in;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Spot prices JSON file structure.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class SpotPrices extends AwsPrices<SpotRegion> {
	// Only for typing
}
