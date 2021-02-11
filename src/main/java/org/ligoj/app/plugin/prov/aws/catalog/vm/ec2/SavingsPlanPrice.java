/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.ec2;

import java.util.Collection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Savings Plan price.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class SavingsPlanPrice {

	private SavingsPlanTerms terms;

	/**
	 * Term definition
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class SavingsPlanTerms {
		private Collection<SavingsPlanTerm> savingsPlan;
	}

	/**
	 * Rates
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class SavingsPlanTerm {
		private String description;
		private String sku;
		private SavingsPlanLease leaseContractLength;
		private Collection<SavingsPlanRate> rates;
	}
	
	/**
	 * Lease duration.
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class SavingsPlanLease {
		private int duration;
	}

	/**
	 * Rate details.
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class SavingsPlanRate {
		private String discountedSku;
		private String discountedUsageType;
		private String rateCode;
		private SavingsPlanDRate discountedRate;
	}

	/**
	 * Actual USD price.
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class SavingsPlanDRate {
		private double price;
	}

}
