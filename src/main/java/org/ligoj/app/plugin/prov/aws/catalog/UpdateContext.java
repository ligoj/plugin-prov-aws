/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import java.util.HashMap;
import java.util.Map;

import org.ligoj.app.plugin.prov.catalog.AbstractUpdateContext;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Context used to perform catalog update.
 */
@NoArgsConstructor
public class UpdateContext extends AbstractUpdateContext {

	/**
	 * Mapping from storage human name to API name.
	 */
	@Getter
	private Map<String, String> mapStorageToApi = new HashMap<>();

	/**
	 * Mapping from Spot region name to API name.
	 */
	@Getter
	private Map<String, String> mapSpotToNewRegion = new HashMap<>();
}
