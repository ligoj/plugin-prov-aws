package org.ligoj.app.plugin.prov.aws;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import javax.transaction.Transactional;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.dao.ProjectRepository;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.prov.QuoteVo;
import org.ligoj.app.plugin.prov.model.ProvInstance;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceType;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
public class ProvAwsTerraformServiceTest extends AbstractServerTest {

	@Autowired
	private ProvAwsTerraformService service;

	@Autowired
	private ProjectRepository projectRepository;

	@Before
	public void prepareData() throws IOException {
		persistSystemEntities();
		persistEntities("csv", new Class[] { Node.class, Project.class }, StandardCharsets.UTF_8.name());
	}

	/**
	 * check generated terraform
	 * 
	 * @throws Exception
	 *             unexpected exception
	 */
	@Test
	public void testTerraformGeneration() throws Exception {
		final Subscription subscription = new Subscription();
		subscription.setProject(projectRepository.findByNameExpected("gStack"));
		final ProvQuoteInstance quoteInstance = new ProvQuoteInstance();
		quoteInstance.setName("dev");
		final ProvInstance instance = new ProvInstance();
		instance.setName("t2.micro");
		final ProvInstancePrice instancePrice = new ProvInstancePrice();
		instancePrice.setInstance(instance);
		instancePrice.setOs(VmOs.LINUX);
		final ProvInstancePriceType instancePriceType = new ProvInstancePriceType();
		instancePriceType.setName("spot");
		instancePrice.setType(instancePriceType);
		quoteInstance.setInstancePrice(instancePrice);
		final QuoteVo quoteVo = new QuoteVo();
		quoteVo.setInstances(Lists.newArrayList(quoteInstance));

		final StringWriter writer = new StringWriter();
		service.writeTerraform(writer, quoteVo, subscription);
		
		final String terraform = writer.toString();
		Assert.assertNotNull(terraform);
		Assert.assertEquals(Files.toString(
				new File(Thread.currentThread().getContextClassLoader().getResource("terraform/terraform.tf").toURI()),
				Charsets.UTF_8), terraform);
	}
}
