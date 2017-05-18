package org.ligoj.app.plugin.prov.aws;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

import javax.transaction.Transactional;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.api.SubscriptionMode;
import org.ligoj.app.dao.ProjectRepository;
import org.ligoj.app.iam.model.CacheCompany;
import org.ligoj.app.iam.model.CacheUser;
import org.ligoj.app.model.DelegateNode;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Project;
import org.ligoj.app.plugin.prov.ComputedInstancePrice;
import org.ligoj.app.plugin.prov.LowestInstancePrice;
import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.QuoteInstanceEditionVo;
import org.ligoj.app.plugin.prov.QuoteVo;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceTypeRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstanceRepository;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceType;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.app.plugin.prov.model.ProvTenancy;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.app.resource.subscription.SubscriptionEditionVo;
import org.ligoj.app.resource.subscription.SubscriptionResource;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * Test class of {@link ProvAwsResource}
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
public class ProvAwsResourceTest extends AbstractServerTest {

	@Autowired
	private ProvAwsResource resource;

	@Autowired
	private ProvResource provResource;

	@Autowired
	private SubscriptionResource subscriptionResource;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private ProvInstancePriceTypeRepository iptRepository;

	@Autowired
	private ProvInstanceRepository instanceRepository;

	@Autowired
	private ConfigurationResource configuration;

	@Before
	public void prepareData() throws IOException {
		persistSystemEntities();
		persistEntities("csv",
				new Class[] { Node.class, Project.class, CacheCompany.class, CacheUser.class, DelegateNode.class },
				StandardCharsets.UTF_8.name());
	}

	@Test
	public void getInstalledEntities() {
		Assert.assertTrue(resource.getInstalledEntities().contains(ProvStorageType.class));
	}

	@Test
	public void getKey() {
		Assert.assertEquals("service:prov:aws", resource.getKey());
	}

