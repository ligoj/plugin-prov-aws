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
import org.ligoj.app.plugin.prov.QuoteVo;
import org.ligoj.app.plugin.prov.model.InternetAccess;
import org.ligoj.app.plugin.prov.model.ProvInstanceType;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;
import org.ligoj.app.plugin.prov.model.ProvQuoteStorage;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
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
		instance.setStorages(Lists.newArrayList(generateInstanceStorage(5), generateInstanceStorage(50)));
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
	 * check generated terraform
	 * 
	 * @throws Exception
	 *             unexpected exception
	 */
	@Test
	public void testTerraformGenerationPrivateNetwork() throws Exception {
		final QuoteVo quoteVo = new QuoteVo();
		final ProvQuoteInstance instance = generateQuoteInstance("OnDemand");
		instance.setInternet(InternetAccess.PRIVATE);
		quoteVo.setInstances(Lists.newArrayList(instance));
		quoteVo.setStorages(Lists.newArrayList());

		testTerraformGeneration(subscription, quoteVo, "terraform/terraform-private.tf");
	}

	/**
	 * check generated terraform
	 * 
	 * @throws Exception
	 *             unexpected exception
	 */
	@Test
	public void testTerraformGenerationWithAutoscale() throws Exception {
		final QuoteVo quoteVo = new QuoteVo();
		final ProvQuoteInstance instance = generateQuoteInstance("OnDemand");
		instance.setInternet(InternetAccess.PRIVATE);
		instance.setMinQuantity(2);
		instance.setMaxQuantity(4);
		quoteVo.setInstances(Lists.newArrayList(instance));
		quoteVo.setStorages(Lists.newArrayList());

		testTerraformGeneration(subscription, quoteVo, "terraform/terraform-scale.tf");
	}

	/**
	 * check generated terraform
	 * 
	 * @throws Exception
	 *             unexpected exception
	 */
	@Test
	public void testTerraformGenerationNatNetwork() throws Exception {
		final QuoteVo quoteVo = new QuoteVo();
		final ProvQuoteInstance instance = generateQuoteInstance("OnDemand");
		instance.setInternet(InternetAccess.PRIVATE_NAT);
		quoteVo.setInstances(Lists.newArrayList(instance));
		quoteVo.setStorages(Lists.newArrayList());

		testTerraformGeneration(subscription, quoteVo, "terraform/terraform-nat.tf");
	}

	/**
	 * Check generated Terraform for only storages
	 */
	@Test
	public void testTerraformGenerationStorage() throws Exception {
		final QuoteVo quoteVo = new QuoteVo();
		quoteVo.setInstances(Lists.newArrayList());
		quoteVo.setStorages(Lists.newArrayList(generateStorage(null, "backup", 40)));

		testTerraformGeneration(subscription, quoteVo, "terraform/terraform-storage.tf");
	}

	/**
	 * Call terraform generation and check the result is same as input file content
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
	private ProvQuoteStorage generateStorage(final Integer instanceId, final String name, final int size) {
		final ProvQuoteStorage storageVo = new ProvQuoteStorage();
		storageVo.setSize(size);
		storageVo.setName(name);
		if (instanceId != null) {
			final ProvQuoteInstance instance = new ProvQuoteInstance();
			instance.setId(instanceId);
			instance.setName("instance-tag");
			storageVo.setQuoteInstance(instance);
		}
		final ProvStorageType storageType = new ProvStorageType();
		storageType.setName("gp2");
		
		final ProvStoragePrice storagePrice = new ProvStoragePrice();
		storagePrice.setType(storageType);
		storageVo.setPrice(storagePrice);
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
		final ProvInstanceType instance = new ProvInstanceType();
		instance.setName("t2.micro");
		final ProvInstancePrice instancePrice = new ProvInstancePrice();
		instancePrice.setType(instance);
		instancePrice.setOs(VmOs.LINUX);
		final ProvInstancePriceTerm instancePriceType = new ProvInstancePriceTerm();
		instancePriceType.setName(type);
		instancePrice.setTerm(instancePriceType);
		quoteInstance.setPrice(instancePrice);
		quoteInstance.setStorages(Lists.newArrayList());
		return quoteInstance;
	}

	/**
	 * generate an instance storage for test purpose
	 * 
	 * @param size
	 *            storage size
	 * @return instance storage
	 */
	private ProvQuoteStorage generateInstanceStorage(final int size) {
		final ProvQuoteStorage instanceStorage = new ProvQuoteStorage();
		instanceStorage.setSize(size);
		final ProvStoragePrice storagePrice = new ProvStoragePrice();
		final ProvStorageType storageType = new ProvStorageType();
		storageType.setName("gp2");
		storagePrice.setType(storageType);
		instanceStorage.setPrice(storagePrice);
		return instanceStorage;
	}
}
