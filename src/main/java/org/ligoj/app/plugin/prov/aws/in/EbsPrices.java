package org.ligoj.app.plugin.prov.aws.in;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * EBS prices JSON file structure.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class EbsPrices extends AwsPrices<EbsRegion> {

	// Only for typing
}
