package org.ligoj.app.plugin.prov.aws.in;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
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
import org.ligoj.app.iam.model.CacheCompany;
import org.ligoj.app.iam.model.CacheUser;
import org.ligoj.app.model.DelegateNode;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Parameter;
import org.ligoj.app.model.ParameterValue;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.prov.ProvQuoteInstanceResource;
import org.ligoj.app.plugin.prov.ProvQuoteStorageResource;
import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.QuoteInstanceEditionVo;
import org.ligoj.app.plugin.prov.QuoteInstanceLookup;
import org.ligoj.app.plugin.prov.QuoteStorageEditionVo;
import org.ligoj.app.plugin.prov.QuoteStorageLoopup;
import org.ligoj.app.plugin.prov.QuoteVo;
import org.ligoj.app.plugin.prov.UpdatedCost;
import org.ligoj.app.plugin.prov.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceTermRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstanceTypeRepository;
import org.ligoj.app.plugin.prov.in.ImportCatalogResource;
import org.ligoj.app.plugin.prov.model.ImportCatalogStatus;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvQuote;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;
import org.ligoj.app.plugin.prov.model.ProvQuoteStorage;
import org.ligoj.app.plugin.prov.model.ProvStorageFrequency;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.app.plugin.prov.model.ProvTenancy;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.bootstrap.core.resource.TechnicalException;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * Test class of {@link ProvAwsPriceImportResource}
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
public class ProvAwsPriceImportResourceTest extends AbstractServerTest {

	private ProvAwsPriceImportResource resource;

	@Autowired
	private ProvResource provResource;

	@Autowired
	private ProvQuoteInstanceResource qiResource;

	@Autowired
	private ProvQuoteStorageResource qsResource;

	@Autowired
	private ProvInstancePriceTermRepository iptRepository;

	@Autowired
	private ProvInstancePriceRepository ipRepository;

	@Autowired
	private ProvInstanceTypeRepository instanceRepository;

	@Autowired
	private ConfigurationResource configuration;

	protected int subscription;

	@Before
	public void prepareData() throws IOException {
		persistSystemEntities();
		persistEntities("csv",
				new Class[] { Node.class, Project.class, CacheCompany.class, CacheUser.class, DelegateNode.class, Parameter.class,
						ProvLocation.class, ProvStorageType.class, Subscription.class, ParameterValue.class, ProvQuote.class },
				StandardCharsets.UTF_8.name());
		this.subscription = getSubscription("gStack");

		// Disable inner transaction
		this.resource = new ProvAwsPriceImportResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		this.resource.initSpotToNewRegion();
		this.resource.initEbsToApi();
		this.resource.importCatalogResource = new ImportCatalogResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(this.resource.importCatalogResource);
		initSpringSecurityContext(DEFAULT_USER);
		resetImportTask();
	}

	private void resetImportTask() {
		this.resource.importCatalogResource.endTask("service:prov:aws", false);
		this.resource.importCatalogResource.startTask("service:prov:aws", t -> {
			t.setLocation(null);
			t.setNbInstancePrices(null);
			t.setNbInstanceTypes(null);
			t.setNbStorageTypes(null);
			t.setWorkload(0);
			t.setDone(0);
			t.setPhase(null);
		});
	}

