/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
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
import java.util.Properties;

import javax.transaction.Transactional;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import org.ligoj.app.plugin.prov.QuoteStorageLookup;
import org.ligoj.app.plugin.prov.QuoteVo;
import org.ligoj.app.plugin.prov.UpdatedCost;
import org.ligoj.app.plugin.prov.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.prov.aws.catalog.AwsEc2Price;
import org.ligoj.app.plugin.prov.aws.catalog.CsvForBeanEc2;
import org.ligoj.app.plugin.prov.aws.catalog.ProvAwsPriceImportResource;
import org.ligoj.app.plugin.prov.catalog.ImportCatalogResource;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceTermRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstanceTypeRepository;
import org.ligoj.app.plugin.prov.dao.ProvQuoteInstanceRepository;
import org.ligoj.app.plugin.prov.dao.ProvQuoteRepository;
import org.ligoj.app.plugin.prov.model.ImportCatalogStatus;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
import org.ligoj.app.plugin.prov.model.ProvInstanceType;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvQuote;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;
import org.ligoj.app.plugin.prov.model.ProvQuoteStorage;
import org.ligoj.app.plugin.prov.model.ProvStorageOptimized;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.app.plugin.prov.model.ProvTenancy;
import org.ligoj.app.plugin.prov.model.ProvUsage;
import org.ligoj.app.plugin.prov.model.Rate;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.bootstrap.core.resource.TechnicalException;
import org.ligoj.bootstrap.dao.system.SystemConfigurationRepository;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Test class of {@link ProvAwsPriceImportResource}
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
public class ProvAwsPriceImportResourceTest extends AbstractServerTest {

	private static final double DELTA = 0.001;

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
	private ProvQuoteInstanceRepository qiRepository;

	@Autowired
	private ProvQuoteRepository repository;

	@Autowired
	private ProvInstanceTypeRepository itRepository;

	@Autowired
	private ConfigurationResource configuration;

	protected int subscription;

	private static Properties initialProperties = (Properties) System.getProperties().clone();

	@BeforeEach
	public void prepareData() throws IOException {
		persistSystemEntities();
		persistEntities("csv",
				new Class[] { Node.class, Project.class, CacheCompany.class, CacheUser.class, DelegateNode.class,
						Parameter.class, ProvLocation.class, Subscription.class, ParameterValue.class,
						ProvQuote.class },
				StandardCharsets.UTF_8.name());
		this.subscription = getSubscription("gStack");

		// Disable inner transaction
		this.resource = new ProvAwsPriceImportResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		this.resource.initSpotToNewRegion();
		this.resource.initEbsToApi();
		this.resource.initRate();
		this.resource.setImportCatalogResource(new ImportCatalogResource());
		clearAllCache();
		System.setProperties(initialProperties);
		applicationContext.getAutowireCapableBeanFactory().autowireBean(this.resource.getImportCatalogResource());
		initSpringSecurityContext(DEFAULT_USER);
		resetImportTask();
	}

