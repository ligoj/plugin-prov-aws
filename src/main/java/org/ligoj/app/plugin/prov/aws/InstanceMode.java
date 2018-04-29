/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws;

/**
 * AWS Instance type depending on the requirements
 */
public enum InstanceMode {
	/**
	 * Single EC2
	 */
	EC2,

	/**
	 * Auto Scaling, combined with ALB/SPOT.
	 */
	AUTO_SCALING,

	/**
	 * EC2spot request
	 */
	SPOT
}
