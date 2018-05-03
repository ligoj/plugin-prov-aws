/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.IntStream;

import javax.transaction.Transactional;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.dao.SubscriptionRepository;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Parameter;
import org.ligoj.app.model.ParameterValue;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.prov.QuoteVo;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvInstanceType;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvQuote;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;
import org.ligoj.app.plugin.prov.model.ProvQuoteStorage;
import org.ligoj.app.plugin.prov.model.ProvStoragePrice;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.app.plugin.prov.terraform.Context;
import org.ligoj.app.plugin.prov.terraform.TerraformUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Test class of {@link ProvAwsTerraformService}
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
public class ProvAwsTerraformServiceTest extends AbstractServerTest {
	private static final File MOCK_PATH = new File("target/test-classes/terraform-it").getAbsoluteFile();
	private static final File EXPECTED_PATH = new File("target/test-classes/terraform").getAbsoluteFile();

	@Autowired
	private SubscriptionRepository subscriptionRepository;

	private Subscription subscription;

	@AfterEach
	@BeforeEach
	public void cleanupFiles() throws IOException {
		FileUtils.deleteDirectory(MOCK_PATH);
		FileUtils.forceMkdir(MOCK_PATH);
	}

	@BeforeEach
	public void prepareData() throws IOException {
		persistSystemEntities();
		persistEntities("csv",
				new Class[] { Node.class, Project.class, Parameter.class, Subscription.class, ParameterValue.class },
				StandardCharsets.UTF_8.name());
		subscription = subscriptionRepository.findBy("node.id", "service:prov:aws:test");
	}

	@Test
	public void writeSimpleCentos() throws IOException {
		final ProvQuoteInstance instance = newQuoteInstance("InstanceA", VmOs.CENTOS, null, 1, 1, 10, 8);
		write(subscription, newQuoteVo(instance));
		assertEquals("instance-centos.tf", "eu-west-3/vm-instancea.tf");
		assertTrue(new File(new File(MOCK_PATH, "eu-west-3"), "ami-centos.tf").exists());
	}

	@Test
	public void writeSimpleAmz() throws IOException {
		final ProvQuoteInstance instance = newQuoteInstance("InstanceA", VmOs.LINUX, null, 1, 1, 10, 8);
		write(subscription, newQuoteVo(instance));
		assertEquals("instance-amazon.tf", "eu-west-3/vm-instancea.tf");
		assertTrue(new File(new File(MOCK_PATH, "eu-west-3"), "ami-amazon.tf").exists());
		assertEquals("dashboard-ec2.tf", "eu-west-3/dashboard.tf");
		assertEquals("dashboard-ec2-widgets.tpl.md", "eu-west-3/dashboard-widgets.tpl.md");
		assertEquals("dashboard-ec2-widgets.tpl.json", "eu-west-3/dashboard-widgets.tpl.json");
		assertTrue(new File(MOCK_PATH, "eu-west-3.tf").exists());
	}

	@Test
	public void writeSimpleAmzRootOnly() throws IOException {
		final ProvQuoteInstance instance = newQuoteInstance("InstanceA", VmOs.LINUX, null, 1, 1, 10);
		write(subscription, newQuoteVo(instance));
		assertEquals("instance-amazon-1-device.tf", "eu-west-3/vm-instancea.tf");
		assertTrue(new File(new File(MOCK_PATH, "eu-west-3"), "ami-amazon.tf").exists());
	}

	@Test
	public void writeSimpleAmz3Devices() throws IOException {
		final ProvQuoteInstance instance = newQuoteInstance("InstanceA", VmOs.LINUX, null, 1, 1, 10, 11, 12);
		write(subscription, newQuoteVo(instance));
		assertEquals("instance-amazon-3-devices.tf", "eu-west-3/vm-instancea.tf");
		assertTrue(new File(new File(MOCK_PATH, "eu-west-3"), "ami-amazon.tf").exists());
	}

	@Test
	public void writeSimpleWindows() throws IOException {
		final ProvQuoteInstance instance = newQuoteInstance("InstanceA", VmOs.WINDOWS, null, 1, 1, 10, 8);
		write(subscription, newQuoteVo(instance));
		assertEquals("instance-windows.tf", "eu-west-3/vm-instancea.tf");
		assertTrue(new File(new File(MOCK_PATH, "eu-west-3"), "ami-windows.tf").exists());
	}

