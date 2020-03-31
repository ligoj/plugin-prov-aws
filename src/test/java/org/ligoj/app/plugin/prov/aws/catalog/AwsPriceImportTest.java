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
import org.ligoj.app.plugin.prov.dao.ProvDatabasePriceRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceTermRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstanceTypeRepository;
import org.ligoj.app.plugin.prov.dao.ProvQuoteDatabaseRepository;
import org.ligoj.app.plugin.prov.dao.ProvQuoteInstanceRepository;
import org.ligoj.app.plugin.prov.dao.ProvQuoteRepository;
import org.ligoj.app.plugin.prov.dao.ProvQuoteStorageRepository;
import org.ligoj.app.plugin.prov.model.ImportCatalogStatus;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceTerm;
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
import org.ligoj.app.plugin.prov.quote.database.QuoteDatabaseEditionVo;
import org.ligoj.app.plugin.prov.quote.database.QuoteDatabaseQuery;
import org.ligoj.app.plugin.prov.quote.instance.ProvQuoteInstanceResource;
import org.ligoj.app.plugin.prov.quote.instance.QuoteInstanceEditionVo;
import org.ligoj.app.plugin.prov.quote.instance.QuoteInstanceLookup;
import org.ligoj.app.plugin.prov.quote.storage.ProvQuoteStorageResource;
import org.ligoj.app.plugin.prov.quote.storage.QuoteStorageEditionVo;
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
class AwsPriceImportTest extends AbstractServerTest {

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
	private ProvDatabasePriceRepository bpRepository;

	@Autowired
	private ProvQuoteInstanceRepository qiRepository;

	@Autowired
	private ProvQuoteDatabaseRepository qbRepository;

	@Autowired
	private ProvQuoteStorageRepository qsRepository;

	@Autowired
	private ProvQuoteRepository repository;

	@Autowired
	private ProvInstanceTypeRepository itRepository;

	@Autowired
	private ConfigurationResource configuration;

	protected int subscription;

	private static Properties initialProperties = (Properties) System.getProperties().clone();

