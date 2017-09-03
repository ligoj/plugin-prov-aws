package org.ligoj.app.plugin.prov.aws;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.ConnectException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;

import javax.transaction.Transactional;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.api.SubscriptionStatusWithData;
import org.ligoj.app.iam.model.CacheCompany;
import org.ligoj.app.iam.model.CacheUser;
import org.ligoj.app.model.DelegateNode;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Parameter;
import org.ligoj.app.model.ParameterValue;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.prov.ComputedInstancePrice;
import org.ligoj.app.plugin.prov.ComputedStoragePrice;
import org.ligoj.app.plugin.prov.LowestInstancePrice;
import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.QuoteInstanceEditionVo;
import org.ligoj.app.plugin.prov.QuoteStorageEditionVo;
import org.ligoj.app.plugin.prov.QuoteStorageVo;
import org.ligoj.app.plugin.prov.QuoteVo;
import org.ligoj.app.plugin.prov.aws.auth.AWS4SignatureQuery;
import org.ligoj.app.plugin.prov.aws.auth.AWS4SignatureQuery.AWS4SignatureQueryBuilder;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceTypeRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstanceRepository;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceType;
import org.ligoj.app.plugin.prov.model.ProvQuote;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;
import org.ligoj.app.plugin.prov.model.ProvStorageFrequency;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.app.plugin.prov.model.ProvTenancy;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.app.resource.plugin.CurlRequest;
import org.ligoj.bootstrap.core.NamedBean;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.core.resource.TechnicalException;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * Test class of {@link ProvAwsPluginResource}
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
public class ProvAwsPluginResourceTest extends AbstractServerTest {

	private static final String MOCK_URL = "http://localhost:" + MOCK_PORT + "/mock";

	@Autowired
	private ProvAwsPluginResource resource;

	@Autowired
	private ProvResource provResource;

	@Autowired
	private ProvInstancePriceTypeRepository iptRepository;

	@Autowired
	private ProvInstancePriceRepository ipRepository;

	@Autowired
	private ProvInstanceRepository instanceRepository;

	@Autowired
	private ConfigurationResource configuration;

	protected int subscription;

	@Before
	public void prepareData() throws IOException {
		persistSystemEntities();
		persistEntities("csv", new Class[] { Node.class, Project.class, CacheCompany.class, CacheUser.class, DelegateNode.class,
				Parameter.class, Subscription.class, ParameterValue.class, ProvQuote.class }, StandardCharsets.UTF_8.name());
		this.subscription = getSubscription("gStack");
	}

	/**
	 * Only for dead but necessary contracted code.
	 * 
	 * @throws IOException
	 */
	@Test
	public void dummyCoverage() throws IOException {
		new AwsCsvForBean(new BufferedReader(new StringReader("SKU"))).toBean(null, (Reader) null);
		new AwsInstancePrice().getDrop();
	}

	/**
	 * Invalid EC2 CSV header
	 */
	@Test(expected = TechnicalException.class)
	public void installInvalidHeader() throws IOException {
		new AwsCsvForBean(new BufferedReader(new StringReader("any"))).toBean(null, (Reader) null);
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
		// Install a new configuration
		final QuoteVo quote = install();

		// Check the whole quote
		final ProvQuoteInstance instance = check(quote);

		// Check the spot
		final ComputedInstancePrice spotPrice = provResource
				.lookupInstance(instance.getConfiguration().getSubscription().getId(), 2, 1741, true, VmOs.LINUX, null, null, true)
				.getInstance();
		Assert.assertEquals(12.629, spotPrice.getCost(), 0.001);
		Assert.assertEquals(0.0173d, spotPrice.getInstance().getCost(), 0.0001);
		Assert.assertEquals("Spot", spotPrice.getInstance().getType().getName());
		Assert.assertTrue(spotPrice.getInstance().getType().isEphemeral());
		Assert.assertEquals("r4.large", spotPrice.getInstance().getInstance().getName());
		Assert.assertEquals(5, ipRepository.findAllBy("type.name", "Spot").size());
	}

	private ProvQuoteInstance check(final QuoteVo quote) {
		Assert.assertEquals(47.219d, quote.getCost().getMin(), 0.001);
		checkStorage(quote.getStorages().get(0));
		return checkInstance(quote.getInstances().get(0));
	}

	private QuoteStorageVo checkStorage(final QuoteStorageVo storage) {
		Assert.assertEquals(0.55d, storage.getCost(), 0.001);
		Assert.assertEquals(5, storage.getSize(), 0.001);
		Assert.assertNotNull(storage.getQuoteInstance());
		Assert.assertEquals("gp2", storage.getType().getName());
		Assert.assertEquals(ProvStorageFrequency.HOT, storage.getType().getFrequency());
		return storage;
	}

