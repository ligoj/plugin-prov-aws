/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.ligoj.app.plugin.prov.quote.instance.QuoteInstanceQuery.builder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import javax.annotation.PostConstruct;
import javax.transaction.Transactional;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
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
import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.QuoteVo;
import org.ligoj.app.plugin.prov.UpdatedCost;
import org.ligoj.app.plugin.prov.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.prov.aws.catalog.efs.AwsPriceImportEfs;
import org.ligoj.app.plugin.prov.aws.catalog.s3.AwsPriceImportS3;
import org.ligoj.app.plugin.prov.aws.catalog.suppport.AwsPriceImportSupport;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ebs.AwsPriceImportEbs;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AwsEc2Price;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AwsPriceImportEc2;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.CsvForBeanEc2;
import org.ligoj.app.plugin.prov.aws.catalog.vm.rds.AwsPriceImportRds;
import org.ligoj.app.plugin.prov.catalog.AbstractImportCatalogResource;
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
import org.ligoj.app.plugin.prov.quote.database.ProvQuoteDatabaseResource;
import org.ligoj.app.plugin.prov.quote.database.QuoteDatabaseLookup;
import org.ligoj.app.plugin.prov.quote.database.QuoteDatabaseQuery;
import org.ligoj.app.plugin.prov.quote.instance.ProvQuoteInstanceResource;
import org.ligoj.app.plugin.prov.quote.instance.QuoteInstanceEditionVo;
import org.ligoj.app.plugin.prov.quote.instance.QuoteInstanceLookup;
import org.ligoj.app.plugin.prov.quote.storage.ProvQuoteStorageResource;
import org.ligoj.app.plugin.prov.quote.storage.QuoteStorageEditionVo;
import org.ligoj.app.plugin.prov.quote.storage.QuoteStorageLookup;
import org.ligoj.app.plugin.prov.quote.storage.QuoteStorageQuery;
import org.ligoj.bootstrap.core.resource.TechnicalException;
import org.ligoj.bootstrap.dao.system.SystemConfigurationRepository;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Test class of {@link AwsPriceImport}
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
public class AwsPriceImportTest extends AbstractServerTest {

	private static final double DELTA = 0.001;

	private AwsPriceImport resource;

	@Autowired
	private ProvResource provResource;

	@Autowired
	private ProvQuoteInstanceResource qiResource;

	@Autowired
	private ProvQuoteDatabaseResource qbResource;

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
		clearAllCache();
		System.setProperties(initialProperties);

		// Mock catalog import helper
		final ImportCatalogResource helper = new ImportCatalogResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(helper);
		this.resource = initCatalog(helper, new AwsPriceImport());
		this.resource.setBase(initCatalog(helper, new AwsPriceImportBase()));
		this.resource.setEc2(initCatalog(helper, new AwsPriceImportEc2()));
		this.resource.setEbs(initCatalog(helper, new AwsPriceImportEbs()));
		this.resource.setS3(initCatalog(helper, new AwsPriceImportS3()));
		this.resource.setEfs(initCatalog(helper, new AwsPriceImportEfs()));
		this.resource.setRds(initCatalog(helper, new AwsPriceImportRds()));
		this.resource.setSupport(initCatalog(helper, new AwsPriceImportSupport()));