	@BeforeEach
	void prepareData() throws IOException {
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
		configuration.put(ProvResource.USE_PARALLEL, "0");

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
				// Ignore
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
	@SuppressWarnings("deprecation")
	@Test
	void dummyCoverage() throws IOException {
		new CsvForBeanEc2(new BufferedReader(new StringReader("SKU"))).toBean(null, (Reader) null);
		new AwsEc2Price().getDrop();
	}

	/**
	 * Invalid EC2 CSV header
	 */
	@SuppressWarnings("deprecation")
	@Test
	void installInvalidHeader() {
		Assertions.assertEquals("Premature end of CSV file, headers were not found",
				Assertions.assertThrows(TechnicalException.class, () -> {
					new CsvForBeanEc2(new BufferedReader(new StringReader("any"))).toBean(null, (Reader) null);
				}).getMessage());
	}

	void mock(final String url, final String file) throws IOException {
		httpServer.stubFor(get(urlEqualTo(url)).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource(file).getInputStream(), "UTF-8"))));

	}

	void configure(final String configuration, final String url) {
		this.configuration.put(configuration, "http://localhost:" + MOCK_PORT + url);
	}

	@Test
	void installOffLine() throws Exception {
		// Install a new configuration
		mockServer();
		applicationContext.getBean(SystemConfigurationRepository.class).findAll();
		initSpringSecurityContext(DEFAULT_USER);
		clearAllCache();

		configure(AwsPriceImportEc2.CONF_URL_EC2_PRICES, "/%s/index-ec2.csv");
		configure(AwsPriceImportRds.CONF_URL_RDS_PRICES, "/%s/index-rds.csv");

		mock("/eu-west-1/index-ec2.csv", "mock-server/aws/index-ec2.csv");
		mock("/eu-west-2/index-ec2.csv", "mock-server/aws/index-ec2-empty.csv");
		mock("/us-west-2/index-ec2.csv", "mock-server/aws/index-ec2-empty.csv");
		mock("/us-east-1/index-ec2.csv", "mock-server/aws/index-ec2-empty.csv");
		mock("/eu-central-1/index-ec2.csv", "mock-server/aws/index-ec2-empty.csv");

		mock("/eu-west-1/index-rds.csv", "mock-server/aws/index-rds.csv");
		mock("/eu-west-2/index-rds.csv", "mock-server/aws/index-rds-empty.csv");
		mock("/us-east-1/index-rds.csv", "mock-server/aws/index-rds-empty.csv");
		mock("/eu-central-1/index-rds.csv", "mock-server/aws/index-rds-empty.csv");

		mock("/savingsPlan/v1.0/aws/AWSComputeSavingsPlan/20200220220300/eu-west-1/index.json",
				"mock-server/aws/savings-plan-eu-west-1-index.json");
		mock("/savingsPlan/v1.0/aws/AWSComputeSavingsPlan/20200220220300/eu-west-3/index.json",
				"mock-server/aws/savings-plan-eu-west-3-index.json");
		mock("/region_index.json", "mock-server/aws/region_index.json");

		// Check the basic quote
		final var quote = installAndConfigure();

		// Check the whole quote
		final var instance = check(quote, 449.057d, 46.667d);
		final var subscription = instance.getConfiguration().getSubscription().getId();

		// Check the v1 only prices
		bpRepository.findByExpected("code", "OLD_____________.JRTCKXETXF.6YS6EN2CT7");
		ipRepository.findByExpected("code", "OLD_____________.JRTCKXETXF.6YS6EN2CT7");

		// Check the spot
		final var spotPrice = qiResource.lookup(subscription,
				builder().cpu(2).ram(1741).constant(true).ephemeral(true).build());
		Assertions.assertEquals(12.629, spotPrice.getCost(), DELTA);
		Assertions.assertEquals(12.629d, spotPrice.getPrice().getCost(), DELTA);
		Assertions.assertEquals(12.629d, spotPrice.getPrice().getCostPeriod(), DELTA);
		Assertions.assertEquals("Spot", spotPrice.getPrice().getTerm().getName());
		Assertions.assertTrue(spotPrice.getPrice().getTerm().isEphemeral());
		Assertions.assertEquals("r4.large", spotPrice.getPrice().getType().getName());
		Assertions.assertEquals(6, ipRepository.findAllBy("term.name", "Spot").size());

		Assertions.assertEquals("eu-west-1", spotPrice.getPrice().getLocation().getName());
		Assertions.assertEquals("EU (Ireland)", spotPrice.getPrice().getLocation().getDescription());

		checkImportStatus(81 /* OD */ + 3 /* RI */ + 6 /* spot */ + 2 /* SP */ + 20 /* RDS */, 77);
		Assertions.assertEquals(81, ipRepository.findAllBy("term.code", "JRTCKXETXF").size());
		Assertions.assertEquals(3, ipRepository.findAllBy("term.code", "NQ3QZPMQV9").size()); // Reserved 3y
		Assertions.assertEquals(6, ipRepository.findAllBy("term.code", "spot").size());
		Assertions.assertEquals(1, ipRepository.findAllBy("term.code", "SSTZVD8UMFZ4RSTW").size()); // EC2 SP
		Assertions.assertEquals(1, ipRepository.findAllBy("term.code", "8GU23DFTKP2N43SD").size()); // Compute SP
		Assertions.assertEquals(20, bpRepository.findAll().size()); // RDS

		// Check the EC2 savings plan
		final var ec2SsavingsPlanPrice = qiResource.lookup(subscription,
				builder().cpu(2).ram(1741).constant(true).usage("36monthEC2SP").build());
		Assertions.assertEquals(63.51d, ec2SsavingsPlanPrice.getCost(), DELTA);
		Assertions.assertEquals(63.51d, ec2SsavingsPlanPrice.getPrice().getCost(), DELTA);
		Assertions.assertEquals(762.12d, ec2SsavingsPlanPrice.getPrice().getCostPeriod(), DELTA);
		Assertions.assertEquals("EC2 Savings Plan, 1yr, No Upfront, c1 in eu-west-1",
				ec2SsavingsPlanPrice.getPrice().getTerm().getName());
		Assertions.assertEquals("eu-west-1", ec2SsavingsPlanPrice.getPrice().getTerm().getLocation().getName());
		Assertions.assertEquals("c1.medium", ec2SsavingsPlanPrice.getPrice().getType().getName());

		// Check the compute savings plan
		final var cSavingsPlanPrice = qiResource.lookup(subscription,
				builder().cpu(2).ram(1741).constant(true).usage("36monthCSP").build());
		Assertions.assertEquals(71.54d, cSavingsPlanPrice.getCost(), DELTA);
		Assertions.assertEquals(71.54d, cSavingsPlanPrice.getPrice().getCost(), DELTA);
		Assertions.assertEquals(858.48d, cSavingsPlanPrice.getPrice().getCostPeriod(), DELTA);
		Assertions.assertEquals("Compute Savings Plan, 1yr, All Upfront",
				cSavingsPlanPrice.getPrice().getTerm().getName());
		Assertions.assertNull(cSavingsPlanPrice.getPrice().getTerm().getLocation());
		Assertions.assertEquals("c1.medium", cSavingsPlanPrice.getPrice().getType().getName());

		// Check the RI
		final var cRIPrice = qiResource.lookup(subscription, builder().cpu(2).ram(1741).usage("36month").build());
		Assertions.assertEquals(46.667d, cRIPrice.getCost(), DELTA);
		Assertions.assertEquals(46.667d, cRIPrice.getPrice().getCost(), DELTA);
		Assertions.assertEquals(1680.0d, cRIPrice.getPrice().getCostPeriod(), DELTA);
		Assertions.assertEquals("Reserved, 3yr, All Upfront", cRIPrice.getPrice().getTerm().getName());
		Assertions.assertNull(cRIPrice.getPrice().getTerm().getLocation());
		Assertions.assertEquals("c1.medium", cRIPrice.getPrice().getType().getName());

		// Install again to check the update without change
		resetImportTask();
		resource.install(false);
		checkImportStatus(112 /* same */, 77);
		checkType();

		provResource.updateCost(subscription);
		check(provResource.getConfiguration(subscription), 449.057d, 46.667d);

		// Install again with force mode, without price change in force mode
		resource.install(true);
		check(provResource.getConfiguration(subscription), 449.057d, 46.667d);
		checkImportStatus(112 /* same */, 77);
		checkType();

		// Install again with force mode, with only specs changes in force mode
		configure(AwsPriceImportRds.CONF_URL_RDS_PRICES, "/vs/%s/index-rds.csv");
		mock("/vs/eu-west-1/index-rds.csv", "mock-server/aws/vs/index-rds.csv");
		check(provResource.getConfiguration(subscription), 449.057d, 46.667d);
		resource.install(true);
		var dtype = this.bpRepository.findByExpected("code", "TBNHT84HARTQH8TY.JRTCKXETXF.6YS6EN2CT7").getType();
		Assertions.assertEquals("db.m1.large.NEW", dtype.getName());
		Assertions.assertEquals("Intel Xeon NEW", dtype.getProcessor());
		Assertions.assertEquals("{Moderate NEW}", dtype.getDescription());
		Assertions.assertEquals(3, dtype.getCpu());
		Assertions.assertEquals(7782, dtype.getRam());
		checkImportStatus(112 - 1 /* purged RDS */ + 1 /* new RDS */
				- 1 /* purged EC2 */ + 1 /* new EC2 */, 77 + 1 /* new type */);

		// Check the v1 only prices are still available
		bpRepository.findByExpected("code", "OLD_____________.JRTCKXETXF.6YS6EN2CT7");
		ipRepository.findByExpected("code", "OLD_____________.JRTCKXETXF.6YS6EN2CT7");

		// Point to another catalog with updated prices
		configure(AwsPriceImportEc2.CONF_URL_EC2_PRICES, "/v2/%s/index-ec2.csv");
		configure(AwsPriceImportRds.CONF_URL_RDS_PRICES, "/v2/%s/index-rds.csv");
		configure(AwsPriceImportEc2.CONF_URL_EC2_PRICES_SPOT, "/v2/spot.js");
		configure(AwsPriceImportEbs.CONF_URL_EBS_PRICES, "/v2/pricing-ebs.js");
		configure(AwsPriceImportEfs.CONF_URL_EFS_PRICES, "/v2/index-efs.csv");
		configure(AwsPriceImportS3.CONF_URL_S3_PRICES, "/v2/index-s3.csv");
		mock("/v2/eu-west-1/index-ec2.csv", "mock-server/aws/v2/index-ec2.csv");
		mock("/v2/eu-west-1/index-rds.csv", "mock-server/aws/v2/index-rds.csv");

		// Install the new catalog, update/deletion occurs
		// Code 'HF7N6NNE7N8GDMBE' and '000000000000_NEW' are deleted
		resetImportTask();
		resource.install(false);
		provResource.updateCost(subscription);

		// Check the v1 only prices are unavailable
		Assertions.assertNull(bpRepository.findBy("code", "OLD_____________.JRTCKXETXF.6YS6EN2CT7"));
		Assertions.assertNull(ipRepository.findBy("code", "OLD_____________.JRTCKXETXF.6YS6EN2CT7"));

		// Check the v2 only price
		bpRepository.findByExpected("code", "NEW_____________.JRTCKXETXF.6YS6EN2CT7");
		ipRepository.findByExpected("code", "NEW_____________.JRTCKXETXF.6YS6EN2CT7");

		// Check the new price
		final QuoteVo newQuote = provResource.getConfiguration(subscription);
		Assertions.assertEquals(448.94d, newQuote.getCost().getMin(), DELTA);

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

		var type = price.getType();
		Assertions.assertEquals("c1.medium", type.getName());
		Assertions.assertEquals("{1 x 350,Moderate}", type.getDescription());

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
		Assertions.assertEquals("{EBS only,20 Gigabit}", type.getDescription());

		// Check status
		checkImportStatus(110 + 2 /* saving plans */ + 1 - 1 /* purged RDS price */ /* new RDS price */
				- 1 /* purged EC2 price */ + 1 /* new EC2 price */, 77 + 1);
	}

	private void checkType() {
		var type = this.bpRepository.findByExpected("code", "TBNHT84HARTQH8TY.JRTCKXETXF.6YS6EN2CT7").getType();
		Assertions.assertEquals("db.m1.large", type.getName());
		Assertions.assertEquals("Intel Xeon", type.getProcessor());
		Assertions.assertEquals(2, type.getCpu());
		Assertions.assertEquals("{Moderate}", type.getDescription());
		Assertions.assertEquals(7680, type.getRam());
	}

	private void checkImportStatus(final int count, final int nbTypes) {
		final ImportCatalogStatus status = this.resource.getImportCatalogResource().getTask("service:prov:aws");
		Assertions.assertTrue(status.getDone() >= 9);
		Assertions.assertEquals(21, status.getWorkload());
		Assertions.assertEquals("efs", status.getPhase());
		Assertions.assertEquals(DEFAULT_USER, status.getAuthor());
		Assertions.assertEquals(nbTypes, status.getNbInstanceTypes().intValue());
		Assertions.assertEquals(count, status.getNbInstancePrices().intValue());
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

		// Saving plan
		mock("/region_index.json", "mock-server/aws/region_index.json");
		mock("/savings-plan-eu-west-1-index.json", "mock-server/aws/savings-plan-eu-west-1-index.json");

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
	void installOnLine() throws Exception {
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
	 * No OnDemand prices to skip SavingsPlan process.
	 */
	@Test
	void installSavingsPlanNoOnDemand() throws Exception {
		// Install a new configuration
		applicationContext.getBean(SystemConfigurationRepository.class).findAll();
		initSpringSecurityContext(DEFAULT_USER);
		clearAllCache();

		patchConfigurationUrl();

		mock("/index-ec2.csv", "mock-server/aws/index-ec2-empty.csv");
		mock("/spot.js", "mock-server/aws/spot-empty.js");
		mock("/pricing-ebs.js", "mock-server/aws/pricing-ebs.js");
		mock("/index-rds.csv", "mock-server/aws/index-rds-empty.csv");
		mock("/index-efs.csv", "mock-server/aws/index-efs-empty.csv");
		mock("/index-s3.csv", "mock-server/aws/index-s3-empty.csv");
		httpServer.start();

		resource.install(false);
		em.flush();
		em.clear();
		Assertions.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), DELTA);

		// No instance imported
		Assertions.assertEquals(0, itRepository.findAll().size());
	}

	/**
	 * Unable to retrieve the EC2 SavingsPlan JSON file
	 */
	@Test
	void installEc2SavingsPlanIndexNotFound() throws Exception {
		// Install a new configuration
		applicationContext.getBean(SystemConfigurationRepository.class).findAll();
		initSpringSecurityContext(DEFAULT_USER);
		clearAllCache();
		configure(AwsPriceImportEc2.CONF_URL_API_SAVINGS_PLAN, "/any.json");
		mock("/region_index.json", "mock-server/aws/region_index.json");

		mockOnlyEc2();
		checkNoSavingsPlan();
	}

	/**
	 * Unable to retrieve the EC2 SavingsPlan JSON file
	 */
	@Test
	void installEc2SavingsPlanRegionFileNotFound() throws Exception {
		// Install a new configuration
		applicationContext.getBean(SystemConfigurationRepository.class).findAll();
		initSpringSecurityContext(DEFAULT_USER);
		clearAllCache();
		configure(AwsPriceImportEc2.CONF_URL_API_SAVINGS_PLAN, "/region_index.json");
		mock("/region_index.json", "mock-server/aws/region_index-err.json");

		mockOnlyEc2();
		checkNoSavingsPlan();
	}

	private void checkNoSavingsPlan() throws IOException, URISyntaxException {
		resource.install(false);
		em.flush();
		em.clear();
		Assertions.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), DELTA);

		// Only OD+RI prices have been imported
		Assertions.assertEquals(74, itRepository.findAll().size());
		Assertions.assertEquals(3, iptRepository.findAll().size()); // RI3y, OD, SPOT
	}

	private void mockOnlyEc2() throws IOException {
		mock("/index-ec2.csv", "mock-server/aws/index-ec2.csv");
		mock("/spot.js", "mock-server/aws/spot-empty.js");
		mock("/pricing-ebs.js", "mock-server/aws/pricing-ebs.js");
		mock("/index-rds.csv", "mock-server/aws/index-rds-empty.csv");
		mock("/index-efs.csv", "mock-server/aws/index-efs-empty.csv");
		mock("/index-s3.csv", "mock-server/aws/index-s3-empty.csv");
		patchConfigurationUrl();
		configuration.put(AwsPriceImportBase.CONF_REGIONS, "eu-west-1"); // Only one region for UTs
		httpServer.start();
	}

	/**
	 * Unable to retrieve the EC2 CSV file
	 */
	@Test
	void installEc2CsvNotFound() throws Exception {
		patchConfigurationUrl();
		configure(AwsPriceImportEc2.CONF_URL_EC2_PRICES, "/any.csv");
		mockServerNoEc2();
		resource.install(false);
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
	void installEc2CsvInvalidHeader() throws Exception {
		patchConfigurationUrl();
		mock("/index-ec2.csv", "mock-server/aws/index-header-not-found.csv");
		mockServerNoEc2();

		Assertions.assertEquals("Premature end of CSV file, headers were not found",
				Assertions.assertThrows(TechnicalException.class, () -> {
					resource.install(false);
				}).getMessage());
	}

	/**
	 * Invalid EFS CSV file
	 */
	@Test
	void installEfsCsvInvalidHeader() throws Exception {
		patchConfigurationUrl();
		configure(AwsPriceImportEfs.CONF_URL_EFS_PRICES, "/index-efs-error.csv");
		mock("/index-efs-error.csv", "mock-server/aws/index-error.csv");
		mock("/index-s3.csv", "mock-server/aws/index-s3.csv");
		httpServer.start();

		Assertions.assertEquals("Premature end of CSV file, headers were not found",
				Assertions.assertThrows(TechnicalException.class, () -> {
					resource.install(false);
				}).getMessage());
	}

	/**
	 * Invalid S3 CSV file
	 */
	@Test
	void installS3CsvInvalidHeader() throws Exception {
		patchConfigurationUrl();
		mock("/index-s3.csv", "mock-server/aws/index-error.csv");
		httpServer.start();

		Assertions.assertEquals("Premature end of CSV file, headers were not found",
				Assertions.assertThrows(TechnicalException.class, () -> {
					resource.install(false);
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
	void installSpotEmpty() throws Exception {
		configure(AwsPriceImportEc2.CONF_URL_EC2_PRICES, "/index-ec2.csv");
		configure(AwsPriceImportRds.CONF_URL_RDS_PRICES, "/index-rds.csv");
		configure(AwsPriceImportEc2.CONF_URL_EC2_PRICES_SPOT, "/any.js");
		configure(AwsPriceImportS3.CONF_URL_S3_PRICES, "/index-s3.csv");
		configure(AwsPriceImportEbs.CONF_URL_EBS_PRICES, "/any.js");
		configure(AwsPriceImportEfs.CONF_URL_EFS_PRICES, "/index-efs.csv");
		mock("/index-ec2.csv", "mock-server/aws/index-ec2-empty.csv");
		mock("/region_index.json", "mock-server/aws/region_index-empty.json");
		mock("/index-rds.csv", "mock-server/aws/index-rds-empty.csv");
		mock("/index-s3.csv", "mock-server/aws/index-s3-empty.csv");
		mock("/index-efs.csv", "mock-server/aws/index-efs-empty.csv");
		httpServer.start();

		// Check the reserved
		resource.install(false);
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
	void installSpotError() throws Exception {
		patchConfigurationUrl();
		mock("/index-ec2.csv", "mock-server/aws/index-ec2.csv");
		mock("/region_index.json", "mock-server/aws/region_index-empty.json");
		mock("/index-rds.csv", "mock-server/aws/index-rds.csv");
		mock("/spot.js", "mock-server/aws/spot-error.js");
		httpServer.start();

		// Parse error expected
		Assertions.assertEquals("For input string: \"AAAAAA\"",
				Assertions.assertThrows(NumberFormatException.class, () -> {
					resource.install(false);
				}).getMessage());
	}

	private void patchConfigurationUrl() {
		configure(AwsPriceImportEc2.CONF_URL_EC2_PRICES, "/index-ec2.csv");
		configure(AwsPriceImportEc2.CONF_URL_API_SAVINGS_PLAN, "/region_index.json");
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
	void installSpotInstanceBrokenReference() throws Exception {
		patchConfigurationUrl();
		mock("/index-efs.csv", "mock-server/aws/index-efs.csv");
		mock("/index-s3.csv", "mock-server/aws/index-s3.csv");
		mock("/index-ec2.csv", "mock-server/aws/index-ec2.csv");
		mock("/region_index.json", "mock-server/aws/region_index-empty.json");
		mock("/index-rds.csv", "mock-server/aws/index-rds.csv");
		mock("/spot.js", "mock-server/aws/spot-unavailable-instance.js");
		httpServer.start();

		// Parse error expected
		resource.install(false);

		// The unique spot could not be installed
		Assertions.assertEquals(0, ipRepository.findAllBy("type.name", "Spot").size());
	}

	/**
	 * No data available from the AWS end-points
	 */
	@Test
	void installSpotRegionNotFound() throws Exception {
		patchConfigurationUrl();
		mock("/index-efs.csv", "mock-server/aws/index-efs.csv");
		mock("/index-ec2.csv", "mock-server/aws/index-ec2-empty.csv");
		mock("/region_index.json", "mock-server/aws/region_index-empty.json");
		mock("/index-rds.csv", "mock-server/aws/index-rds-empty.csv");
		mock("/index-s3.csv", "mock-server/aws/index-s3.csv");
		mock("/spot.js", "mock-server/aws/spot-empty.js");
		httpServer.start();

		resource.install(false);
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
		resource.install(false);
		em.flush();
		em.clear();
		Assertions.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), DELTA);

		final ProvUsage usage = new ProvUsage();
		usage.setName("36month");
		usage.setRate(100);
		usage.setDuration(36);
		usage.setConfiguration(repository.findBy("subscription.id", subscription));
		usage.setReservation(true);
		em.persist(usage);

		final ProvUsage usageCSP = new ProvUsage();
		usageCSP.setName("36monthCSP");
		usageCSP.setRate(100);
		usageCSP.setDuration(36);
		usageCSP.setConfiguration(repository.findBy("subscription.id", subscription));
		usageCSP.setReservation(false);
		usageCSP.setConvertibleOs(true);
		usageCSP.setConvertibleType(true);
		usageCSP.setConvertibleFamily(true);
		usageCSP.setConvertibleEngine(false);
		usageCSP.setConvertibleLocation(true);
		em.persist(usageCSP);

		final ProvUsage usageEC2SP = new ProvUsage();
		usageEC2SP.setName("36monthEC2SP");
		usageEC2SP.setRate(100);
		usageEC2SP.setDuration(36);
		usageEC2SP.setConfiguration(repository.findBy("subscription.id", subscription));
		usageEC2SP.setReservation(false);
		usageEC2SP.setConvertibleOs(true);
		usageEC2SP.setConvertibleType(true);
		usageEC2SP.setConvertibleFamily(false);
		usageEC2SP.setConvertibleEngine(false);
		usageEC2SP.setConvertibleLocation(false);
		em.persist(usageEC2SP);
		em.flush();
		em.clear();

		// Request an instance that would not be a Spot
		final QuoteInstanceLookup lookup = qiResource.lookup(subscription,
				builder().cpu(2).ram(1741).constant(true).usage("36month").type("c1.medium").build());
		Assertions.assertEquals("HB5V2X8TXQUTDZBV.NQ3QZPMQV9.6YS6EN2CT7", lookup.getPrice().getCode());

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
		var slookup = qsResource.lookup(subscription,
				QuoteStorageQuery.builder().size(5).latency(Rate.GOOD).instance(server1()).build()).get(0);
		Assertions.assertEquals("eu-west-1-gp2", slookup.getPrice().getCode());
		final QuoteStorageEditionVo svo = new QuoteStorageEditionVo();
		svo.setQuoteInstance(server1());
		svo.setSize(5);
		svo.setType(slookup.getPrice().getType().getName());
		svo.setName("sda1");
		svo.setSubscription(subscription);
		final UpdatedCost createStorage = qsResource.create(svo);
		Assertions.assertTrue(createStorage.getCost().getMin() >= 0.5);
		Assertions.assertTrue(createStorage.getTotal().getMin() > 40);
		Assertions.assertEquals("gp2", qsRepository.findOne(createStorage.getId()).getPrice().getType().getName());

		// Add storage (EFS) to this quote
		slookup = qsResource.lookup(subscription,
				QuoteStorageQuery.builder().latency(Rate.GOOD).optimized(ProvStorageOptimized.THROUGHPUT).build())
				.get(0);
		Assertions.assertEquals("WDJR7Q9RKV87VCVK", slookup.getPrice().getCode());
		final QuoteStorageEditionVo svo2 = new QuoteStorageEditionVo();
		svo2.setSize(1);
		svo2.setOptimized(ProvStorageOptimized.THROUGHPUT);
		svo2.setType(slookup.getPrice().getType().getName());
		svo2.setName("nfs1");
		svo2.setSubscription(subscription);
		final UpdatedCost createEfs = qsResource.create(svo2);
		Assertions.assertEquals(0.33, createEfs.getCost().getMin(), DELTA);
		Assertions.assertEquals("efs", qsRepository.findOne(createEfs.getId()).getPrice().getType().getName());

		// Add storage (S3) to this quote
		slookup = qsResource.lookup(subscription,
				QuoteStorageQuery.builder().latency(Rate.MEDIUM).optimized(ProvStorageOptimized.DURABILITY).build())
				.get(0);
		Assertions.assertEquals("QESS8VZ4CR8YK5WX", slookup.getPrice().getCode());
		final ProvStorageType type = slookup.getPrice().getType();
		Assertions.assertEquals(99.5d, type.getAvailability(), 0.000000001d);
		Assertions.assertEquals(11, type.getDurability9().intValue());

		final QuoteStorageEditionVo svo3 = new QuoteStorageEditionVo();
		svo3.setSize(1);
		svo3.setOptimized(ProvStorageOptimized.DURABILITY);
		svo3.setType(slookup.getPrice().getType().getName());
		svo3.setName("my-bucket");
		svo3.setSubscription(subscription);
		final UpdatedCost createS3 = qsResource.create(svo3);
		Assertions.assertEquals(0.01, createS3.getCost().getMin(), DELTA);
		Assertions.assertEquals("s3-z-ia", qsRepository.findOne(createS3.getId()).getPrice().getType().getName());

		// Request a database
		var blookup = qbResource.lookup(subscription,
				QuoteDatabaseQuery.builder().cpu(4).ram(1741).constant(false).engine("MYSQL").build());
		Assertions.assertEquals("FQ2P47XZ3KZ97A3P.JRTCKXETXF.6YS6EN2CT7", blookup.getPrice().getCode());
		Assertions.assertFalse(blookup.getPrice().getType().getConstant().booleanValue());
		Assertions.assertNull(blookup.getPrice().getLicense());
		Assertions.assertEquals("MYSQL", blookup.getPrice().getEngine());
		Assertions.assertNull(blookup.getPrice().getEdition());
		Assertions.assertNull(blookup.getPrice().getStorageEngine());
		Assertions.assertNull(blookup.getPrice().getInitialCost());
		Assertions.assertEquals("OnDemand", blookup.getPrice().getTerm().getName());

		final QuoteDatabaseEditionVo qb1 = new QuoteDatabaseEditionVo();
		qb1.setCpu(4);
		qb1.setRam(1741);
		qb1.setConstant(false);
		qb1.setPhysical(false);
		qb1.setEngine("MYSQL");
		qb1.setSubscription(subscription);
		qb1.setPrice(blookup.getPrice().getId());
		qb1.setName("database1");
		final UpdatedCost createDb = qbResource.create(qb1);
		Assertions.assertEquals("db.t2.xlarge", qbRepository.findOne(createDb.getId()).getPrice().getType().getName());

		blookup = qbResource.lookup(subscription, QuoteDatabaseQuery.builder().cpu(2).ram(1741).type("db.r5.large")
				.license("BYOL").engine("ORACLE").edition("ENTERPRISE").build());
		Assertions.assertEquals("68NGR9GHC49W62UR.JRTCKXETXF.6YS6EN2CT7", blookup.getPrice().getCode());
		Assertions.assertTrue(blookup.getPrice().getType().getConstant().booleanValue());
		Assertions.assertEquals("BYOL", blookup.getPrice().getLicense());
		Assertions.assertEquals("ORACLE", blookup.getPrice().getEngine());
		Assertions.assertEquals("ENTERPRISE", blookup.getPrice().getEdition());
		Assertions.assertNull(blookup.getPrice().getStorageEngine());
		Assertions.assertNull(blookup.getPrice().getInitialCost());

		final QuoteDatabaseEditionVo qb2 = new QuoteDatabaseEditionVo();
		qb2.setCpu(2);
		qb2.setRam(1741);
		qb2.setEngine("ORACLE");
		qb2.setEdition("ENTERPRISE");
		qb2.setLicense("BYOL");
		qb2.setType("db.r5.large");
		qb2.setSubscription(subscription);
		qb2.setPrice(blookup.getPrice().getId());
		qb2.setName("database2");
		final UpdatedCost createDb2 = qbResource.create(qb2);
		Assertions.assertEquals("db.r5.large", qbRepository.findOne(createDb2.getId()).getPrice().getType().getName());

		return provResource.getConfiguration(subscription);
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is only one subscription for a service.
	 */
	private int getSubscription(final String project) {
		return getSubscription(project, ProvAwsPluginResource.KEY);
	}
}
