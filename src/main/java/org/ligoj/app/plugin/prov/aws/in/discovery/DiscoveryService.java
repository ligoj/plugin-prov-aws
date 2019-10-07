/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.in.discovery;

import java.util.Map;
import java.util.function.Consumer;

import javax.transaction.Transactional;

import org.ligoj.app.model.Node;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.prov.aws.model.DiscoveryStatus;
import org.ligoj.app.plugin.prov.aws.model.DiscoveryStep;
import org.springframework.stereotype.Component;

/**
 * Discovery service.
 */
@Component
@Transactional
public class DiscoveryService {

	private DiscoveryRunnerResource resource;

	/**
	 * Update the current phase for statistics and add 1 to the processed workload.
	 *
	 * @param node  The current import node.
	 * @param phase The new import phase.
	 */
	protected void nextStep(final Node node, final DiscoveryStep step, final Consumer<DiscoveryStatus> updater) {
		resource.nextStep(node.getId(), t -> {
			t.setProgress(step.ordinal() * 100 / DiscoveryStep.values().length);
			t.setStep(step);
			updater.accept(t);
		});
	}

	public void refresh(final Subscription entity, final Map<String, String> parameters) {
		final var node = entity.getNode();
		/**
		 * Requesting data export from Discovery Service
		 */
		nextStep(node, DiscoveryStep.CLI_EXPORT, t -> {
		});

		/**
		 * Waiting for the export available
		 */
		nextStep(node, DiscoveryStep.CLI_WAITING_EXPORT, t -> {
		});

		/**
		 * Downloading the Discovery export
		 */
		nextStep(node, DiscoveryStep.CLI_DOWLOADING_EXPORT, t -> {
		});

		/**
		 * Cleaning the target Athena zone in S3.
		 */
		nextStep(node, DiscoveryStep.ATHENA_CLEANING, t -> {
		});

		/**
		 * Importing the fresh Athena data to S3.
		 */
		nextStep(node, DiscoveryStep.ATHENA_IMPORTING_DATA, t -> {
		});

		/**
		 * Exporting the CSV export.
		 */
		nextStep(node, DiscoveryStep.ATHENA_EXPORTING_SPEC, t -> {
		});

		/**
		 * Exporting the CSV export.
		 */
		nextStep(node, DiscoveryStep.ATHENA_EXPORTING_NETWORK, t -> {
		});

	}

}
