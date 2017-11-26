package org.ligoj.app.plugin.prov.aws.in;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AwsRegionPrices {
	private String region;
}
