/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.aws.catalog;

import lombok.Setter;
import org.ligoj.app.plugin.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.aws.catalog.efs.AwsPriceImportEfs;
import org.ligoj.app.plugin.aws.catalog.lambda.AwsPriceImportLambda;
import org.ligoj.app.plugin.aws.catalog.s3.AwsPriceImportS3;
import org.ligoj.app.plugin.aws.catalog.suppport.AwsPriceImportSupport;
import org.ligoj.app.plugin.aws.catalog.vm.ec2.AwsPriceImportEc2;
import org.ligoj.app.plugin.aws.catalog.vm.fargate.AwsPriceImportFargate;
import org.ligoj.app.plugin.aws.catalog.vm.rds.AwsPriceImportRds;
import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.catalog.AbstractImportCatalogResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The provisioning price service for AWS. Manage installation or update of prices.
 */
@Component
@Setter
public class AwsPriceImport extends AbstractImportCatalogResource {

	@Autowired
	private AwsPriceImportBase base;

	@Autowired
	private AwsPriceImportEc2 ec2;

	@Autowired
	private AwsPriceImportFargate fargate;

	@Autowired
	private AwsPriceImportRds rds;

	@Autowired
	private AwsPriceImportEfs efs;

	@Autowired
	private AwsPriceImportS3 s3;

	@Autowired
	private AwsPriceImportLambda lambda;

	@Autowired
	private AwsPriceImportSupport support;

	@Autowired
	private PlatformTransactionManager txManager;

	/**
	 * Install or update prices.<br>
	 * When the parallel import is disabled (<code>service:prov:use-parallel=0</code>), the whole update is executed
	 * inside a single transaction: prices are accumulated in the persistence context and flushed by chunks with JDBC
	 * batching instead of one transaction per price. With the (default) parallel import, the worker threads run their
	 * own transactions and would not see the uncommitted entities of an enclosing one: each save keeps its own
	 * transaction as before.
	 *
	 * @param force When <code>true</code>, all cost attributes are update.
	 * @throws IOException        When CSV or XML files cannot be read.
	 */
	public void install(final boolean force) throws IOException {
		if (configuration.get(ProvResource.USE_PARALLEL, 1) == 0) {
			try {
				new TransactionTemplate(txManager).executeWithoutResult(s -> {
					initJdbcBatch();
					try {
						installInternal(force);
					} catch (final IOException e) {
						throw new UncheckedIOException(e);
					}
				});
			} catch (final UncheckedIOException e) {
				throw e.getCause();
			}
		} else {
			installInternal(force);
		}
	}

	private void installInternal(final boolean force) throws IOException {
		final var context = initContext(new UpdateContext(), ProvAwsPluginResource.KEY, force);

		base.install(context);
		lambda.install(context);
		s3.install(context);
		ec2.install(context);
		rds.install(context);
		efs.install(context);
		fargate.install(context);
		support.install(context);
		context.cleanup();
	}
}
