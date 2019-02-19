/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import java.io.IOException;
import java.net.URISyntaxException;

import org.ligoj.app.model.Node;
import org.ligoj.app.plugin.prov.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.prov.aws.catalog.efs.AwsPriceImportEfs;
import org.ligoj.app.plugin.prov.aws.catalog.s3.AwsPriceImportS3;
import org.ligoj.app.plugin.prov.aws.catalog.suppport.AwsPriceImportSupport;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ebs.AwsPriceImportEbs;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AwsPriceImportEc2;
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
public class ProvAwsPriceImport extends AbstractImportCatalogResource {

	@Autowired
	private AwsPriceImportBase base;

	@Autowired
	private AwsPriceImportEc2 ec2;

	@Autowired
	private AwsPriceImportRds rds;

	@Autowired
	private AwsPriceImportEfs efs;

	@Autowired
	private AwsPriceImportEbs ebs;

	@Autowired
	private AwsPriceImportS3 s3;

	@Autowired
	private AwsPriceImportSupport support;

	/**
	 * Install or update prices.
	 *
	 * @throws IOException
	 *             When CSV or XML files cannot be read.
	 * @throws URISyntaxException
	 *             When CSV or XML files cannot be read.
	 */
	public void install() throws IOException, URISyntaxException {
		final UpdateContext context = new UpdateContext();

		// Node is already persisted, install EC2 prices
		final Node node = nodeRepository.findOneExpected(ProvAwsPluginResource.KEY);
		context.setNode(node);

		base.install(context);
		ebs.install(context);
		ec2.install(context);
		rds.install(context);
		s3.install(context);
		efs.install(context);
		support.install(context);
	}
}
