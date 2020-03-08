/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.ec2;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Savings Plan index.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class SavingsPlanIndex {

	private SavingsPlanUrl[] regions;

	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class SavingsPlanUrl {

		private String regionCode;
		private String versionUrl;

	}

}