	@Test
	public void installOffLine() throws Exception {
		initSpringSecurityContext(DEFAULT_USER);
		configuration.saveOrUpdate(ProvAwsResource.CONF_URL_PRICES, "http://localhost:" + MOCK_PORT + "/index.csv");
		configuration.saveOrUpdate(ProvAwsResource.CONF_URL_PRICES_SPOT, "http://localhost:" + MOCK_PORT + "/spot.js");
		httpServer.stubFor(get(urlEqualTo("/index.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/aws/index.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/spot.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/aws/spot.js").getInputStream(), "UTF-8"))));
		httpServer.start();

		// Check the reserved
		final QuoteVo quote = check();
		Assert.assertEquals(46.848d, quote.getCost(), 0.001);
		ProvQuoteInstance instance = quote.getInstances().get(0);
		Assert.assertEquals(46.848d, instance.getCost(), 0.001);
		final ProvInstancePrice price = instance.getInstancePrice();
		Assert.assertEquals(1680d, price.getInitialCost(), 0.001);
		Assert.assertEquals(VmOs.LINUX, price.getOs());
		Assert.assertEquals(ProvTenancy.SHARED, price.getTenancy());
		Assert.assertEquals(0.064, price.getCost(), 0.001);
		ProvInstancePriceType priceType = price.getType();
		Assert.assertEquals("Reserved, 3yr, All Upfront", priceType.getName());
		Assert.assertEquals(1576800, priceType.getPeriod().intValue());
		Assert.assertEquals("c1.medium", price.getInstance().getName());

		// Check the spot
		final ComputedInstancePrice spotPrice = provResource
				.lookupInstance(instance.getConfiguration().getSubscription().getId(), 2, 1741, true, VmOs.LINUX, null,
						null)
				.getInstance();
		Assert.assertEquals(12.444, spotPrice.getCost(), 0.001);
		Assert.assertEquals(0.0173d, spotPrice.getInstance().getCost(), 0.0001);
		Assert.assertEquals("Spot", spotPrice.getInstance().getType().getName());
		Assert.assertEquals("r4.large", spotPrice.getInstance().getInstance().getName());
	}

	@Test
	public void installOnLine() throws Exception {
		configuration.delete(ProvAwsResource.CONF_URL_PRICES);
		configuration.delete(ProvAwsResource.CONF_URL_PRICES_SPOT);

		// Aligned to :
		// https://aws.amazon.com/fr/ec2/pricing/reserved-instances/pricing/
		// Check the reserved
		final QuoteVo quote = check();
		Assert.assertEquals(46.848d, quote.getCost(), 0.001);
		ProvQuoteInstance instance = quote.getInstances().get(0);
		Assert.assertEquals(46.848d, instance.getCost(), 0.001);
		final ProvInstancePrice price = instance.getInstancePrice();
		Assert.assertEquals(1680d, price.getInitialCost(), 0.001);
		Assert.assertEquals(ProvTenancy.SHARED, price.getTenancy());
		Assert.assertEquals(0.064, price.getCost(), 0.001);
		ProvInstancePriceType priceType = price.getType();
		Assert.assertEquals("Reserved, 3yr, All Upfront", priceType.getName());
		Assert.assertEquals(1576800, priceType.getPeriod().intValue());
		Assert.assertEquals("c1.medium", price.getInstance().getName());

		// Check the spot
		final ComputedInstancePrice spotPrice = provResource
				.lookupInstance(instance.getConfiguration().getSubscription().getId(), 2, 1741, false,
						VmOs.LINUX, instanceRepository
								.findByName(instance.getConfiguration().getSubscription().getId(), "r4.large").getId(),
						null)
				.getInstance();
		Assert.assertTrue(spotPrice.getCost() > 5d);
		Assert.assertTrue(spotPrice.getCost() < 100d);
		final ProvInstancePrice instance2 = spotPrice.getInstance();
		Assert.assertTrue(instance2.getCost() > 0.005d);
		Assert.assertTrue(instance2.getCost() < 1d);
		Assert.assertEquals("Spot", instance2.getType().getName());
		Assert.assertEquals("r4.large", instance2.getInstance().getName());
	}

	@Test(expected = ConnectException.class)
	public void installErrorCsv() throws Exception {
		initSpringSecurityContext(DEFAULT_USER);
		configuration.saveOrUpdate(ProvAwsResource.CONF_URL_PRICES, "http://localhost:" + MOCK_PORT + "/any.csv");
		check();
	}

	/**
	 * Reserved prices are available but not the spot instances.
	 */
	@Test
	public void installSpotEmpty() throws Exception {
		initSpringSecurityContext(DEFAULT_USER);
		configuration.saveOrUpdate(ProvAwsResource.CONF_URL_PRICES, "http://localhost:" + MOCK_PORT + "/index.csv");
		configuration.saveOrUpdate(ProvAwsResource.CONF_URL_PRICES_SPOT, "http://localhost:" + MOCK_PORT + "/any.js");
		httpServer.stubFor(get(urlEqualTo("/index.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/aws/index-empty.csv").getInputStream(), "UTF-8"))));
		httpServer.start();

		// Check the reserved
		resource.install();
		em.flush();
		em.clear();
		final int subscription = newSubscription();
		em.flush();
		em.clear();
		Assert.assertEquals(0, provResource.getConfiguration(subscription).getCost(), 0.001);

		// Request an instance that would not be a Spot
		Assert.assertNull(iptRepository.findByName("Reserved, 3yr, All Upfront"));

		// Check the spot
		Assert.assertNull(provResource.lookupInstance(subscription, 1, 1, false, VmOs.LINUX, null, null).getInstance());
	}

	/**
	 * Reserved prices are valid, but not the spot instances.
	 */
	@Test(expected = NumberFormatException.class)
	public void installSpotError() throws Exception {
		initSpringSecurityContext(DEFAULT_USER);
		configuration.saveOrUpdate(ProvAwsResource.CONF_URL_PRICES, "http://localhost:" + MOCK_PORT + "/index.csv");
		configuration.saveOrUpdate(ProvAwsResource.CONF_URL_PRICES_SPOT, "http://localhost:" + MOCK_PORT + "/spot.js");
		httpServer.stubFor(get(urlEqualTo("/index.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/aws/index-empty.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/spot.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/aws/spot-error.js").getInputStream(), "UTF-8"))));
		httpServer.start();

		// Parse error expected
		resource.install();
	}

	/**
	 * No data available from the AWS end-points
	 */
	@Test
	public void installSpotRegionNotFound() throws Exception {
		initSpringSecurityContext(DEFAULT_USER);
		configuration.saveOrUpdate(ProvAwsResource.CONF_URL_PRICES, "http://localhost:" + MOCK_PORT + "/index.csv");
		configuration.saveOrUpdate(ProvAwsResource.CONF_URL_PRICES_SPOT, "http://localhost:" + MOCK_PORT + "/spot.js");
		httpServer.stubFor(get(urlEqualTo("/index.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/aws/index-empty.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/spot.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/aws/spot-empty.js").getInputStream(), "UTF-8"))));
		httpServer.start();

		resource.install();
		em.flush();
		em.clear();
		final int subscription = newSubscription();
		em.flush();
		em.clear();
		Assert.assertEquals(0, provResource.getConfiguration(subscription).getCost(), 0.001);

		// Check no instance can be found
		Assert.assertNull(provResource.lookupInstance(subscription, 1, 1, false, VmOs.LINUX, null, null).getInstance());
	}

	private QuoteVo check() throws IOException, URISyntaxException, Exception {
		resource.install();
		em.flush();
		em.clear();
		final int subscription = newSubscription();
		em.flush();
		em.clear();
		Assert.assertEquals(0, provResource.getConfiguration(subscription).getCost(), 0.001);

		// Request an instance that would not be a Spot
		final LowestInstancePrice price = provResource.lookupInstance(subscription, 2, 1741, true, VmOs.LINUX,
				instanceRepository.findByName(subscription, "c1.medium").getId(),
				iptRepository.findByNameExpected("Reserved, 3yr, All Upfront").getId());
		final QuoteInstanceEditionVo vo = new QuoteInstanceEditionVo();
		vo.setCpu(1d);
		vo.setRam(1);
		vo.setInstancePrice(price.getInstance().getInstance().getId());
		vo.setName("server1");
		vo.setSubscription(subscription);
		provResource.createInstance(vo);
		em.flush();
		em.clear();
		return provResource.getConfiguration(subscription);
	}

	private int newSubscription() throws Exception {
		final SubscriptionEditionVo vo = new SubscriptionEditionVo();
		vo.setMode(SubscriptionMode.CREATE);
		vo.setNode("service:prov:aws:account");
		vo.setProject(projectRepository.findByNameExpected("gStack").getId());
		return subscriptionResource.create(vo);
	}
}