		initSpringSecurityContext(DEFAULT_USER);
		resetImportTask();
	}

	private <T extends AbstractImportCatalogResource> T initCatalog(ImportCatalogResource importHelper, T catalog) {
		applicationContext.getAutowireCapableBeanFactory().autowireBean(catalog);
		catalog.setImportCatalogResource(importHelper);
		MethodUtils.getMethodsListWithAnnotation(catalog.getClass(), PostConstruct.class).forEach(m -> {
			try {
				m.invoke(catalog);
			} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
				// Ignore;
			}
		});
		return catalog;
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

	public void mock(final String url, final String file) throws IOException {
		httpServer.stubFor(get(urlEqualTo(url)).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource(file).getInputStream(), "UTF-8"))));

	}

	public void configure(final String configuration, final String url) {
		this.configuration.put(configuration, "http://localhost:" + MOCK_PORT + url);
	}

	@Test
	public void installOffLine() throws Exception {
		// Install a new configuration
		mockServer();
		applicationContext.getBean(SystemConfigurationRepository.class).findAll();
		initSpringSecurityContext(DEFAULT_USER);
		clearAllCache();

		configure(AwsPriceImportEc2.CONF_URL_EC2_PRICES, "/%s/index-ec2.csv");
		configure(AwsPriceImportRds.CONF_URL_RDS_PRICES, "/%s/index-rds.csv");

		mock("/eu-west-1/index-ec2.csv", "mock-server/aws/index-ec2.csv");
		mock("/us-east-1/index-ec2.csv", "mock-server/aws/index-ec2-empty.csv");
		mock("/eu-central-1/index-ec2.csv", "mock-server/aws/index-ec2-empty.csv");
		mock("/eu-west-1/index-rds.csv", "mock-server/aws/index-rds.csv");
		mock("/us-east-1/index-rds.csv", "mock-server/aws/index-rds-empty.csv");
		mock("/eu-central-1/index-rds.csv", "mock-server/aws/index-rds-empty.csv");

		// Check the basic quote
		final QuoteVo quote = installAndConfigure();

		// Check the whole quote
		final ProvQuoteInstance instance = check(quote, 47.557d, 46.667d);

		// Check the spot
		final QuoteInstanceLookup spotPrice = qiResource.lookup(instance.getConfiguration().getSubscription().getId(),
				builder().cpu(2).ram(1741).constant(true).ephemeral(true).build());
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
		configure(AwsPriceImportEc2.CONF_URL_EC2_PRICES, "/v2/%s/index-ec2.csv");
		configure(AwsPriceImportRds.CONF_URL_RDS_PRICES, "/v2/%s/index-rds.csv");
		configure(AwsPriceImportEc2.CONF_URL_EC2_PRICES_SPOT, "/v2/spot.js");
		configure(AwsPriceImportEbs.CONF_URL_EBS_PRICES, "/v2/pricing-ebs.js");
		configure(AwsPriceImportEfs.CONF_URL_EFS_PRICES, "/v2/index-efs.csv");
		configure(AwsPriceImportS3.CONF_URL_S3_PRICES, "/v2/index-s3.csv");
		mock("/v2/eu-west-1/index-ec2.csv", "mock-server/aws/v2/index-ec2.csv");
		mock("/v2/us-east-1/index-ec2.csv", "mock-server/aws/index-ec2-empty.csv");
		mock("/v2/eu-central-1/index-ec2.csv", "mock-server/aws/index-ec2-empty.csv");
		mock("/v2/eu-west-1/index-rds.csv", "mock-server/aws/v2/index-rds.csv");
		mock("/v2/us-east-1/index-rds.csv", "mock-server/aws/index-rds-empty.csv");
		mock("/v2/eu-central-1/index-rds.csv", "mock-server/aws/index-rds-empty.csv");

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
		Assertions.assertEquals(16, status.getWorkload());
		Assertions.assertEquals("efs", status.getPhase());
		Assertions.assertEquals(DEFAULT_USER, status.getAuthor());
		Assertions.assertEquals(77, status.getNbInstanceTypes().intValue());
		Assertions.assertEquals(109, status.getNbInstancePrices().intValue());
		Assertions.assertEquals(4, status.getNbLocations().intValue());
		Assertions.assertEquals(16, status.getNbStorageTypes().intValue());
	}

	private void mockServer() throws IOException {
		patchConfigurationUrl();
		mock("/index-ec2.csv", "mock-server/aws/index-ec2.csv");
		mock("/index-rds.csv", "mock-server/aws/index-rds.csv");
		mock("/spot.js", "mock-server/aws/spot.js");
		mock("/pricing-ebs.js", "mock-server/aws/pricing-ebs.js");
		mock("/index-efs.csv", "mock-server/aws/index-efs.csv");
		mock("/index-s3.csv", "mock-server/aws/index-s3.csv");

		// Another catalog version
		mock("/v2/index-ec2.csv", "mock-server/aws/v2/index-ec2.csv");
		mock("/v2/index-rds.csv", "mock-server/aws/v2/index-rds.csv");
		mock("/v2/spot.js", "mock-server/aws/v2/spot.js");
		mock("/v2/pricing-ebs.js", "mock-server/aws/v2/pricing-ebs.js");
		mock("/v2/index-efs.csv", "mock-server/aws/v2/index-efs.csv");
		mock("/v2/index-s3.csv", "mock-server/aws/v2/index-s3.csv");
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
		configuration.delete(AwsPriceImportEc2.CONF_URL_EC2_PRICES);
		configuration.delete(AwsPriceImportEc2.CONF_URL_EC2_PRICES_SPOT);
		configuration.put(AwsPriceImportBase.CONF_REGIONS, "eu-west-1"); // Only one region for UTs
		configuration.put(AwsPriceImportEc2.CONF_OS, "LINUX"); // Only one OS for UTs

		// Only "r4.large" and "t.*","i.*,c1" for UTs
		configuration.put(AwsPriceImportEc2.CONF_ITYPE, "(r4.*|t\\.*|i.*|c1.*)");
		configuration.put(AwsPriceImportRds.CONF_DTYPE, "(db\\.r5.*|db\\.t2.*)");

		// Aligned to :
		// https://aws.amazon.com/ec2/pricing/reserved-instances/pricing/
		// Check the reserved
		final QuoteVo quote = installAndConfigure();
		final ProvQuoteInstance instance = quote.getInstances().get(0);

		// Check the spot
		final QuoteInstanceLookup price = qiResource.lookup(instance.getConfiguration().getSubscription().getId(),
				builder().cpu(2).ram(1741).type("r4.large").ephemeral(true).build());
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
		configure(AwsPriceImportEc2.CONF_URL_EC2_PRICES, "/any.csv");
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
		mock("/index-ec2.csv", "mock-server/aws/index-header-not-found.csv");
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
		configure(AwsPriceImportEfs.CONF_URL_EFS_PRICES, "/index-efs-error.csv");
		mock("/index-efs-error.csv", "mock-server/aws/index-error.csv");
		mock("/index-s3.csv", "mock-server/aws/index-s3.csv");
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
		mock("/index-s3.csv", "mock-server/aws/index-error.csv");
		httpServer.start();

		Assertions.assertEquals("Premature end of CSV file, headers were not found",
				Assertions.assertThrows(TechnicalException.class, () -> {
					resource.install();
				}).getMessage());
	}

	private void mockServerNoEc2() throws IOException {
		mock("/spot.js", "mock-server/aws/spot.js");
		mock("/pricing-ebs.js", "mock-server/aws/pricing-ebs.js");
		mock("/index-efs.csv", "mock-server/aws/index-efs.csv");
		mock("/index-s3.csv", "mock-server/aws/index-s3.csv");
		httpServer.start();
	}

	/**
	 * Reserved prices are available but not the spot instances.
	 */
	@Test
	public void installSpotEmpty() throws Exception {
		configure(AwsPriceImportEc2.CONF_URL_EC2_PRICES, "/index-ec2.csv");
		configure(AwsPriceImportRds.CONF_URL_RDS_PRICES, "/index-rds.csv");
		configure(AwsPriceImportEc2.CONF_URL_EC2_PRICES_SPOT, "/any.js");
		configure(AwsPriceImportS3.CONF_URL_S3_PRICES, "/index-s3.csv");
		configure(AwsPriceImportEbs.CONF_URL_EBS_PRICES, "/any.js");
		configure(AwsPriceImportEfs.CONF_URL_EFS_PRICES, "/index-efs.csv");
		mock("/index-ec2.csv", "mock-server/aws/index-ec2-empty.csv");
		mock("/index-rds.csv", "mock-server/aws/index-rds-empty.csv");
		mock("/index-s3.csv", "mock-server/aws/index-s3-empty.csv");
		mock("/index-efs.csv", "mock-server/aws/index-efs-empty.csv");
		httpServer.start();

		// Check the reserved
		resource.install();
		em.flush();
		em.clear();
		Assertions.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), DELTA);

		// Request an instance that would not be a Spot
		Assertions.assertNull(iptRepository.findByName("Reserved, 3yr, All Upfront"));

		// Check the spot is not available
		Assertions.assertNull(qiResource.lookup(subscription, builder().constant(false).ephemeral(true).build()));
	}

	/**
	 * Reserved prices are valid, but not the spot instances.
	 */
	@Test
	public void installSpotError() throws Exception {
		patchConfigurationUrl();
		mock("/index-ec2.csv", "mock-server/aws/index-ec2.csv");
		mock("/index-rds.csv", "mock-server/aws/index-rds.csv");
		mock("/spot.js", "mock-server/aws/spot-error.js");
		httpServer.start();

		// Parse error expected
		Assertions.assertEquals("For input string: \"AAAAAA\"",
				Assertions.assertThrows(NumberFormatException.class, () -> {
					resource.install();
				}).getMessage());
	}

	private void patchConfigurationUrl() {
		configure(AwsPriceImportEc2.CONF_URL_EC2_PRICES, "/index-ec2.csv");
		configure(AwsPriceImportRds.CONF_URL_RDS_PRICES, "/index-rds.csv");
		configure(AwsPriceImportEc2.CONF_URL_EC2_PRICES_SPOT, "/spot.js");
		configure(AwsPriceImportEbs.CONF_URL_EBS_PRICES, "/pricing-ebs.js");
		configure(AwsPriceImportEfs.CONF_URL_EFS_PRICES, "/index-efs.csv");
		configure(AwsPriceImportS3.CONF_URL_S3_PRICES, "/index-s3.csv");
	}

	/**
	 * Spot refers to a non existing/not available instance
	 */
	@Test
	public void installSpotInstanceBrokenReference() throws Exception {
		patchConfigurationUrl();
		mock("/index-efs.csv", "mock-server/aws/index-efs.csv");
		mock("/index-s3.csv", "mock-server/aws/index-s3.csv");
		mock("/index-ec2.csv", "mock-server/aws/index-ec2.csv");
		mock("/index-rds.csv", "mock-server/aws/index-rds.csv");
		mock("/spot.js", "mock-server/aws/spot-unavailable-instance.js");
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
		mock("/index-efs.csv", "mock-server/aws/index-efs.csv");
		mock("/index-ec2.csv", "mock-server/aws/index-ec2-empty.csv");
		mock("/index-rds.csv", "mock-server/aws/index-rds-empty.csv");
		mock("/index-s3.csv", "mock-server/aws/index-s3.csv");
		mock("/spot.js", "mock-server/aws/spot-empty.js");
		httpServer.start();

		resource.install();
		em.flush();
		em.clear();
		Assertions.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), DELTA);
		Assertions.assertEquals(0, ipRepository.findAllBy("type.name", "Spot").size());

		// Check no instance can be found
		Assertions.assertNull(qiResource.lookup(subscription, builder().constant(false).ephemeral(true).build()));
		Assertions.assertNull(qbResource.lookup(subscription, QuoteDatabaseQuery.builder().engine("MYSQL").build()));
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
		final QuoteInstanceLookup lookup = qiResource.lookup(subscription,
				builder().cpu(2).ram(1741).constant(true).type("c1.medium").usage("36month").build());

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
		final QuoteStorageLookup slookup = qsResource.lookup(subscription,
				QuoteStorageQuery.builder().size(5).latency(Rate.GOOD).instance(server1()).build()).get(0);
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
		final QuoteStorageLookup efsLookpup = qsResource.lookup(subscription,
				QuoteStorageQuery.builder().latency(Rate.GOOD).optimized(ProvStorageOptimized.THROUGHPUT).build())
				.get(0);
		final QuoteStorageEditionVo svo2 = new QuoteStorageEditionVo();
		svo2.setSize(1);
		svo2.setOptimized(ProvStorageOptimized.THROUGHPUT);
		svo2.setType(efsLookpup.getPrice().getType().getName());
		svo2.setName("nfs1");
		svo2.setSubscription(subscription);
		final UpdatedCost createEfs = qsResource.create(svo2);
		Assertions.assertEquals(0.33, createEfs.getCost().getMin(), DELTA);

		// Add storage (S3) to this quote
		final QuoteStorageLookup s3Lookpup = qsResource.lookup(subscription,
				QuoteStorageQuery.builder().latency(Rate.MEDIUM).optimized(ProvStorageOptimized.DURABILITY).build())
				.get(0);
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

		// Request a database
		final QuoteDatabaseLookup blookup1 = qbResource.lookup(subscription,
				QuoteDatabaseQuery.builder().cpu(2).ram(1741).type("db.t2.xlarge").engine("MYSQL").build());
		Assertions.assertFalse(blookup1.getPrice().getType().getConstant().booleanValue());
		Assertions.assertNull(blookup1.getPrice().getLicense());
		Assertions.assertEquals("MYSQL", blookup1.getPrice().getEngine());
		Assertions.assertNull(blookup1.getPrice().getEdition());
		Assertions.assertNull(blookup1.getPrice().getStorageEngine());
		Assertions.assertNull(blookup1.getPrice().getInitialCost());
		Assertions.assertEquals("OnDemand", blookup1.getPrice().getTerm().getName());

		final QuoteDatabaseLookup blookup2 = qbResource.lookup(subscription, QuoteDatabaseQuery.builder().cpu(2)
				.ram(1741).type("db.r5.large").license("BYOL").engine("ORACLE").edition("ENTERPRISE").build());
		Assertions.assertTrue(blookup2.getPrice().getType().getConstant().booleanValue());
		Assertions.assertEquals("BYOL", blookup2.getPrice().getLicense());
		Assertions.assertEquals("ORACLE", blookup2.getPrice().getEngine());
		Assertions.assertEquals("ENTERPRISE", blookup2.getPrice().getEdition());
		Assertions.assertNull(blookup2.getPrice().getStorageEngine());
		Assertions.assertNull(blookup2.getPrice().getInitialCost());

		return provResource.getConfiguration(subscription);
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is only one subscription for a service.
	 */
	protected int getSubscription(final String project) {
		return getSubscription(project, ProvAwsPluginResource.KEY);
	}
}