	private void resetImportTask() {
		this.resource.getImportCatalogResource().endTask("service:prov:aws", false);
		this.resource.getImportCatalogResource().startTask("service:prov:aws", t -> {
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
		new CsvForBeanEc2(new BufferedReader(new StringReader("SKU"))).toBean(null, (Reader) null);
		new AwsEc2Price().getDrop();
	}

	/**
	 * Invalid EC2 CSV header
	 */
	@Test
	public void installInvalidHeader() {
		Assertions.assertEquals("Premature end of CSV file, headers were not found",
				Assertions.assertThrows(TechnicalException.class, () -> {
					new CsvForBeanEc2(new BufferedReader(new StringReader("any"))).toBean(null, (Reader) null);
				}).getMessage());
	}

	@Test
	public void installOffLine() throws Exception {
		// Install a new configuration
		mockServer();
		applicationContext.getBean(SystemConfigurationRepository.class).findAll();
		initSpringSecurityContext(DEFAULT_USER);
		clearAllCache();

		configuration.put(ProvAwsPriceImportResource.CONF_URL_EC2_PRICES,
				"http://localhost:" + MOCK_PORT + "/%s/index.csv");

		httpServer.stubFor(get(urlEqualTo("/eu-west-1/index.csv"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils
						.toString(new ClassPathResource("mock-server/aws/index.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/us-east-1/index.csv"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
						new ClassPathResource("mock-server/aws/index-empty.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/eu-central-1/index.csv"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
						new ClassPathResource("mock-server/aws/index-empty.csv").getInputStream(), "UTF-8"))));

		// Check the basic quote
		final QuoteVo quote = installAndConfigure();

		// Check the whole quote
		final ProvQuoteInstance instance = check(quote, 47.557d, 46.667d);

		// Check the spot
		final QuoteInstanceLookup spotPrice = qiResource.lookup(instance.getConfiguration().getSubscription().getId(),
				2, 1741, true, VmOs.LINUX, null, true, null, null, null, null);
		Assertions.assertEquals(12.664, spotPrice.getCost(), DELTA);
		Assertions.assertEquals(12.664d, spotPrice.getPrice().getCost(), 0.0001);
		Assertions.assertEquals(0.0173d, spotPrice.getPrice().getCostPeriod(), 0.0001);
		Assertions.assertEquals("Spot", spotPrice.getPrice().getTerm().getName());
		Assertions.assertTrue(spotPrice.getPrice().getTerm().isEphemeral());
		Assertions.assertEquals("r4.large", spotPrice.getPrice().getType().getName());
		Assertions.assertEquals(6, ipRepository.findAllBy("term.name", "Spot").size());

		Assertions.assertEquals("eu-west-1", spotPrice.getPrice().getLocation().getName());
		Assertions.assertEquals("EU (Ireland)", spotPrice.getPrice().getLocation().getDescription());
		checkImportStatus();

		// Install again to check the update without change
		resetImportTask();
		resource.install();
		provResource.updateCost(subscription);
		check(provResource.getConfiguration(subscription), 47.557d, 46.667d);
		checkImportStatus();

		// Now, change a price within the remote catalog

		// Point to another catalog with different prices
		configuration.put(ProvAwsPriceImportResource.CONF_URL_EC2_PRICES,
				"http://localhost:" + MOCK_PORT + "/v2/%s/index.csv");
		configuration.put(ProvAwsPriceImportResource.CONF_URL_EC2_PRICES_SPOT,
				"http://localhost:" + MOCK_PORT + "/v2/spot.js");
		configuration.put(ProvAwsPriceImportResource.CONF_URL_EBS_PRICES,
				"http://localhost:" + MOCK_PORT + "/v2/pricing-ebs.js");
		configuration.put(ProvAwsPriceImportResource.CONF_URL_EFS_PRICES,
				"http://localhost:" + MOCK_PORT + "/v2/pricing-efs.csv");
		configuration.put(ProvAwsPriceImportResource.CONF_URL_S3_PRICES,
				"http://localhost:" + MOCK_PORT + "/v2/pricing-s3.csv");
		httpServer.stubFor(get(urlEqualTo("/v2/eu-west-1/index.csv"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils
						.toString(new ClassPathResource("mock-server/aws/v2/index.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/v2/us-east-1/index.csv"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
						new ClassPathResource("mock-server/aws/index-empty.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/v2/eu-central-1/index.csv"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
						new ClassPathResource("mock-server/aws/index-empty.csv").getInputStream(), "UTF-8"))));

		// Install the new catalog, update occurs
		resetImportTask();
		resource.install();
		provResource.updateCost(subscription);

		// Check the new price
		final QuoteVo newQuote = provResource.getConfiguration(subscription);
		Assertions.assertEquals(47.44d, newQuote.getCost().getMin(), DELTA);

		// Storage price is updated
		final ProvQuoteStorage storage = newQuote.getStorages().get(0);
		Assertions.assertEquals(0.5d, storage.getCost(), DELTA);
		Assertions.assertEquals(5, storage.getSize(), DELTA);

		// Compute price is updated
		final ProvQuoteInstance instance2 = newQuote.getInstances().get(0);
		Assertions.assertEquals(46.611d, instance2.getCost(), DELTA);
		final ProvInstancePrice price = instance2.getPrice();
		Assertions.assertEquals(1678d, price.getInitialCost(), DELTA);
		Assertions.assertEquals(VmOs.LINUX, price.getOs());
		Assertions.assertEquals(ProvTenancy.SHARED, price.getTenancy());
		Assertions.assertEquals(46.611d, price.getCost(), DELTA);
		final ProvInstancePriceTerm priceType = price.getTerm();
		Assertions.assertEquals("Reserved, 3yr, All Upfront", priceType.getName());
		Assertions.assertFalse(priceType.isEphemeral());
		Assertions.assertEquals(36, priceType.getPeriod());

		ProvInstanceType type = price.getType();
		Assertions.assertEquals("c1.medium", type.getName());
		Assertions.assertEquals("{Intel Xeon Family}", type.getDescription());

		// Check rating of "c1.medium"
		Assertions.assertEquals(Rate.MEDIUM, type.getRamRate());
		Assertions.assertEquals(Rate.GOOD, type.getCpuRate());
		Assertions.assertEquals(Rate.LOW, type.getNetworkRate());
		Assertions.assertEquals(Rate.MEDIUM, type.getStorageRate());

		// Check rating of "m4.16xlarge"
		type = itRepository.findByName("m4.16xlarge");
		Assertions.assertEquals(Rate.GOOD, type.getRamRate());
		Assertions.assertEquals(Rate.MEDIUM, type.getCpuRate());
		Assertions.assertEquals(Rate.BEST, type.getNetworkRate());
		Assertions.assertEquals(Rate.GOOD, type.getStorageRate());
		Assertions.assertEquals("{Intel Xeon E5-2686 v4 (Broadwell),2.3 GHz}", type.getDescription());

		// Check status
		checkImportStatus();
	}

	private void checkImportStatus() {
		final ImportCatalogStatus status = this.resource.getImportCatalogResource().getTask("service:prov:aws");
		Assertions.assertTrue(status.getDone() >= 9);
		Assertions.assertEquals(12, status.getWorkload());
		Assertions.assertEquals("efs", status.getPhase());
		Assertions.assertEquals(DEFAULT_USER, status.getAuthor());
		Assertions.assertEquals(74, status.getNbInstanceTypes().intValue());
		Assertions.assertEquals(90, status.getNbInstancePrices().intValue()); // 74 + 6 spot prices
		Assertions.assertEquals(4, status.getNbLocations().intValue());
		Assertions.assertEquals(11, status.getNbStorageTypes().intValue());
	}

	private void mockServer() throws IOException {
		patchConfigurationUrl();
		httpServer.stubFor(get(urlEqualTo("/index.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/aws/index.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/spot.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/aws/spot.js").getInputStream(), "UTF-8"))));
		httpServer.stubFor(
				get(urlEqualTo("/pricing-ebs.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils
						.toString(new ClassPathResource("mock-server/aws/pricing-ebs.js").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/pricing-efs.csv"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
						new ClassPathResource("mock-server/aws/pricing-efs.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(
				get(urlEqualTo("/pricing-s3.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils
						.toString(new ClassPathResource("mock-server/aws/pricing-s3.csv").getInputStream(), "UTF-8"))));

		// Another catalog version
		httpServer.stubFor(
				get(urlEqualTo("/v2/index.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils
						.toString(new ClassPathResource("mock-server/aws/v2/index.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/v2/spot.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/aws/v2/spot.js").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/v2/pricing-ebs.js"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
						new ClassPathResource("mock-server/aws/v2/pricing-ebs.js").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/v2/pricing-efs.csv"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
						new ClassPathResource("mock-server/aws/v2/pricing-efs.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/v2/pricing-s3.csv"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
						new ClassPathResource("mock-server/aws/v2/pricing-s3.csv").getInputStream(), "UTF-8"))));
		httpServer.start();
	}

	private ProvQuoteInstance check(final QuoteVo quote, final double cost, final double computeCost) {
		Assertions.assertEquals(cost, quote.getCost().getMin(), DELTA);
		checkStorage(quote.getStorages().get(0));
		return checkInstance(quote.getInstances().get(0), computeCost);
	}

	private ProvQuoteInstance checkInstance(final ProvQuoteInstance instance, final double cost) {
		Assertions.assertEquals(cost, instance.getCost(), DELTA);
		final ProvInstancePrice price = instance.getPrice();
		Assertions.assertEquals(1680d, price.getInitialCost(), DELTA);
		Assertions.assertEquals(VmOs.LINUX, price.getOs());
		Assertions.assertEquals(ProvTenancy.SHARED, price.getTenancy());
		Assertions.assertEquals(46.667, price.getCost(), DELTA);
		Assertions.assertEquals(1680d, price.getCostPeriod(), DELTA);
		final ProvInstancePriceTerm priceType = price.getTerm();
		Assertions.assertEquals("Reserved, 3yr, All Upfront", priceType.getName());
		Assertions.assertFalse(priceType.isEphemeral());
		Assertions.assertEquals(36, priceType.getPeriod());
		Assertions.assertEquals("c1.medium", price.getType().getName());
		return instance;
	}

	private ProvQuoteStorage checkStorage(final ProvQuoteStorage storage) {
		Assertions.assertEquals(0.55d, storage.getCost(), DELTA);
		Assertions.assertEquals(5, storage.getSize(), DELTA);
		Assertions.assertNotNull(storage.getQuoteInstance());
		Assertions.assertEquals("gp2", storage.getPrice().getType().getName());
		Assertions.assertEquals(Rate.BEST, storage.getPrice().getType().getLatency());
		return storage;
	}

	@Test
	public void installOnLine() throws Exception {
		configuration.delete(ProvAwsPriceImportResource.CONF_URL_EC2_PRICES);
		configuration.delete(ProvAwsPriceImportResource.CONF_URL_EC2_PRICES_SPOT);
		configuration.put(ProvAwsPriceImportResource.CONF_REGIONS, "eu-west-1"); // Only one region for UTs
		configuration.put(ProvAwsPriceImportResource.CONF_OS, "LINUX"); // Only one OS for UTs

		// Aligned to :
		// https://aws.amazon.com/ec2/pricing/reserved-instances/pricing/
		// Check the reserved
		final QuoteVo quote = installAndConfigure();
		final ProvQuoteInstance instance = quote.getInstances().get(0);

		// Check the spot
		final QuoteInstanceLookup price = qiResource.lookup(instance.getConfiguration().getSubscription().getId(), 2,
				1741, null, VmOs.LINUX, "r4.large", true, null, null, null, null);
		Assertions.assertTrue(price.getCost() > 5d);
		Assertions.assertTrue(price.getCost() < 100d);
		final ProvInstancePrice instance2 = price.getPrice();
		Assertions.assertTrue(instance2.getCostPeriod() > 0.002d);
		Assertions.assertTrue(instance2.getCost() > 5d);
		Assertions.assertEquals("Spot", instance2.getTerm().getName());
		Assertions.assertTrue(instance2.getTerm().isEphemeral());
		Assertions.assertEquals("r4.large", instance2.getType().getName());
	}

	/**
	 * Unable to retrieve the EC2 CSV file
	 */
	@Test
	public void installEc2CsvNotFound() throws Exception {
		patchConfigurationUrl();
		configuration.put(ProvAwsPriceImportResource.CONF_URL_EC2_PRICES, "http://localhost:" + MOCK_PORT + "/any.csv");
		mockServerNoEc2();
		resource.install();
		em.flush();
		em.clear();
		Assertions.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), DELTA);

		// No instance imported
		Assertions.assertEquals(0, itRepository.findAll().size());
	}

	/**
	 * Invalid EC2 CSV file
	 */
	@Test
	public void installEc2CsvInvalidHeader() throws Exception {
		patchConfigurationUrl();
		httpServer.stubFor(get(urlEqualTo("/index.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(
						new ClassPathResource("mock-server/aws/index-header-not-found.csv").getInputStream(),
						"UTF-8"))));
		mockServerNoEc2();

		Assertions.assertEquals("Premature end of CSV file, headers were not found",
				Assertions.assertThrows(TechnicalException.class, () -> {
					resource.install();
				}).getMessage());
	}

	/**
	 * Invalid EFS CSV file
	 */
	@Test
	public void installEfsCsvInvalidHeader() throws Exception {
		patchConfigurationUrl();
		configuration.put(ProvAwsPriceImportResource.CONF_URL_EFS_PRICES,
				"http://localhost:" + MOCK_PORT + "/pricing-efs-error.csv");
		httpServer.stubFor(get(urlEqualTo("/pricing-efs-error.csv"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
						new ClassPathResource("mock-server/aws/pricing-error.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(
				get(urlEqualTo("/pricing-s3.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils
						.toString(new ClassPathResource("mock-server/aws/pricing-s3.csv").getInputStream(), "UTF-8"))));
		httpServer.start();

		Assertions.assertEquals("Premature end of CSV file, headers were not found",
				Assertions.assertThrows(TechnicalException.class, () -> {
					resource.install();
				}).getMessage());
	}

	/**
	 * Invalid S3 CSV file
	 */
	@Test
	public void installS3CsvInvalidHeader() throws Exception {
		patchConfigurationUrl();
		httpServer.stubFor(get(urlEqualTo("/pricing-s3.csv"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
						new ClassPathResource("mock-server/aws/pricing-error.csv").getInputStream(), "UTF-8"))));
		httpServer.start();

		Assertions.assertEquals("Premature end of CSV file, headers were not found",
				Assertions.assertThrows(TechnicalException.class, () -> {
					resource.install();
				}).getMessage());
	}

	private void mockServerNoEc2() throws IOException {
		httpServer.stubFor(get(urlEqualTo("/spot.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/aws/spot.js").getInputStream(), "UTF-8"))));
		httpServer.stubFor(
				get(urlEqualTo("/pricing-ebs.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils
						.toString(new ClassPathResource("mock-server/aws/pricing-ebs.js").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/pricing-efs.csv"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
						new ClassPathResource("mock-server/aws/pricing-efs.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(
				get(urlEqualTo("/pricing-s3.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils
						.toString(new ClassPathResource("mock-server/aws/pricing-s3.csv").getInputStream(), "UTF-8"))));
		httpServer.start();
	}

	/**
	 * Reserved prices are available but not the spot instances.
	 */
	@Test
	public void installSpotEmpty() throws Exception {
		configuration.put(ProvAwsPriceImportResource.CONF_URL_EC2_PRICES,
				"http://localhost:" + MOCK_PORT + "/index.csv");
		configuration.put(ProvAwsPriceImportResource.CONF_URL_EC2_PRICES_SPOT,
				"http://localhost:" + MOCK_PORT + "/any.js");
		configuration.put(ProvAwsPriceImportResource.CONF_URL_S3_PRICES,
				"http://localhost:" + MOCK_PORT + "/index.csv");
		configuration.put(ProvAwsPriceImportResource.CONF_URL_EBS_PRICES, "http://localhost:" + MOCK_PORT + "/any.js");
		httpServer.stubFor(get(urlEqualTo("/index.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/aws/index-empty.csv").getInputStream(), "UTF-8"))));
		httpServer.start();

		// Check the reserved
		resource.install();
		em.flush();
		em.clear();
		Assertions.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), DELTA);

		// Request an instance that would not be a Spot
		Assertions.assertNull(iptRepository.findByName("Reserved, 3yr, All Upfront"));

		// Check the spot
		Assertions.assertNull(
				qiResource.lookup(subscription, 1, 1, false, VmOs.LINUX, null, true, null, null, null, null));
	}

	/**
	 * Reserved prices are valid, but not the spot instances.
	 */
	@Test
	public void installSpotError() throws Exception {
		patchConfigurationUrl();
		httpServer.stubFor(get(urlEqualTo("/index.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/aws/index.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/spot.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/aws/spot-error.js").getInputStream(), "UTF-8"))));
		httpServer.start();

		// Parse error expected
		Assertions.assertEquals("For input string: \"AAAAAA\"",
				Assertions.assertThrows(NumberFormatException.class, () -> {
					resource.install();
				}).getMessage());
	}

	private void patchConfigurationUrl() {
		configuration.put(ProvAwsPriceImportResource.CONF_URL_EC2_PRICES,
				"http://localhost:" + MOCK_PORT + "/index.csv");
		configuration.put(ProvAwsPriceImportResource.CONF_URL_EC2_PRICES_SPOT,
				"http://localhost:" + MOCK_PORT + "/spot.js");
		configuration.put(ProvAwsPriceImportResource.CONF_URL_EBS_PRICES,
				"http://localhost:" + MOCK_PORT + "/pricing-ebs.js");
		configuration.put(ProvAwsPriceImportResource.CONF_URL_EFS_PRICES,
				"http://localhost:" + MOCK_PORT + "/pricing-efs.csv");
		configuration.put(ProvAwsPriceImportResource.CONF_URL_S3_PRICES,
				"http://localhost:" + MOCK_PORT + "/pricing-s3.csv");
	}

	/**
	 * Spot refers to a non existing/not available instance
	 */
	@Test
	public void installSpotInstanceBrokenReference() throws Exception {
		patchConfigurationUrl();
		httpServer.stubFor(get(urlEqualTo("/pricing-efs.csv"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
						new ClassPathResource("mock-server/aws/pricing-efs.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(
				get(urlEqualTo("/pricing-s3.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils
						.toString(new ClassPathResource("mock-server/aws/pricing-s3.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/index.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/aws/index.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/spot.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(
						new ClassPathResource("mock-server/aws/spot-unavailable-instance.js").getInputStream(),
						"UTF-8"))));
		httpServer.start();

		// Parse error expected
		resource.install();

		// The unique spot could not be installed
		Assertions.assertEquals(0, ipRepository.findAllBy("type.name", "Spot").size());
	}

	/**
	 * No data available from the AWS end-points
	 */
	@Test
	public void installSpotRegionNotFound() throws Exception {
		patchConfigurationUrl();
		httpServer.stubFor(get(urlEqualTo("/pricing-efs.csv"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
						new ClassPathResource("mock-server/aws/pricing-efs.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/index.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/aws/index-empty.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(
				get(urlEqualTo("/pricing-s3.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils
						.toString(new ClassPathResource("mock-server/aws/pricing-s3.csv").getInputStream(), "UTF-8"))));
		httpServer.stubFor(get(urlEqualTo("/spot.js")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/aws/spot-empty.js").getInputStream(), "UTF-8"))));
		httpServer.start();

		resource.install();
		em.flush();
		em.clear();
		Assertions.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), DELTA);
		Assertions.assertEquals(0, ipRepository.findAllBy("type.name", "Spot").size());

		// Check no instance can be found
		Assertions.assertNull(
				qiResource.lookup(subscription, 1, 1, false, VmOs.LINUX, null, true, null, null, null, null));
	}

	private int server1() {
		return qiRepository.findByName("server1").getId();
	}

	/**
	 * Install and check
	 */
	private QuoteVo installAndConfigure() throws IOException, URISyntaxException, Exception {
		resource.install();
		em.flush();
		em.clear();
		Assertions.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), DELTA);

		final ProvUsage usage = new ProvUsage();
		usage.setName("36month");
		usage.setRate(100);
		usage.setDuration(36);
		usage.setConfiguration(repository.findBy("subscription.id", subscription));
		em.persist(usage);
		em.flush();
		em.clear();

		// Request an instance that would not be a Spot
		final QuoteInstanceLookup lookup = qiResource.lookup(subscription, 2, 1741, true, VmOs.LINUX, "c1.medium",
				false, null, "36month", null, null);

		final QuoteInstanceEditionVo ivo = new QuoteInstanceEditionVo();
		ivo.setCpu(1d);
		ivo.setRam(1);
		ivo.setPrice(lookup.getPrice().getId());
		ivo.setName("server1");
		ivo.setSubscription(subscription);
		final UpdatedCost createInstance = qiResource.create(ivo);
		Assertions.assertTrue(createInstance.getTotal().getMin() > 1);
		Assertions.assertTrue(createInstance.getId() > 0);
		em.flush();
		em.clear();

		// Add storage to this instance
		final QuoteStorageLookup slookup = qsResource.lookup(subscription, 5, Rate.GOOD, server1(), null, null).get(0);
		final QuoteStorageEditionVo svo = new QuoteStorageEditionVo();
		svo.setQuoteInstance(server1());
		svo.setSize(5);
		svo.setType(slookup.getPrice().getType().getName());
		svo.setName("sda1");
		svo.setSubscription(subscription);
		final UpdatedCost createStorage = qsResource.create(svo);
		Assertions.assertTrue(createStorage.getCost().getMin() >= 0.5);
		Assertions.assertTrue(createStorage.getTotal().getMin() > 40);

		// Add storage (EFS) to this quote
		final QuoteStorageLookup efsLookpup = qsResource
				.lookup(subscription, 1, Rate.GOOD, null, ProvStorageOptimized.THROUGHPUT, null).get(0);
		final QuoteStorageEditionVo svo2 = new QuoteStorageEditionVo();
		svo2.setSize(1);
		svo2.setOptimized(ProvStorageOptimized.THROUGHPUT);
		svo2.setType(efsLookpup.getPrice().getType().getName());
		svo2.setName("nfs1");
		svo2.setSubscription(subscription);
		final UpdatedCost createEfs = qsResource.create(svo2);
		Assertions.assertEquals(0.33, createEfs.getCost().getMin(), DELTA);

		// Add storage (S3) to this quote
		final QuoteStorageLookup s3Lookpup = qsResource
				.lookup(subscription, 1, Rate.MEDIUM, null, ProvStorageOptimized.DURABILITY, null).get(0);
		final ProvStorageType type = s3Lookpup.getPrice().getType();
		Assertions.assertEquals(99.5d, type.getAvailability(), 0.000000001d);
		Assertions.assertEquals(11, type.getDurability9().intValue());

		final QuoteStorageEditionVo svo3 = new QuoteStorageEditionVo();
		svo3.setSize(1);
		svo3.setOptimized(ProvStorageOptimized.DURABILITY);
		svo3.setType(s3Lookpup.getPrice().getType().getName());
		svo3.setName("my-bucket");
		svo3.setSubscription(subscription);
		final UpdatedCost createS3 = qsResource.create(svo3);
		Assertions.assertEquals(0.01, createS3.getCost().getMin(), DELTA);

		return provResource.getConfiguration(subscription);
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is only one subscription for a service.
	 */
	protected int getSubscription(final String project) {
		return getSubscription(project, ProvAwsPluginResource.KEY);
	}
}