	@Test
	public void writeSimpleRHEL() throws IOException {
		final ProvQuoteInstance instance = newQuoteInstance("InstanceA", VmOs.RHEL, null, 1, 1, 10, 8);
		write(subscription, newQuoteVo(instance));
		assertEquals("instance-rhel.tf", "eu-west-3/vm-instancea.tf");
		assertTrue(new File(new File(MOCK_PATH, "eu-west-3"), "ami-rhel.tf").exists());
	}

	@Test
	public void writeSpotAmz() throws IOException {
		final ProvQuoteInstance instance = newQuoteInstance("InstanceA", VmOs.LINUX, 0.1, 1, 1, 10, 8);
		write(subscription, newQuoteVo(instance));
		assertEquals("instance-spot.tf", "eu-west-3/ephemeral-instancea.tf");
		assertTrue(new File(new File(MOCK_PATH, "eu-west-3"), "ami-amazon.tf").exists());
		assertEquals("dashboard-spot.tf", "eu-west-3/dashboard.tf");
		assertEquals("dashboard-spot-widgets.tpl.md", "eu-west-3/dashboard-widgets.tpl.md");
		assertEquals("dashboard-spot-widgets.tpl.json", "eu-west-3/dashboard-widgets.tpl.json");
	}

	@Test
	public void writeAutoScaling() throws IOException {
		final ProvQuoteInstance instance = newQuoteInstance("InstanceA", VmOs.LINUX, null, 1, 2, 10, 8);
		write(subscription, newQuoteVo(instance));
		assertEquals("instance-auto_scaling.tf", "eu-west-3/auto_scaling-instancea.tf");
		assertTrue(new File(new File(MOCK_PATH, "eu-west-3"), "ami-amazon.tf").exists());
	}

	@Test
	public void writeAutoScaling1Device() throws IOException {
		final ProvQuoteInstance instance = newQuoteInstance("InstanceA", VmOs.LINUX, null, 1, 2, 10);
		write(subscription, newQuoteVo(instance));
		assertEquals("instance-auto_scaling-1-device.tf", "eu-west-3/auto_scaling-instancea.tf");
		assertTrue(new File(new File(MOCK_PATH, "eu-west-3"), "ami-amazon.tf").exists());
	}

	@Test
	public void writeAutoScaling3Devices() throws IOException {
		final ProvQuoteInstance instance = newQuoteInstance("InstanceA", VmOs.LINUX, null, 1, 2, 10, 11, 12);
		write(subscription, newQuoteVo(instance));
		assertEquals("instance-auto_scaling-3-devices.tf", "eu-west-3/auto_scaling-instancea.tf");
		assertTrue(new File(new File(MOCK_PATH, "eu-west-3"), "ami-amazon.tf").exists());
	}

	@Test
	public void writeAutoScalingUnbound() throws IOException {
		final ProvQuoteInstance instanceA = newQuoteInstance("InstanceA", VmOs.LINUX, null, 2, null, 10, 8);
		write(subscription, newQuoteVo(instanceA));
		assertEquals("instance-auto_scaling-unbound.tf", "eu-west-3/auto_scaling-instancea.tf");
		assertTrue(new File(new File(MOCK_PATH, "eu-west-3"), "ami-amazon.tf").exists());
	}

	@Test
	public void writeMutliple() throws IOException {
		final ProvQuoteInstance instanceA = newQuoteInstance("InstanceEC21", VmOs.LINUX, null, 1, 1, 10, 8);
		final ProvQuoteInstance instanceB = newQuoteInstance("InstanceEC22", VmOs.WINDOWS, null, 1, 1, 10, 8);
		final ProvQuoteInstance instanceC = newQuoteInstance("InstanceSpot1", VmOs.LINUX, 0.1, 1, 1, 10, 8);
		final ProvQuoteInstance instanceD = newQuoteInstance("InstanceSpot2", VmOs.WINDOWS, 0.1, 1, 1, 10, 8);
		final ProvQuoteInstance instanceE = newQuoteInstance("InstanceAS1", VmOs.LINUX, null, 1, 2, 10, 8);
		final ProvQuoteInstance instanceF = newQuoteInstance("InstanceAS2", VmOs.WINDOWS, null, 1, null, 10, 8);
		write(subscription, newQuoteVo(instanceA, instanceB, instanceC, instanceD, instanceE, instanceF));
		assertEquals("dashboard-multiple.tf", "eu-west-3/dashboard.tf");
		assertEquals("dashboard-multiple-widgets.tpl.md", "eu-west-3/dashboard-widgets.tpl.md");
		assertEquals("dashboard-multiple-widgets.tpl.json", "eu-west-3/dashboard-widgets.tpl.json");
		assertEquals("secrets.auto.tfvars", "secrets.auto.tfvars");
		assertTrue(new File(new File(MOCK_PATH, "eu-west-3"), "ami-amazon.tf").exists());
	}

