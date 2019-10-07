/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.in.discovery;

import java.util.concurrent.Executors;
import java.util.function.Supplier;

import javax.transaction.Transactional;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.ligoj.app.dao.NodeRepository;
import org.ligoj.app.dao.SubscriptionRepository;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.aws.dao.DiscoveryStatusRepository;
import org.ligoj.app.plugin.prov.aws.model.DiscoveryStatus;
import org.ligoj.app.resource.ServicePluginLocator;
import org.ligoj.app.resource.node.LongTaskRunnerNode;
import org.ligoj.app.resource.node.NodeResource;
import org.ligoj.app.resource.subscription.SubscriptionResource;
import org.ligoj.bootstrap.core.security.SecurityHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Discovery task runner resource. IMplies Amazon Discovery Service, Athena and S3 services.
 */
@Service
@Path(ProvResource.SERVICE_URL + "/discovery")
@Produces(MediaType.APPLICATION_JSON)
@Transactional
@Slf4j
public class DiscoveryRunnerResource implements LongTaskRunnerNode<DiscoveryStatus, DiscoveryStatusRepository> {

	@Autowired
	@Getter
	protected DiscoveryStatusRepository taskRepository;

	@Autowired
	@Getter
	private NodeRepository nodeRepository;

	@Autowired
	@Getter
	private NodeResource nodeResource;

	@Autowired
	private SecurityHelper securityHelper;

	@Autowired
	@Getter
	private SubscriptionResource subscriptionResource;

	@Autowired
	private SubscriptionRepository subscriptionRepository;

	@Autowired
	protected ServicePluginLocator locator;
	@Autowired
	protected DiscoveryService discovery;

	@Override
	public Supplier<DiscoveryStatus> newTask() {
		return DiscoveryStatus::new;
	}

	/**
	 * Return the Discovery status from the given subscription identifier.
	 *
	 * @param subscription The subscription identifier.
	 * @return The Discovery status from the given subscription identifier. <code>null</code> when the is no task
	 *         associated to this subscription.
	 */
	@GET
	@Path("{subscription:\\d+}")
	@org.springframework.transaction.annotation.Transactional(readOnly = true)
	public DiscoveryStatus getTask(@PathParam("subscription") final int subscription) {
		return getTaskInternal(getSubscriptionResource().checkVisible(subscription));
	}

	/**
	 * Return the Discovery status from the given subscription identifier.
	 *
	 * @param subscription The subscription identifier.
	 * @return The Discovery status from the given subscription identifier. <code>null</code> when the is no task
	 *         associated to this subscription.
	 */
	@DELETE
	@Path("{subscription:\\d+}")
	@org.springframework.transaction.annotation.Transactional(readOnly = true)
	public DiscoveryStatus cancel(@PathParam("subscription") final int subscription) {
		return endTask(getSubscriptionResource().checkVisible(subscription).getNode().getId(), true);
	}

	/**
	 * Return the Discovery status from the given subscription.
	 *
	 * @param subscription The subscription entity.
	 * @return The Discovery status from the given subscription identifier. <code>null</code> when the is no task
	 *         associated to this subscription.
	 */
	@org.springframework.transaction.annotation.Transactional(readOnly = true)
	public DiscoveryStatus getTaskInternal(final Subscription subscription) {
		final var status = getTask(subscription.getNode().getId());
		if (status == null || status.getSubscription() != subscription.getId()) {
			// Subscription is valid but not related to the current task
			// Another subscription is running on this node
			return null;
		}
		return status;
	}

	/**
	 * Update the internal database from the discovery data.
	 * 
	 * @param node The node (provider) to update.
	 * @return The catalog status.
	 */
	@POST
	@Path("{subscription:\\d+}")
	public DiscoveryStatus refresh(@PathParam("subscription") final int subscription) {
		final var entity = subscriptionResource.checkVisible(subscription);
		final var tool = nodeResource.checkWritableNode(entity.getNode().getId()).getTool();
		final var task = startTask(tool.getId(), t -> {
			t.setSubscription(subscription);
			t.setAthenaTask(null);
			t.setAthenaUrl(null);
			t.setExportUrl(null);
			t.setExportTask(null);
			t.setProgress(0);
			t.setStep(null);
		});
		final var user = securityHelper.getLogin();
		// The import execution will done into another thread
		Executors.newSingleThreadExecutor().submit(() -> {
			Thread.sleep(50);
			securityHelper.setUserName(user);
			refresh(discovery, entity.getId());
			return null;
		});
		return task;
	}

	/**
	 * Update the catalog of given node. Synchronous operation.
	 *
	 * @param catalogService The catalog service related to the provider.
	 * @param node           The node to update.
	 */
	protected void refresh(final DiscoveryService discovery, final int subscription) {
		// Restore the context
		final var entity = subscriptionRepository.findOneExpected(subscription);
		final var parameters = subscriptionResource.getParametersNoCheck(subscription);
		log.info("Quote refresh for {}", entity);
		var failed = true;
		try {
			discovery.refresh(entity, parameters);
			log.info("Quote refresh succeed for {}", entity);
			failed = false;
		} catch (final Exception e) {
			// Catalog update failed
			log.info("Quote refresh failed for {}", entity);
		} finally {
			endTask(entity.getNode().getId(), failed, t -> {
			});
		}
	}
}
