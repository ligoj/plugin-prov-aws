/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import java.io.IOException;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.ligoj.app.plugin.prov.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.prov.catalog.ImportCatalog;
import org.ligoj.app.plugin.prov.model.AbstractCodedEntity;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.bootstrap.core.INamableBean;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;

/**
 * The base import data.
 */
@Component
public class AwsPriceImportBase extends AbstractAwsImport implements ImportCatalog<UpdateContext> {

	/**
	 * Configuration key used for enabled regions pattern names. When value is <code>null</code>, no restriction.
	 */
	public static final String CONF_REGIONS = ProvAwsPluginResource.KEY + ":regions";

	private static final TypeReference<Map<String, ProvLocation>> MAP_LOCATION = new TypeReference<>() {
		// Nothing to extend
	};

	@Override
	public void install(final UpdateContext context) throws IOException {
		importCatalogResource.nextStep(context.getNode().getId(), t -> t.setPhase("region"));
		context.setValidRegion(Pattern.compile(configuration.get(CONF_REGIONS, ".*")));
		context.getMapRegionById().putAll(toMap("aws-regions.json", MAP_LOCATION));

		// Complete the by-name map
		context.getMapStorageToApi().putAll(toMap("storage-to-api.json", MAP_STR));
		context.getMapRegionById().forEach((id,r) -> context.getMapStorageToApi().put(r.getName(), id));

		// The previously installed location cache. Key is the location AWS name
		context.setRegions(locationRepository.findAllBy(BY_NODE, context.getNode()).stream()
				.filter(r -> isEnabledRegion(context, r))
				.collect(Collectors.toMap(INamableBean::getName, Function.identity())));

		// The previously installed storage types cache. Key is the storage name
		context.setStorageTypes(stRepository.findAllBy(BY_NODE, context.getNode()).stream()
				.collect(Collectors.toMap(AbstractCodedEntity::getCode, Function.identity())));
		installStorageTypes(context);
		context.getMapSpotToNewRegion().putAll(toMap("spot-to-new-region.json", MAP_STR));

		nextStep(context, "region");
	}

	private void installStorageTypes(final UpdateContext context) throws IOException {
		csvForBean.toBean(ProvStorageType.class, "csv/aws-prov-storage-type.csv").forEach(t -> {
			final var entity = context.getStorageTypes().computeIfAbsent(t.getName(), n -> {
				final var newType = new ProvStorageType();
				newType.setNode(context.getNode());
				newType.setCode(n);
				return newType;
			});

			// Merge the storage type details
			entity.setName(entity.getCode());
			entity.setDescription(t.getDescription());
			entity.setInstanceType(t.getInstanceType());
			entity.setDatabaseType(t.getDatabaseType());
			entity.setIops(t.getIops());
			entity.setLatency(t.getLatency());
			entity.setMaximal(t.getMaximal());
			entity.setMinimal(t.getMinimal());
			entity.setOptimized(t.getOptimized());
			entity.setThroughput(t.getThroughput());
			entity.setAvailability(t.getAvailability());
			entity.setDurability9(t.getDurability9());
			entity.setEngine(t.getEngine());
			stRepository.save(entity);
		});
	}
}