	/**
	 * Only for dead but necessary contracted code.
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
	public void installOffLine() throws Exception {
		// Install a new configuration
		final QuoteVo quote = install();

		// Check the whole quote
		final ProvQuoteInstance instance = check(quote, 47.219d, 46.669d);

		// Check the spot
		final QuoteInstanceLookup spotPrice = qiResource.lookup(instance.getConfiguration().getSubscription().getId(), 2, 1741, true,
				VmOs.LINUX, null, null, true, null, null);
		Assert.assertEquals(12.629, spotPrice.getCost(), 0.001);
		Assert.assertEquals(0.0173d, spotPrice.getPrice().getCost(), 0.0001);
		Assert.assertEquals("Spot", spotPrice.getPrice().getTerm().getName());
		Assert.assertTrue(spotPrice.getPrice().getTerm().isEphemeral());
		Assert.assertEquals("r4.large", spotPrice.getPrice().getType().getName());
		Assert.assertEquals(6, ipRepository.findAllBy("term.name", "Spot").size());
		checkImportStatus();

		// Install again to check the update without change
		resetImportTask();
		resource.install();
		provResource.refreshCost(subscription);
		check(provResource.getConfiguration(subscription), 47.219d, 46.669d);
		checkImportStatus();

		// Now, change a price within the remote catalog

		// Point to another catalog with different prices
		configuration.saveOrUpdate(ProvAwsPriceImportResource.CONF_URL_EC2_PRICES, "http://localhost:" + MOCK_PORT + "/v2/index.csv");
		configuration.saveOrUpdate(ProvAwsPriceImportResource.CONF_URL_EC2_PRICES_SPOT, "http://localhost:" + MOCK_PORT + "/v2/spot.js");
		configuration.saveOrUpdate(ProvAwsPriceImportResource.CONF_URL_EBS_PRICES, "http://localhost:" + MOCK_PORT + "/v2/pricing-ebs.js");
		configuration.saveOrUpdate(ProvAwsPriceImportResource.CONF_URL_S3_PRICES,
				"http://localhost:" + MOCK_PORT + "/v2/pricing-storage-s3.js");

		// Install the new catalog, update occurs
		resetImportTask();
		resource.install();
		provResource.refreshCost(subscription);

		// Check the new price
		final QuoteVo newQuote = provResource.getConfiguration(subscription);
		Assert.assertEquals(47.111d, newQuote.getCost().getMin(), 0.001);

		// Storage price is updated
		final ProvQuoteStorage storage = newQuote.getStorages().get(0);
		Assert.assertEquals(0.5d, storage.getCost(), 0.001);
		Assert.assertEquals(5, storage.getSize(), 0.001);

		// Compute price is updated
		final ProvQuoteInstance instance2 = newQuote.getInstances().get(0);
		Assert.assertEquals(46.611d, instance2.getCost(), 0.001);
		final ProvInstancePrice price = instance2.getPrice();
		Assert.assertEquals(1678d, price.getInitialCost(), 0.001);
		Assert.assertEquals(VmOs.LINUX, price.getOs());
		Assert.assertEquals(ProvTenancy.SHARED, price.getTenancy());
		Assert.assertEquals(0.06385, price.getCost(), 0.001);
		final ProvInstancePriceTerm priceType = price.getTerm();
		Assert.assertEquals("Reserved, 3yr, All Upfront", priceType.getName());
		Assert.assertFalse(priceType.isEphemeral());
		Assert.assertEquals(1576800, priceType.getPeriod().intValue());
		Assert.assertEquals("c1.medium", price.getType().getName());
		checkImportStatus();
	}

	private void checkImportStatus() {
		final ImportCatalogStatus status = this.resource.importCatalogResource.getTask("service:prov:aws");
		Assert.assertEquals(6, status.getDone());
		Assert.assertEquals(7, status.getWorkload());
		Assert.assertEquals("ec2", status.getPhase());
		Assert.assertEquals(DEFAULT_USER, status.getAuthor());
		Assert.assertEquals(258, status.getNbInstancePrices().intValue());
		Assert.assertEquals(74, status.getNbInstanceTypes().intValue());
		Assert.assertEquals(4, status.getNbLocations().intValue());
		Assert.assertEquals(8, status.getNbStorageTypes().intValue());
	}

	private void mockAwsServer() throws IOException {
		patchConfigurationUrl();
		httpServer.stubFor(get(urlEqualTo("/index.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/aws/index.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/spot.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/aws/spot.js").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/pricing-ebs.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/aws/pricing-ebs.js").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/pricing-storage-s3.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/aws/pricing-storage-s3.js").getInputStream(), "UTF-8"))));

		// Another catalog version
		httpServer.stubFor(get(urlEqualTo("/v2/index.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/aws/v2/index.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/v2/spot.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/aws/v2/spot.js").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/v2/pricing-ebs.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/aws/v2/pricing-ebs.js").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/v2/pricing-storage-s3.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/aws/v2/pricing-storage-s3.js").getInputStream(), "UTF-8"))));
		httpServer.start();
	}

	private ProvQuoteInstance check(final QuoteVo quote, final double cost, final double computeCost) {
		Assert.assertEquals(cost, quote.getCost().getMin(), 0.001);
		checkStorage(quote.getStorages().get(0));
		return checkInstance(quote.getInstances().get(0), computeCost);
	}

	private ProvQuoteInstance checkInstance(final ProvQuoteInstance instance, final double cost) {
		Assert.assertEquals(cost, instance.getCost(), 0.001);
		final ProvInstancePrice price = instance.getPrice();
		Assert.assertEquals(1680d, price.getInitialCost(), 0.001);
		Assert.assertEquals(VmOs.LINUX, price.getOs());
		Assert.assertEquals(ProvTenancy.SHARED, price.getTenancy());
		Assert.assertEquals(0.06393, price.getCost(), 0.001);
		final ProvInstancePriceTerm priceType = price.getTerm();
		Assert.assertEquals("Reserved, 3yr, All Upfront", priceType.getName());
		Assert.assertFalse(priceType.isEphemeral());
		Assert.assertEquals(1576800, priceType.getPeriod().intValue());
		Assert.assertEquals("c1.medium", price.getType().getName());
		return instance;
	}

	private ProvQuoteStorage checkStorage(final ProvQuoteStorage storage) {
		Assert.assertEquals(0.55d, storage.getCost(), 0.001);
		Assert.assertEquals(5, storage.getSize(), 0.001);
		Assert.assertNotNull(storage.getQuoteInstance());
		Assert.assertEquals("gp2", storage.getPrice().getType().getName());
		Assert.assertEquals(ProvStorageFrequency.HOT, storage.getPrice().getType().getFrequency());
		return storage;
	}

	/**
	 * Common offline install and configuring an instance
	 * 
	 * @return The new quote from the installed
	 */
	private QuoteVo install() throws Exception {
		mockAwsServer();

		// Check the basic quote
		return installAndConfigure();
	}

