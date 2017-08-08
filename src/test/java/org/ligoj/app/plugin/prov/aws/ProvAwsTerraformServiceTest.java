package org.ligoj.app.plugin.prov.aws;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import javax.transaction.Transactional;

import org.apache.commons.io.IOUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.dao.SubscriptionRepository;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Parameter;
import org.ligoj.app.model.ParameterValue;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.prov.QuoteStorageVo;
import org.ligoj.app.plugin.prov.QuoteVo;
import org.ligoj.app.plugin.prov.model.ProvInstance;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceType;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;

/**
 * Test class of {@link ProvAwsTerraformService}
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
public class ProvAwsTerraformServiceTest extends AbstractServerTest {

	@Autowired
	private ProvAwsTerraformService service;

	@Autowired
	private SubscriptionRepository subscriptionRepository;

	private Subscription subscription;

	@Before
	public void prepareData() throws IOException {
		persistSystemEntities();
		persistEntities("csv", new Class[] { Node.class, Project.class, Parameter.class, Subscription.class, ParameterValue.class },
				StandardCharsets.UTF_8.name());
		subscription = subscriptionRepository.findBy("node.id", "service:prov:aws:test");
	}

	/**
	 * check generated terraform
	 * 
	 * @throws Exception
	 *             unexpected exception
	 */
	@Test
	public void testTerraformGenerationOnDemandType() throws Exception {
		final QuoteVo quoteVo = new QuoteVo();
		final ProvQuoteInstance instance = generateQuoteInstance("OnDemand");
		quoteVo.setInstances(Lists.newArrayList(instance));
		quoteVo.setStorages(
				Lists.newArrayList(generateStorage(instance.getId(), "dev", 5), generateStorage(instance.getId(), "dev-1", 50)));

		testTerraformGeneration(subscription, quoteVo, "terraform/terraform.tf");
	}

	/**
	 * check generated terraform
	 * 
	 * @throws Exception
	 *             unexpected exception
	 */
	@Test
	public void testTerraformGenerationSpotType() throws Exception {
		final QuoteVo quoteVo = new QuoteVo();
		quoteVo.setInstances(Lists.newArrayList(generateQuoteInstance("Spot")));
		quoteVo.setStorages(Lists.newArrayList());

		testTerraformGeneration(subscription, quoteVo, "terraform/terraform-spot.tf");
	}

	/**
	 * check generated terraform for only storages
	 * 
	 * @throws Exception
	 *             unexpected exception
	 */
	@Test
	public void testTerraformGenerationStorage() throws Exception {
		final QuoteVo quoteVo = new QuoteVo();
		quoteVo.setInstances(Lists.newArrayList());
		quoteVo.setStorages(Lists.newArrayList(generateStorage(null, "backup", 40)));

		testTerraformGeneration(subscription, quoteVo, "terraform/terraform-storage.tf");
	}

	/**
	 * Call terraform generation and check the result is same as input file
	 * content
	 * 
	 * @param subscription
	 *            subscription
	 * @param quoteVo
	 *            quote
	 * @param expectedResultFileName
	 *            expected result
	 */
	private void testTerraformGeneration(final Subscription subscription, final QuoteVo quoteVo, final String expectedResultFileName)
			throws IOException {
		final StringWriter writer = new StringWriter();
		service.writeTerraform(writer, quoteVo, subscription);

		final String terraform = writer.toString();
		Assert.assertNotNull(terraform);
		Assert.assertEquals(
				IOUtils.toString(Thread.currentThread().getContextClassLoader().getResource(expectedResultFileName), Charsets.UTF_8),
				terraform);
	}

	/**
	 * generate a quote storage vo
	 * 
	 * @param name
	 *            quote name
	 * @param size
	 *            storage size
	 * @return quote storage
	 */
	private QuoteStorageVo generateStorage(final Integer instanceId, final String name, final int size) {
		final QuoteStorageVo storageVo = new QuoteStorageVo();
		storageVo.setSize(size);
		storageVo.setName(name);
		storageVo.setQuoteInstance(instanceId);
		final ProvStorageType storageType = new ProvStorageType();
		storageType.setName("gp2");
		storageVo.setType(storageType);
		return storageVo;
	}

	/**
	 * generate a quote instance for test purpose
	 * 
	 * @param type
	 *            instance type (Spot or OnDemand
	 * @return quote instance
	 */
	private ProvQuoteInstance generateQuoteInstance(final String type) {
		final ProvQuoteInstance quoteInstance = new ProvQuoteInstance();
		quoteInstance.setId(1);
		quoteInstance.setName("dev");
		final ProvInstance instance = new ProvInstance();
		instance.setName("t2.micro");
		final ProvInstancePrice instancePrice = new ProvInstancePrice();
		instancePrice.setInstance(instance);
		instancePrice.setOs(VmOs.LINUX);
		final ProvInstancePriceType instancePriceType = new ProvInstancePriceType();
		instancePriceType.setName(type);
		instancePrice.setType(instancePriceType);
		quoteInstance.setInstancePrice(instancePrice);
		quoteInstance.setStorages(Lists.newArrayList());
		return quoteInstance;
	}
}
