package org.ligoj.app.plugin.prov.aws.in;

import java.util.Map;

import org.ligoj.bootstrap.core.model.AbstractPersistable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * AWS price holder JSON file structure.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AwsPrice extends AbstractPersistable<Integer> {

	/**
	 * Prices where key is the currency
	 */
	private Map<String, String> prices;
}