	@Test
	public void installOnLine() throws Exception {
		configuration.delete(ProvAwsPriceImportResource.CONF_URL_EC2_PRICES);
		configuration.delete(ProvAwsPriceImportResource.CONF_URL_EC2_PRICES_SPOT);

		// Aligned to :
		// https://aws.amazon.com/fr/ec2/pricing/reserved-instances/pricing/
		// Check the reserved
		final QuoteVo quote = installAndConfigure();
		final ProvQuoteInstance instance = check(quote, 47.219d, 46.669d);

		// Check the spot
		final QuoteInstanceLookup spotPrice = qiResource.lookup(instance.getConfiguration().getSubscription().getId(), 2, 1741, null,
				VmOs.LINUX, "r4.large", null, true, null, null);
		Assert.assertTrue(spotPrice.getCost() > 5d);
		Assert.assertTrue(spotPrice.getCost() < 100d);
		final ProvInstancePrice instance2 = spotPrice.getPrice();
		Assert.assertTrue(instance2.getCost() > 0.005d);
		Assert.assertTrue(instance2.getCost() < 1d);
		Assert.assertEquals("Spot", instance2.getTerm().getName());
		Assert.assertTrue(instance2.getTerm().isEphemeral());
		Assert.assertEquals("r4.large", instance2.getType().getName());
	}

