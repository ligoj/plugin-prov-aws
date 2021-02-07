package org.ligoj.app.plugin.prov.aws.catalog.vm.fargate;

import java.util.Map;

import org.ligoj.app.plugin.prov.model.ProvLocation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Fargate spot prices.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class SpotPrice {

	/**
	 * Dimension: <code>vCPU-Hours</code> or <code>GB-Hours</code>.
	 */
	private String unit;

	/**
	 * Currency with price.
	 */
	private Map<String, String> price;

	/**
	 * Includes attribute <code>aws:region</code>.
	 */
	private Map<String, String> attributes;

	/**
	 * Computed region name.
	 */
	private String regionName;
	/**
	 * Resolved region.
	 */
	private ProvLocation region;

}
