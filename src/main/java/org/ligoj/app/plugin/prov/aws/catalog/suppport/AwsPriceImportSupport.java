/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog.suppport;

import java.io.IOException;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.ligoj.app.plugin.prov.aws.catalog.AbstractAwsImport;
import org.ligoj.app.plugin.prov.aws.catalog.UpdateContext;
import org.ligoj.app.plugin.prov.catalog.ImportCatalog;
import org.ligoj.app.plugin.prov.model.AbstractPrice;
import org.ligoj.app.plugin.prov.model.ProvSupportPrice;
import org.ligoj.app.plugin.prov.model.ProvSupportType;
import org.ligoj.bootstrap.core.INamableBean;
import org.springframework.stereotype.Component;

/**
 * The provisioning support price service for AWS. Manage install or update of prices.
 */
@Component
public class AwsPriceImportSupport extends AbstractAwsImport implements ImportCatalog<UpdateContext> {

	@Override
	public void install(final UpdateContext context) throws IOException {
		// Install previous types
		installSupportTypes(context);

		// Fetch previous prices
		final var previous = sp2Repository.findAllBy("type.node", context.getNode()).stream()
				.collect(Collectors.toMap(AbstractPrice::getCode, Function.identity()));

		// Complete the set
		csvForBean.toBean(ProvSupportPrice.class, "csv/aws-prov-support-price.csv").forEach(t -> {
			final var entity = previous.computeIfAbsent(t.getCode(), n -> t);
			// Merge the support type details
			final var price = copyAsNeeded(context, entity, s -> {
				s.setLimit(t.getLimit());
				s.setMin(t.getMin());
				s.setRate(t.getRate());
			});
			saveAsNeeded(context, price, t.getCost(), sp2Repository);
		});
	}

	private void installSupportTypes(final UpdateContext context) throws IOException {
		// Fetch previous prices
		final var previous = st2Repository.findAllBy(BY_NODE, context.getNode()).stream()
				.collect(Collectors.toMap(INamableBean::getName, Function.identity()));

		// Complete the set
		csvForBean.toBean(ProvSupportType.class, "csv/aws-prov-support-type.csv").forEach(t -> {
			final var entity = previous.computeIfAbsent(t.getCode(), n -> t);
			// Merge the support type details
			copyAsNeeded(context, entity, t2 -> {
				t2.setName(t.getCode());
				t2.setDescription(t.getDescription());
				t2.setAccessApi(t.getAccessApi());
				t2.setAccessChat(t.getAccessChat());
				t2.setAccessEmail(t.getAccessEmail());
				t2.setAccessPhone(t.getAccessPhone());
				t2.setSlaStartTime(t.getSlaStartTime());
				t2.setSlaEndTime(t.getSlaEndTime());
				t2.setDescription(t.getDescription());

				t2.setSlaBusinessCriticalSystemDown(t.getSlaBusinessCriticalSystemDown());
				t2.setSlaGeneralGuidance(t.getSlaGeneralGuidance());
				t2.setSlaProductionSystemDown(t.getSlaProductionSystemDown());
				t2.setSlaProductionSystemImpaired(t.getSlaProductionSystemImpaired());
				t2.setSlaSystemImpaired(t.getSlaSystemImpaired());
				t2.setSlaWeekEnd(t.isSlaWeekEnd());

				t2.setCommitment(t.getCommitment());
				t2.setSeats(t.getSeats());
				t2.setLevel(t.getLevel());
			}, st2Repository);
		});
	}
}