	/**
	 * Unable to retrieve the EC2 CSV file
	 */
	@Test
	public void installEc2CsvNotFound() throws Exception {
		patchConfigurationUrl();
		configuration.saveOrUpdate(ProvAwsPriceImportResource.CONF_URL_EC2_PRICES, "http://localhost:" + MOCK_PORT + "/any.csv");
		mockServerNoEc2();
		resource.install();
		em.flush();
		em.clear();
		Assert.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), 0.001);

		// No instance imported
		Assert.assertEquals(0, instanceRepository.findAll().size());
	}

	/**
	 * Invalid EC2 CSV file
	 */
	@Test(expected = TechnicalException.class)
	public void installEc2CsvInvalidHeader() throws Exception {
		patchConfigurationUrl();
		httpServer.stubFor(get(urlEqualTo("/index.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/aws/index-header-not-found.csv").getInputStream(), "UTF-8"))));
		mockServerNoEc2();

		resource.install();
		em.flush();
		em.clear();
		Assert.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), 0.001);

		// No instance imported
		Assert.assertEquals(0, instanceRepository.findAll().size());
	}

	/**
	 * Duplicate prices into the EC2 CSV file is accepted, but only the last one is
	 * considered
	 */
	@Test
	public void installDuplicateEc2PriceCsv() throws Exception {
		patchConfigurationUrl();
		httpServer.stubFor(get(urlEqualTo("/index.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/aws/index-duplicate-price.csv").getInputStream(), "UTF-8"))));
		mockServerNoEc2();

		resource.install();
		em.flush();
		em.clear();
		Assert.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), 0.001);

		// Only one price has been imported
		Assert.assertEquals(1, instanceRepository.findAll().size());
	}

	private void mockServerNoEc2() throws IOException {
		httpServer.stubFor(get(urlEqualTo("/spot.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/aws/spot.js").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/pricing-ebs.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/aws/pricing-ebs.js").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/pricing-storage-s3.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/aws/pricing-storage-s3.js").getInputStream(), "UTF-8"))));
		httpServer.start();
	}

	/**
	 * Reserved prices are available but not the spot instances.
	 */
	@Test
	public void installSpotEmpty() throws Exception {
		configuration.saveOrUpdate(ProvAwsPriceImportResource.CONF_URL_EC2_PRICES, "http://localhost:" + MOCK_PORT + "/index.csv");
		configuration.saveOrUpdate(ProvAwsPriceImportResource.CONF_URL_EC2_PRICES_SPOT, "http://localhost:" + MOCK_PORT + "/any.js");
		configuration.saveOrUpdate(ProvAwsPriceImportResource.CONF_URL_S3_PRICES, "http://localhost:" + MOCK_PORT + "/any.js");
		configuration.saveOrUpdate(ProvAwsPriceImportResource.CONF_URL_EBS_PRICES, "http://localhost:" + MOCK_PORT + "/any.js");
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
		Assert.assertNull(qiResource.lookup(subscription, 1, 1, false, VmOs.LINUX, null, null, true, null, null));
	}

	/**
	 * Reserved prices are valid, but not the spot instances.
	 */
	@Test(expected = NumberFormatException.class)
	public void installSpotError() throws Exception {
		patchConfigurationUrl();
		httpServer.stubFor(get(urlEqualTo("/index.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/aws/index.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/spot.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/aws/spot-error.js").getInputStream(), "UTF-8"))));
		httpServer.start();

		// Parse error expected
		resource.install();
	}

	private void patchConfigurationUrl() {
		configuration.saveOrUpdate(ProvAwsPriceImportResource.CONF_URL_EC2_PRICES, "http://localhost:" + MOCK_PORT + "/index.csv");
		configuration.saveOrUpdate(ProvAwsPriceImportResource.CONF_URL_EC2_PRICES_SPOT, "http://localhost:" + MOCK_PORT + "/spot.js");
		configuration.saveOrUpdate(ProvAwsPriceImportResource.CONF_URL_EBS_PRICES, "http://localhost:" + MOCK_PORT + "/pricing-ebs.js");
		configuration.saveOrUpdate(ProvAwsPriceImportResource.CONF_URL_S3_PRICES,
				"http://localhost:" + MOCK_PORT + "/pricing-storage-s3.js");
	}

	/**
	 * Spot refers to a non existing/not available instance
	 */
	@Test
	public void installSpotInstanceBrokenReference() throws Exception {
		patchConfigurationUrl();
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
		patchConfigurationUrl();
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
		Assert.assertNull(qiResource.lookup(subscription, 1, 1, false, VmOs.LINUX, null, null, true, null, null));
	}

	/**
	 * Install and check
	 */
	private QuoteVo installAndConfigure() throws IOException, URISyntaxException, Exception {
		resource.install();
		em.flush();
		em.clear();
		Assert.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), 0.001);

		// Request an instance that would not be a Spot
		final QuoteInstanceLookup lookup = qiResource.lookup(subscription, 2, 1741, true, VmOs.LINUX, "c1.medium",
				"Reserved, 3yr, All Upfront", false, null, null);

		final QuoteInstanceEditionVo ivo = new QuoteInstanceEditionVo();
		ivo.setCpu(1d);
		ivo.setRam(1);
		ivo.setPrice(lookup.getPrice().getId());
		ivo.setName("server1");
		ivo.setSubscription(subscription);
		final UpdatedCost createInstance = qiResource.create(ivo);
		Assert.assertTrue(createInstance.getTotalCost().getMin() > 1);
		final int instance = createInstance.getId();
		em.flush();
		em.clear();

		// Add storage to this instance
		final QuoteStorageLoopup slookup = qsResource.lookup(subscription, 5, ProvStorageFrequency.HOT, instance, null, null).get(0);
		final QuoteStorageEditionVo svo = new QuoteStorageEditionVo();
		svo.setQuoteInstance(instance);
		svo.setSize(5);
		svo.setType(slookup.getPrice().getType().getName());
		svo.setName("sda1");
		svo.setSubscription(subscription);
		final UpdatedCost createStorage = qsResource.create(svo);
		Assert.assertTrue(createStorage.getTotalCost().getMin() > 0.5);

		return provResource.getConfiguration(subscription);
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is
	 * only one subscription for a service.
	 */
	protected int getSubscription(final String project) {
		return getSubscription(project, ProvAwsPluginResource.KEY);
	}
}
