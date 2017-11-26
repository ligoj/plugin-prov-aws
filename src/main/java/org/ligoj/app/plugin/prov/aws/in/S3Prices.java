package org.ligoj.app.plugin.prov.aws.in;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * S3 prices JSON file structure.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class S3Prices extends AwsPrices<S3Region> {

	// Only for typing
}
