/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import java.util.Collection;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.prov.catalog.AbstractImportCatalogResource;
import org.ligoj.app.plugin.prov.model.ImportCatalogStatus;
import org.ligoj.bootstrap.core.INamableBean;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The provisioning price service for AWS. Manage install or update of prices.
 */
public abstract class AbstractAwsImport extends AbstractImportCatalogResource {

	@Autowired
	private ProvResource provResource;

	/**
	 * Configuration key used for AWS URL prices.
	 */
	public static final String CONF_URL_API_PRICES = ProvAwsPluginResource.KEY + ":%s-prices-url";

	protected Integer toInteger(final String value) {
		return Optional.ofNullable(StringUtils.trimToNull(value))
				.map(v -> StringUtils.replaceEach(v, new String[] { "GB", "TB", " " }, new String[] { "", "", "" }))
				.map(Integer::valueOf).map(v -> value.contains("TB") ? v * 1024 : v).orElse(null);
	}

	/**
	 * Return a parallel stream if allowed.
	 * 
	 * @param <T>        The stream item type.
	 * @param collection The collection to stream.
	 * @return The parallel or sequential stream.
	 */
	protected <T> Stream<T> newStream(Collection<T> collection) {
		return provResource.newStream(collection);
	}

	/**
	 * Convert the JSON name to the API name and check this storage is exists
	 * 
	 * @param <T>     The storage type.
	 * @param context The update context.
	 * @param storage The storage to evaluate.
	 * @return <code>true</code> when the storage is valid.
	 */
	protected <T extends INamableBean<?>> boolean containsKey(final UpdateContext context, final T storage) {
		storage.setName(context.getMapStorageToApi().getOrDefault(storage.getName(), storage.getName()));
		return context.getStorageTypes().containsKey(storage.getName());
	}

	@Override
	protected int getWorkload(ImportCatalogStatus status) {
		return status.getNbLocations() * 3 + 4; // NB region (for EC2 + Spot + RDS) + 3 (S3+EBS+EFS)
	}
}
