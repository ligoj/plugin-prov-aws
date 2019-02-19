/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.vm.ec2;

import java.util.Map;

import javax.persistence.Transient;

import org.ligoj.app.plugin.prov.model.VmOs;

import lombok.Getter;
import lombok.Setter;

/**
 * AWS price configuration for a specific OS.
 */
@Getter
@Setter
public class AwsEc2SpotOsPrice {

	/**
	 * OS identifier.
	 */
	private String name;

	/**
	 * Resolved OS identifier.
	 */
	@Transient
	private VmOs os;

	/**
	 * Prices where key is the currency
	 */
	private Map<String, String> prices;
}
