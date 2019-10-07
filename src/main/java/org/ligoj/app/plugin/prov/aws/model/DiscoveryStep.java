/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.model;

/**
 * Discovery import task.
 */
public enum DiscoveryStep {

	/**
	 * Requesting data export from Discovery Service
	 */
	CLI_EXPORT,
	
	/**
	 * Waiting for the export available
	 */
	CLI_WAITING_EXPORT,
	
	/**
	 * Downloading the Discovery export
	 */
	CLI_DOWLOADING_EXPORT,

	/**
	 * Cleaning the target Athena zone in S3.
	 */
	ATHENA_CLEANING,
	
	/**
	 * Importing the fresh Athena data to S3.
	 */
	ATHENA_IMPORTING_DATA,
	
	/**
	 * Exporting the CSV export.
	 */
	ATHENA_EXPORTING_SPEC,
	
	/**
	 * Exporting the CSV export.
	 */
	ATHENA_EXPORTING_NETWORK;
	
}