	/**
	 * Call Terraform generation and check the result is same as input file content
	 * 
	 * @param subscription
	 *            subscription
	 * @param quoteVo
	 *            quote
	 */
	private void write(final Subscription subscription, final QuoteVo quoteVo) throws IOException {
		final Context context = new Context();
		context.setSubscription(subscription);
		context.setQuote(quoteVo);
		newProvAwsTerraformService().write(context);
	}

	/**
	 * generate a quote instance for test purpose
	 * 
	 * @param type
	 *            instance type (Spot or OnDemand
	 * @return quote instance
	 */
	private ProvQuoteInstance newQuoteInstance(final String name, final VmOs os, final Double maxVariableCost,
			final int min, final Integer max, int... storages) {
		final ProvQuoteInstance quoteInstance = new ProvQuoteInstance();
		final ProvInstanceType instance = new ProvInstanceType();
		instance.setName("t2.micro");
		final ProvInstancePrice instancePrice = new ProvInstancePrice();
		instancePrice.setType(instance);
		instancePrice.setOs(os);
		final ProvInstancePriceTerm instancePriceType = new ProvInstancePriceTerm();
		instancePriceType.setName("some");
		instancePrice.setTerm(instancePriceType);
		quoteInstance.setPrice(instancePrice);
		quoteInstance.setId(1);
		quoteInstance.setName(name);
		quoteInstance.setMaxVariableCost(maxVariableCost);
		quoteInstance.setMinQuantity(min);
		quoteInstance.setMaxQuantity(max);
		quoteInstance.setOs(os);
		quoteInstance.setStorages(new ArrayList<>());
		IntStream.range(0, storages.length).forEach(idx -> {
			final ProvQuoteStorage storage = new ProvQuoteStorage();
			storage.setQuoteInstance(quoteInstance);
			ProvStoragePrice price = new ProvStoragePrice();
			ProvStorageType type = new ProvStorageType();
			type.setName("gp2");
			price.setType(type);
			storage.setPrice(price);
			storage.setName(name + "-storage-" + idx);
			storage.setSize(storages[idx]);
			quoteInstance.getStorages().add(storage);
		});

		return quoteInstance;
	}

	private ProvAwsTerraformService newProvAwsTerraformService() {
		final ProvAwsTerraformService resource = new ProvAwsTerraformService();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource.utils = new TerraformUtils() {

			@Override
			public File toFile(final Subscription subscription, final String... fragments) throws IOException {
				final File file = fragments.length == 0 ? MOCK_PATH : new File(MOCK_PATH, String.join("/", fragments));
				FileUtils.forceMkdirParent(file);
				return file;
			}
		};
		return resource;
	}

	private void assertEquals(final String expected, final String generated) throws IOException {
		final File expected2 = new File(EXPECTED_PATH, expected).getAbsoluteFile();
		Assertions.assertTrue(expected2.exists());
		final File generatedFile = new File(MOCK_PATH, generated).getAbsoluteFile();
		Assertions.assertTrue(generatedFile.exists());
		Assertions.assertEquals(IOUtils.toString(expected2.toURI(), StandardCharsets.UTF_8),
				IOUtils.toString(generatedFile.toURI(), StandardCharsets.UTF_8));
	}

	private QuoteVo newQuoteVo(final ProvQuoteInstance... instances) {
		final QuoteVo quoteVo = new QuoteVo();
		final ProvLocation location = new ProvLocation();
		location.setName("eu-west-3");
		quoteVo.setInstances(Arrays.asList(instances));
		ProvQuote configuration = new ProvQuote();
		configuration.setLocation(location);
		Arrays.stream(instances).forEach(i -> {
			i.setConfiguration(configuration);
			i.getStorages().stream().forEach(s -> s.setConfiguration(configuration));
		});
		quoteVo.setLocation(location);
		return quoteVo;
	}
}
