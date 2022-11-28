/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import java.io.IOException;

import org.ligoj.app.plugin.prov.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.prov.aws.catalog.efs.AwsPriceImportEfs;
import org.ligoj.app.plugin.prov.aws.catalog.lambda.AwsPriceImportLambda;
import org.ligoj.app.plugin.prov.aws.catalog.s3.AwsPriceImportS3;
import org.ligoj.app.plugin.prov.aws.catalog.suppport.AwsPriceImportSupport;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AwsPriceImportEc2;
import org.ligoj.app.plugin.prov.aws.catalog.vm.fargate.AwsPriceImportFargate;
import org.ligoj.app.plugin.prov.aws.catalog.vm.rds.AwsPriceImportRds;
import org.ligoj.app.plugin.prov.catalog.AbstractImportCatalogResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.Setter;

/**
 * The provisioning price service for AWS. Manage install or update of prices.
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

	/**
	 * Install or update prices.
	 *
	 * @param force When <code>true</code>, all cost attributes are update.
	 * @throws IOException        When CSV or XML files cannot be read.
	 */
	public void install(final boolean force) throws IOException {
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