	private ProvQuoteInstance checkInstance(final ProvQuoteInstance instance) {
		Assert.assertEquals(46.669d, instance.getCost(), 0.001);
		final ProvInstancePrice price = instance.getInstancePrice();
		Assert.assertEquals(1680d, price.getInitialCost(), 0.001);
		Assert.assertEquals(VmOs.LINUX, price.getOs());
		Assert.assertEquals(ProvTenancy.SHARED, price.getTenancy());
		Assert.assertEquals(0.064, price.getCost(), 0.001);
		final ProvInstancePriceType priceType = price.getType();
		Assert.assertEquals("Reserved, 3yr, All Upfront", priceType.getName());
		Assert.assertFalse(priceType.isEphemeral());
		Assert.assertEquals(1576800, priceType.getPeriod().intValue());
		Assert.assertEquals("c1.medium", price.getInstance().getName());
		return instance;
	}

	/**
	 * Common offline install and configuring an instance
	 * 
	 * @return The new quote from the installed
	 */
	private QuoteVo install() throws Exception {
		initSpringSecurityContext(DEFAULT_USER);
		configuration.saveOrUpdate(ProvAwsPluginResource.CONF_URL_PRICES, "http://localhost:" + MOCK_PORT + "/index.csv");
		configuration.saveOrUpdate(ProvAwsPluginResource.CONF_URL_PRICES_SPOT, "http://localhost:" + MOCK_PORT + "/spot.js");
		httpServer.stubFor(get(urlEqualTo("/index.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/aws/index.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/spot.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/aws/spot.js").getInputStream(), "UTF-8"))));
		httpServer.start();

		// Check the basic quote
		return installAndConfigure();
	}

	@Test
	public void installOnLine() throws Exception {
		configuration.delete(ProvAwsPluginResource.CONF_URL_PRICES);
		configuration.delete(ProvAwsPluginResource.CONF_URL_PRICES_SPOT);

		// Aligned to :
		// https://aws.amazon.com/fr/ec2/pricing/reserved-instances/pricing/
		// Check the reserved
		final QuoteVo quote = installAndConfigure();
		final ProvQuoteInstance instance = check(quote);

		// Check the spot
		final ComputedInstancePrice spotPrice = provResource.lookupInstance(instance.getConfiguration().getSubscription().getId(), 2, 1741,
				null, VmOs.LINUX, instanceRepository.findByName(instance.getConfiguration().getSubscription().getId(), "r4.large").getId(),
				null, true).getInstance();
		Assert.assertTrue(spotPrice.getCost() > 5d);
		Assert.assertTrue(spotPrice.getCost() < 100d);
		final ProvInstancePrice instance2 = spotPrice.getInstance();
		Assert.assertTrue(instance2.getCost() > 0.005d);
		Assert.assertTrue(instance2.getCost() < 1d);
		Assert.assertEquals("Spot", instance2.getType().getName());
		Assert.assertTrue(instance2.getType().isEphemeral());
		Assert.assertEquals("r4.large", instance2.getInstance().getName());
	}

	@Test(expected = ConnectException.class)
	public void installErrorCsv() throws Exception {
		initSpringSecurityContext(DEFAULT_USER);
		configuration.saveOrUpdate(ProvAwsPluginResource.CONF_URL_PRICES, "http://localhost:" + MOCK_PORT + "/any.csv");
		installAndConfigure();
	}

	/**
	 * Reserved prices are available but not the spot instances.
	 */
	@Test
	public void installSpotEmpty() throws Exception {
		initSpringSecurityContext(DEFAULT_USER);
		configuration.saveOrUpdate(ProvAwsPluginResource.CONF_URL_PRICES, "http://localhost:" + MOCK_PORT + "/index.csv");
		configuration.saveOrUpdate(ProvAwsPluginResource.CONF_URL_PRICES_SPOT, "http://localhost:" + MOCK_PORT + "/any.js");
		httpServer.stubFor(get(urlEqualTo("/index.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/aws/index-empty.csv").getInputStream(), "UTF-8"))));
		httpServer.start();

		// Check the reserved
		resource.install();
		em.flush();
		em.clear();
		Assert.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), 0.001);

		// Request an instance that would not be a Spot
		Assert.assertNull(iptRepository.findByName("Reserved, 3yr, All Upfront"));

		// Check the spot
		Assert.assertNull(provResource.lookupInstance(subscription, 1, 1, false, VmOs.LINUX, null, null, true).getInstance());
	}

	/**
	 * Reserved prices are valid, but not the spot instances.
	 */
	@Test(expected = NumberFormatException.class)
	public void installSpotError() throws Exception {
		initSpringSecurityContext(DEFAULT_USER);
		configuration.saveOrUpdate(ProvAwsPluginResource.CONF_URL_PRICES, "http://localhost:" + MOCK_PORT + "/index.csv");
		configuration.saveOrUpdate(ProvAwsPluginResource.CONF_URL_PRICES_SPOT, "http://localhost:" + MOCK_PORT + "/spot.js");
		httpServer.stubFor(get(urlEqualTo("/index.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/aws/index.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/spot.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/aws/spot-error.js").getInputStream(), "UTF-8"))));
		httpServer.start();

		// Parse error expected
		resource.install();
	}

	/**
	 * Spot refers to a non existing/not available instance
	 */
	@Test
	public void installSpotInstanceBrokenReference() throws Exception {
		initSpringSecurityContext(DEFAULT_USER);
		configuration.saveOrUpdate(ProvAwsPluginResource.CONF_URL_PRICES, "http://localhost:" + MOCK_PORT + "/index.csv");
		configuration.saveOrUpdate(ProvAwsPluginResource.CONF_URL_PRICES_SPOT, "http://localhost:" + MOCK_PORT + "/spot.js");
		httpServer.stubFor(get(urlEqualTo("/index.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/aws/index.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/spot.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/aws/spot-unavailable-instance.js").getInputStream(), "UTF-8"))));
		httpServer.start();

		// Parse error expected
		resource.install();

		// The unique spot could not be installed
		Assert.assertEquals(0, ipRepository.findAllBy("type.name", "Spot").size());
	}

	/**
	 * No data available from the AWS end-points
	 */
	@Test
	public void installSpotRegionNotFound() throws Exception {
		initSpringSecurityContext(DEFAULT_USER);
		configuration.saveOrUpdate(ProvAwsPluginResource.CONF_URL_PRICES, "http://localhost:" + MOCK_PORT + "/index.csv");
		configuration.saveOrUpdate(ProvAwsPluginResource.CONF_URL_PRICES_SPOT, "http://localhost:" + MOCK_PORT + "/spot.js");
		httpServer.stubFor(get(urlEqualTo("/index.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/aws/index-empty.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/spot.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/aws/spot-empty.js").getInputStream(), "UTF-8"))));
		httpServer.start();

		resource.install();
		em.flush();
		em.clear();
		Assert.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), 0.001);
		Assert.assertEquals(0, ipRepository.findAllBy("type.name", "Spot").size());

		// Check no instance can be found
		Assert.assertNull(provResource.lookupInstance(subscription, 1, 1, false, VmOs.LINUX, null, null, true).getInstance());
	}

	/**
	 * Install and check
	 */
	private QuoteVo installAndConfigure() throws IOException, URISyntaxException, Exception {
		persistEntities("csv", new Class[] { ProvStorageType.class }, StandardCharsets.UTF_8.name());
		resource.install();
		em.flush();
		em.clear();
		Assert.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), 0.001);

		// Request an instance that would not be a Spot
		final LowestInstancePrice price = provResource.lookupInstance(subscription, 2, 1741, true, VmOs.LINUX,
				instanceRepository.findByName(subscription, "c1.medium").getId(),
				iptRepository.findByNameExpected("Reserved, 3yr, All Upfront").getId(), false);
		final QuoteInstanceEditionVo ivo = new QuoteInstanceEditionVo();
		ivo.setCpu(1d);
		ivo.setRam(1);
		ivo.setInstancePrice(price.getInstance().getInstance().getId());
		ivo.setName("server1");
		ivo.setSubscription(subscription);
		final int instance = provResource.createInstance(ivo).getId();
		em.flush();
		em.clear();

		// Add storage to this instance
		final ComputedStoragePrice sprice = provResource.lookupStorage(subscription, 5, ProvStorageFrequency.HOT, instance, null).get(0);
		final QuoteStorageEditionVo svo = new QuoteStorageEditionVo();
		svo.setQuoteInstance(instance);
		svo.setSize(5);
		svo.setType(sprice.getType().getId());
		svo.setName("sda1");
		svo.setSubscription(subscription);
		provResource.createStorage(svo);

		return provResource.getConfiguration(subscription);
	}

	@Test
	public void terraform() throws Exception {
		final ByteArrayOutputStream bos = new ByteArrayOutputStream();
		final QuoteVo vo = install();
		resource.terraform(bos, subscription, vo);
		final String content = IOUtils.toString(new ByteArrayInputStream(bos.toByteArray()), "UTF-8");
		Assert.assertTrue(content.startsWith("variable \"AWS_ACCESS_KEY_ID\""));
	}

	@Test
	public void terraformCommandLineParameters() throws Exception {
		final String[] parameters = resource.commandLineParameters(subscription);
		Assert.assertTrue(parameters.length == 4);
		Assert.assertTrue("'AWS_ACCESS_KEY_ID=12345678901234567890'".equals(parameters[1]));
		Assert.assertTrue("'AWS_SECRET_ACCESS_KEY=abcdefghtiklmnopqrstuvwxyz'".equals(parameters[3]));
	}

	/**
	 * retrieve keys from AWS
	 * 
	 * @throws Exception
	 *             exception
	 */
	@Test
	public void getEC2Keys() throws Exception {
		final ProvAwsPluginResource resource = Mockito.spy(this.resource);
		final CurlRequest mockRequest = new CurlRequest("GET", MOCK_URL, null);
		mockRequest.setSaveResponse(true);
		Mockito.doReturn(mockRequest).when(resource).newRequest(ArgumentMatchers.any(AWS4SignatureQueryBuilder.class),
				ArgumentMatchers.eq(subscription));
		httpServer.stubFor(
				get(urlEqualTo("/mock")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("<keyName>my-key</keyName>")));
		httpServer.start();

		final List<NamedBean<String>> keys = resource.getEC2Keys(subscription);
		Assert.assertFalse(keys.isEmpty());
		Assert.assertEquals(1, keys.size());
		Assert.assertEquals("my-key", keys.get(0).getId());
	}

	/**
	 * error when we retrieve keys from AWS
	 * 
	 * @throws Exception
	 *             exception
	 */
	@Test
	public void getEC2KeysError() throws Exception {
		Assert.assertTrue(resource.getEC2Keys(subscription).isEmpty());
	}

	/**
	 * prepare call to AWS
	 * 
	 * @throws Exception
	 *             exception
	 */
	@Test
	public void newRequest() throws Exception {
		final CurlRequest request = resource.newRequest(AWS4SignatureQuery.builder().host("mock").path("/").body("body").service("s3"),
				subscription);
		Assert.assertTrue(request.getHeaders().containsKey("Authorization"));
		Assert.assertEquals("https://mock/", request.getUrl());
		Assert.assertEquals("POST", request.getMethod());
		Assert.assertEquals("body", request.getContent());
	}

	@Test(expected = BusinessException.class)
	public void createFailed() throws Exception {
		final ProvAwsPluginResource resource = Mockito.spy(ProvAwsPluginResource.class);
		Mockito.doReturn(false).when(resource).validateAccess(ArgumentMatchers.anyInt());
		resource.create(-1);
	}

	@Test
	public void checkSubscriptionStatusUp() throws Exception {
		final SubscriptionStatusWithData status = resource.checkSubscriptionStatus(subscription, null, new HashMap<String, String>());
		Assert.assertTrue(status.getStatus().isUp());
	}

	@Test
	public void checkSubscriptionStatusDown() throws Exception {
		final ProvAwsPluginResource resource = Mockito.spy(ProvAwsPluginResource.class);
		Mockito.doReturn(false).when(resource).validateAccess(ArgumentMatchers.anyInt());
		final SubscriptionStatusWithData status = resource.checkSubscriptionStatus(subscription, null, new HashMap<String, String>());
		Assert.assertFalse(status.getStatus().isUp());
	}

	@Test
	public void validateAccessUp() throws Exception {
		Assert.assertTrue(validateAccess(HttpStatus.SC_OK));
	}

	@Test
	public void validateAccessDown() throws Exception {
		Assert.assertFalse(validateAccess(HttpStatus.SC_FORBIDDEN));
	}

	private boolean validateAccess(int status) throws Exception {
		final ProvAwsPluginResource resource = Mockito.spy(new ProvAwsPluginResource());
		final CurlRequest mockRequest = new CurlRequest("GET", MOCK_URL, null);
		mockRequest.setSaveResponse(true);
		Mockito.doReturn("any").when(resource).getRegion();
		Mockito.doReturn(mockRequest).when(resource).newRequest(ArgumentMatchers.any(AWS4SignatureQueryBuilder.class),
				ArgumentMatchers.eq(subscription));

		httpServer.stubFor(get(urlEqualTo("/mock")).willReturn(aResponse().withStatus(status)));
		httpServer.start();
		return resource.validateAccess(subscription);
	}

	/**
	 * Configuration class used to mock AWS calls
	 */
	@Configuration
	public static class MockConfiguration {
		@Bean
		ProvAwsPluginResource provAwsPluginResource() {
			return new ProvAwsPluginResource() {
				@Override
				public boolean validateAccess(int subscription) throws Exception {
					return true;
				}
			};
		}
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is
	 * only one subscription for a service.
	 */
	protected int getSubscription(final String project) {
		return getSubscription(project, ProvAwsPluginResource.KEY);
	}
}
