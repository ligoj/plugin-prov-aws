/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.ligoj.app.plugin.prov.aws.catalog.AwsPriceImportBase.CONF_URL_AWS_PRICES;
import static org.ligoj.app.plugin.prov.quote.instance.QuoteInstanceQuery.builder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import org.ligoj.app.plugin.prov.aws.ProvAwsPluginResource;
import org.ligoj.app.plugin.prov.aws.catalog.efs.AwsPriceImportEfs;
import org.ligoj.app.plugin.prov.aws.catalog.lambda.AwsPriceImportLambda;
import org.ligoj.app.plugin.prov.aws.catalog.s3.AwsPriceImportS3;
import org.ligoj.app.plugin.prov.aws.catalog.suppport.AwsPriceImportSupport;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AwsEc2Price;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.AwsPriceImportEc2;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.CsvForBeanEc2;
import org.ligoj.app.plugin.prov.aws.catalog.vm.fargate.AwsPriceImportFargate;
import org.ligoj.app.plugin.prov.aws.catalog.vm.rds.AwsPriceImportRds;
import org.ligoj.app.plugin.prov.catalog.AbstractImportCatalogResource;
import org.ligoj.app.plugin.prov.catalog.ImportCatalogResource;
import org.ligoj.app.plugin.prov.dao.ProvContainerPriceRepository;
import org.ligoj.app.plugin.prov.dao.ProvContainerTypeRepository;
import org.ligoj.app.plugin.prov.dao.ProvDatabasePriceRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceTermRepository;
import org.ligoj.app.plugin.prov.dao.ProvInstanceTypeRepository;
import org.ligoj.app.plugin.prov.dao.ProvQuoteDatabaseRepository;
import org.ligoj.app.plugin.prov.dao.ProvQuoteInstanceRepository;
import org.ligoj.app.plugin.prov.dao.ProvQuoteRepository;
import org.ligoj.app.plugin.prov.dao.ProvQuoteStorageRepository;
import org.ligoj.app.plugin.prov.model.ProvBudget;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvQuote;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;
import org.ligoj.app.plugin.prov.model.ProvQuoteStorage;
import org.ligoj.app.plugin.prov.model.ProvStorageOptimized;
import org.ligoj.app.plugin.prov.model.ProvTenancy;
import org.ligoj.app.plugin.prov.model.ProvUsage;
import org.ligoj.app.plugin.prov.model.Rate;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.app.plugin.prov.quote.container.ProvQuoteContainerResource;
import org.ligoj.app.plugin.prov.quote.container.QuoteContainerQuery;
import org.ligoj.app.plugin.prov.quote.database.ProvQuoteDatabaseResource;
import org.ligoj.app.plugin.prov.quote.database.QuoteDatabaseEditionVo;
import org.ligoj.app.plugin.prov.quote.database.QuoteDatabaseQuery;
import org.ligoj.app.plugin.prov.quote.instance.ProvQuoteInstanceResource;
import org.ligoj.app.plugin.prov.quote.instance.QuoteInstanceEditionVo;
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
	private ProvQuoteContainerResource qcResource;

	@Autowired
	private ProvQuoteDatabaseResource qbResource;

	@Autowired
	private ProvQuoteStorageResource qsResource;

	@Autowired
	private ProvInstancePriceTermRepository iptRepository;

	@Autowired
	private ProvInstancePriceRepository ipRepository;

	@Autowired
	private ProvContainerPriceRepository cpRepository;

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
	private ProvContainerTypeRepository ctRepository;

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
		final var helper = new ImportCatalogResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(helper);
		this.resource = initCatalog(helper, new AwsPriceImport());
		this.resource.setBase(initCatalog(helper, new AwsPriceImportBase()));
		this.resource.setEc2(initCatalog(helper, new AwsPriceImportEc2() {
			@Override
			public AwsPriceImportEc2 newProxy() {
				return this;
			}

		}));
		this.resource.setFargate(initCatalog(helper, new AwsPriceImportFargate() {
			@Override
			public AwsPriceImportFargate newProxy() {
				return this;
			}

		}));
		this.resource.setLambda(initCatalog(helper, new AwsPriceImportLambda() {
			@Override
			public AwsPriceImportLambda newProxy() {
				return this;
			}

		}));
		this.resource.setRds(initCatalog(helper, new AwsPriceImportRds() {
			@Override
			public AwsPriceImportRds newProxy() {
				return this;
			}
		}));
		this.resource.setS3(initCatalog(helper, new AwsPriceImportS3()));
		this.resource.setEfs(initCatalog(helper, new AwsPriceImportEfs()));
		this.resource.setSupport(initCatalog(helper, new AwsPriceImportSupport()));
		configuration.put(ProvResource.USE_PARALLEL, "0");
		configuration.put(CONF_URL_AWS_PRICES, "http://localhost:" + MOCK_PORT);
		configure(AwsPriceImportFargate.CONF_URL_FARGATE_PRICES_SPOT, "/spot-fargate.json");
		configure(AwsPriceImportEc2.CONF_URL_EC2_PRICES_SPOT, "/spot.js");

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
	@Test
	void dummyCoverage() throws IOException {
		new AwsEc2Price().getDrop();
		new AwsPriceImportEc2().newProxy();
		new AwsPriceImportRds().newProxy();
		new AwsPriceImportFargate().newProxy();
		new AwsPriceImportLambda().newProxy();
	}

	/**
	 * Invalid EC2 CSV header
	 */
	@Test
	void installInvalidHeader() throws IOException {
		final var reader = new BufferedReader(new StringReader("any"));
		Assertions.assertEquals("Premature end of CSV file, headers were not found",
				Assertions.assertThrows(TechnicalException.class, () -> new CsvForBeanEc2(reader)).getMessage());
	}

	void mock404(final String url) throws IOException {
		httpServer.stubFor(get(urlEqualTo(url)).willReturn(aResponse().withStatus(HttpStatus.SC_NOT_FOUND)));
	}

	void mock(final String url, final String file) throws IOException {
		httpServer.stubFor(get(urlEqualTo(url)).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource(file).getInputStream(), StandardCharsets.UTF_8))));

	}

	void configure(final String configuration, final String url) {
		this.configuration.put(configuration, "http://localhost:" + MOCK_PORT + url);
	}

	private void mockOffer(final String url) throws IOException {
		mock("/offers/v1.0/aws" + url, "mock-server/aws/offers/v1.0/aws" + url);
	}

	private void mockSavingsPlan(final String url) throws IOException {
		mock("/savingsPlan/v1.0/aws" + url, "mock-server/aws/savingsPlan/v1.0/aws" + url);
	}

	private void mockService(final String service, final String version) throws IOException {
		if ("-vs".equals(version) && !"AmazonRDS".equals(service)) {
			return;
		}
		if (SERVICES_MULTI_REGION.contains(service)) {
			mockOffer("/" + service + "/current/index" + version + ".csv");
			if (version.equals("")) {
				mockOffer("/" + service + "/current/empty.csv");
			}
		} else {
			mockOffer("/" + service + "/current/region_index" + version + ".json");
			if (version.equals("")) {
				mockOffer("/" + service + "/current/default/empty.csv");
			}
			mockOffer("/" + service + "/current/eu-west-1/index" + version + ".csv");
		}
	}

	private Set<String> SERVICES_MULTI_REGION = Set.of("AmazonS3", "AmazonEFS");
	private Set<String> SERVICES_REGION = Set.of("AWSLambda", "AmazonEC2", "AmazonECS", "AmazonRDS", "AmazonS3",
			"AmazonEFS");
	private Set<String> SERVICES = Stream.concat(SERVICES_MULTI_REGION.stream(), SERVICES_REGION.stream())
			.collect(Collectors.toSet());

	private void mockServices(final String version) throws IOException {
		mock("/offers/v1.0/aws/index.json", "mock-server/aws/offers/v1.0/aws/index" + version + ".json");
		for (var service : SERVICES) {
			mockService(service, version);
		}
	}

	private void mockAll() throws IOException {
		mockServices("");
		mockSavingsPlan("/AWSComputeSavingsPlan/current/region_index.json");
		mockSavingsPlan("/AWSComputeSavingsPlan/current/region_index-empty.json");
		mockSavingsPlan("/AWSComputeSavingsPlan/current/eu-west-1/index.json");
		mockSavingsPlan("/AWSComputeSavingsPlan/current/default/empty.json");
		mock("/spot.js", "mock-server/aws/spot.js");
		mock("/spot-fargate.json", "mock-server/aws/spot-fargate.json");

	}

	private void mockEmpty() throws IOException {
		mock("/offers/v1.0/aws/index.json", "mock-server/aws/offers/v1.0/aws/index-empty.json");
		mockSavingsPlan("/AWSComputeSavingsPlan/current/default/empty.json");
	}

	@Test
	void installOffLine() throws Exception {
		// Install a new configuration
		mockAll();
		applicationContext.getBean(SystemConfigurationRepository.class).findAll();
		initSpringSecurityContext(DEFAULT_USER);
		startMockServer();

		// Check the basic quote
		final var quote = installAndConfigure();

		// Check the whole quote
		final var instance = check(quote, 448.793d, 46.667d);
		final var subscription = instance.getConfiguration().getSubscription().getId();

		// Check the v1 only prices
		bpRepository.findByExpected("code", "OLD_____________.JRTCKXETXF.6YS6EN2CT7");
		ipRepository.findByExpected("code", "OLD_____________.JRTCKXETXF.6YS6EN2CT7");

		// Check the spot
		final var spotPrice = qiResource.lookup(subscription,
				builder().cpu(2).ram(1741).constant(true).ephemeral(true).build());
		Assertions.assertEquals("spot-eu-west-1-r4.large-LINUX", spotPrice.getPrice().getCode());
		Assertions.assertEquals(12.629, spotPrice.getCost(), DELTA);
		Assertions.assertEquals(12.629d, spotPrice.getPrice().getCost(), DELTA);
		Assertions.assertEquals(12.629d, spotPrice.getPrice().getCostPeriod(), DELTA);
		Assertions.assertEquals("Spot", spotPrice.getPrice().getTerm().getName());
		Assertions.assertTrue(spotPrice.getPrice().getTerm().isEphemeral());
		Assertions.assertEquals("r4.large", spotPrice.getPrice().getType().getName());
		Assertions.assertEquals(6, ipRepository.findAllBy("term.name", "Spot").size());

		Assertions.assertEquals("eu-west-1", spotPrice.getPrice().getLocation().getName());
		Assertions.assertEquals("EU (Ireland)", spotPrice.getPrice().getLocation().getDescription());

		Assertions.assertEquals(81, ipRepository.findAllBy("term.code", "JRTCKXETXF").size()); // EC2 OD
		Assertions.assertEquals(3, ipRepository.findAllBy("term.code", "NQ3QZPMQV9").size()); // EC2 Reserved 3y
		Assertions.assertEquals(6, ipRepository.findAllBy("term.code", "spot").size()); // EC2 Spot
		Assertions.assertEquals(1, ipRepository.findAllBy("term.code", "7DVU5XBSTGHBTJUV").size()); // EC2 SP
		Assertions.assertEquals(1, ipRepository.findAllBy("term.code", "8GU23DFTKP2N43SD").size()); // Compute SP
		Assertions.assertEquals(50, cpRepository.findAllBy("term.code", "JRTCKXETXF").size()); // Fargate OD
		Assertions.assertEquals(50, cpRepository.findAllBy("term.code", "ZGC49G7XS8QA54BQ").size()); // Fargate Compute
																										// SP
		Assertions.assertEquals(100, cpRepository.findAllBy("term.code", "spot").size()); // Fargate Spot x 2 regions
		Assertions.assertEquals(20, bpRepository.findAll().size()); // RDS

		Assertions.assertEquals(50, ctRepository.findAll().size()); // Fargate type

		checkImportStatus(81 /* OD */ + 3 /* RI */ + 6 /* spot */ + 2 /* SP */ + 20 /* RDS */ + 200 /* Fargate */ ,
				77 + 50 /* Fargate */ );

		// Check EC2 savings plan
		final var ec2SsavingsPlanPrice = qiResource.lookup(subscription,
				builder().cpu(2).ram(1741).constant(true).usage("36monthEC2SP").build());
		Assertions.assertEquals("7DVU5XBSTGHBTJUV.M2WTFX8JK6VDUNU5", ec2SsavingsPlanPrice.getPrice().getCode());
		Assertions.assertEquals(63.51d, ec2SsavingsPlanPrice.getCost(), DELTA);
		Assertions.assertEquals(63.51d, ec2SsavingsPlanPrice.getPrice().getCost(), DELTA);
		Assertions.assertEquals(762.12d, ec2SsavingsPlanPrice.getPrice().getCostPeriod(), DELTA);
		Assertions.assertEquals("EC2 Savings Plan, 1yr, No Upfront, c1 in eu-west-1",
				ec2SsavingsPlanPrice.getPrice().getTerm().getName());
		Assertions.assertFalse(ec2SsavingsPlanPrice.getPrice().getTerm().getInitialCost().booleanValue());
		Assertions.assertEquals("eu-west-1", ec2SsavingsPlanPrice.getPrice().getTerm().getLocation().getName());
		Assertions.assertEquals("c1.medium", ec2SsavingsPlanPrice.getPrice().getType().getName());

		// Check Compute savings plan for EC2
		final var cSavingsPlanPrice = qiResource.lookup(subscription,
				builder().cpu(2).ram(1741).constant(true).usage("36monthCSP").build());
		Assertions.assertEquals("8GU23DFTKP2N43SD.29QDFBY626457QNP", cSavingsPlanPrice.getPrice().getCode());
		Assertions.assertEquals("Compute Savings Plan, 1yr, All Upfront",
				cSavingsPlanPrice.getPrice().getTerm().getName());
		Assertions.assertEquals(71.54d, cSavingsPlanPrice.getCost(), DELTA);
		Assertions.assertEquals(71.54d, cSavingsPlanPrice.getPrice().getCost(), DELTA);
		Assertions.assertEquals(858.48d, cSavingsPlanPrice.getPrice().getCostPeriod(), DELTA);
		Assertions.assertTrue(cSavingsPlanPrice.getPrice().getTerm().getInitialCost().booleanValue());
		Assertions.assertNull(cSavingsPlanPrice.getPrice().getTerm().getLocation());
		Assertions.assertEquals("c1.medium", cSavingsPlanPrice.getPrice().getType().getName());

		// Check EC2 RI
		final var cRIPrice = qiResource.lookup(subscription, builder().cpu(2).ram(1741).usage("36month").build());
		Assertions.assertEquals(46.667d, cRIPrice.getCost(), DELTA);
		Assertions.assertEquals(46.667d, cRIPrice.getPrice().getCost(), DELTA);
		Assertions.assertEquals(1680.0d, cRIPrice.getPrice().getCostPeriod(), DELTA);
		Assertions.assertEquals("Reserved, 3yr, All Upfront", cRIPrice.getPrice().getTerm().getName());
		Assertions.assertTrue(cRIPrice.getPrice().getTerm().getInitialCost().booleanValue());
		Assertions.assertNull(cRIPrice.getPrice().getTerm().getLocation());
		Assertions.assertEquals("c1.medium", cRIPrice.getPrice().getType().getName());

		// Check Fargate OnDemand
		final var cPrice = qcResource.lookup(subscription, QuoteContainerQuery.builder().cpu(2).ram(1741).build());
		Assertions.assertEquals(72.08d, cPrice.getCost(), DELTA);
		Assertions.assertEquals(72.08d, cPrice.getPrice().getCost(), DELTA);
		Assertions.assertEquals(72.08d, cPrice.getPrice().getCostPeriod(), DELTA);
		Assertions.assertEquals("OnDemand", cPrice.getPrice().getTerm().getName());
		Assertions.assertEquals("K4EXFQ5YFQCP98EN.JRTCKXETXF.6YS6EN2CT7|2.0|4.0", cPrice.getPrice().getCode());
		Assertions.assertEquals("fargate-2.0-4.0", cPrice.getPrice().getType().getName());

		// Check Fargate Saving Plan
		final var cSPPrice = qcResource.lookup(subscription,
				QuoteContainerQuery.builder().cpu(2).ram(1741).usage("36monthCSP").build());
		Assertions.assertEquals(43.248d, cSPPrice.getCost(), DELTA);
		Assertions.assertEquals(43.248d, cSPPrice.getPrice().getCost(), DELTA);
		Assertions.assertEquals(1556.932d, cSPPrice.getPrice().getCostPeriod(), DELTA);
		Assertions.assertEquals("ZGC49G7XS8QA54BQ.K4EXFQ5YFQCP98EN|2.0|4.0", cSPPrice.getPrice().getCode());
		Assertions.assertEquals("Compute Savings Plan, 3yr, No Upfront", cSPPrice.getPrice().getTerm().getName());
		Assertions.assertFalse(cSPPrice.getPrice().getTerm().getInitialCost().booleanValue());
		Assertions.assertNull(cSPPrice.getPrice().getTerm().getLocation());
		Assertions.assertEquals("fargate-2.0-4.0", cSPPrice.getPrice().getType().getName());

		// Check Fargate Spot
		final var cSPrice = qcResource.lookup(subscription,
				QuoteContainerQuery.builder().cpu(2).ram(1741).ephemeral(true).build());
		Assertions.assertEquals(24.566d, cSPrice.getCost(), DELTA);
		Assertions.assertEquals(24.566d, cSPrice.getPrice().getCost(), DELTA);
		Assertions.assertEquals(24.566d, cSPrice.getPrice().getCostPeriod(), DELTA);
		Assertions.assertEquals("Spot", cSPrice.getPrice().getTerm().getName());
		Assertions.assertFalse(cSPrice.getPrice().getTerm().getInitialCost().booleanValue());
		Assertions.assertNull(cSPrice.getPrice().getTerm().getLocation());
		Assertions.assertEquals("fargate-2.0-4.0", cSPrice.getPrice().getType().getName());

		// Install again to check the update without change
		resetImportTask();
		resource.install(false);
		checkImportStatus(312 /* same */, 77 + 50 /* Fargate */);
		checkType();

		provResource.updateCost(subscription);
		check(provResource.getConfiguration(subscription), 448.793d, 46.667d);

		// Install again with force mode, without price change in force mode
		resetImportTask();
		resource.install(true);
		check(provResource.getConfiguration(subscription), 448.793d, 46.667d);
		checkImportStatus(312 /* same */, 77 + 50 /* Fargate */);
		checkType();

		// Install again with force mode, with only specs changes in force mode
		mockServices("-vs");
		check(provResource.getConfiguration(subscription), 448.793d, 46.667d);
		resetImportTask();
		resource.install(true);
		var dtype = this.bpRepository.findByExpected("code", "TBNHT84HARTQH8TY.JRTCKXETXF.6YS6EN2CT7").getType();
		Assertions.assertEquals("db.m1.large.NEW", dtype.getName());
		Assertions.assertEquals("Intel Xeon NEW", dtype.getProcessor());
		Assertions.assertEquals("{Moderate NEW}", dtype.getDescription());
		Assertions.assertEquals(3, dtype.getCpu());
		Assertions.assertEquals(7782, dtype.getRam());
		checkImportStatus(312 - 1 /* purged RDS */ + 1 /* new RDS */
				- 1 /* purged EC2 */ + 1 /* new EC2 */, 77 + 50 /* Fargate */ + 1 /* new type */);

		// Check the v1 only prices are still available
		bpRepository.findByExpected("code", "OLD_____________.JRTCKXETXF.6YS6EN2CT7");
		ipRepository.findByExpected("code", "OLD_____________.JRTCKXETXF.6YS6EN2CT7");

		// Point to another catalog with updated prices
		mockServices("-v2");
		configure(AwsPriceImportEc2.CONF_URL_EC2_PRICES_SPOT, "/v2/spot.js");
		configure(AwsPriceImportFargate.CONF_URL_FARGATE_PRICES_SPOT, "/v2/spot-fargate.json");

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
		final var newQuote = provResource.getConfiguration(subscription);
		Assertions.assertEquals(448.736d, newQuote.getCost().getMin(), DELTA);

		// gp2 storage price is updated
		final var storage = newQuote.getStorages().stream().sorted().collect(Collectors.toList()).get(2);
		Assertions.assertEquals("gp3", storage.getPrice().getType().getCode());
		Assertions.assertEquals(0.44d, storage.getCost(), DELTA);
		Assertions.assertEquals(5, storage.getSize(), DELTA);

		// Compute price is updated
		final var instance2 = newQuote.getInstances().get(0);
		Assertions.assertEquals(46.611d, instance2.getCost(), DELTA);
		final var price = instance2.getPrice();
		Assertions.assertEquals(1678d, price.getInitialCost(), DELTA);
		Assertions.assertEquals(VmOs.LINUX, price.getOs());
		Assertions.assertEquals(ProvTenancy.SHARED, price.getTenancy());
		Assertions.assertEquals(46.611d, price.getCost(), DELTA);
		final var priceType = price.getTerm();
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
		checkImportStatus(310 + 2 /* saving plans */ + 1 - 1 /* purged RDS price */ /* new RDS price */
				- 1 /* purged EC2 price */ + 1 /* new EC2 price */, 77 + 50 /* Fargate */ + 1);
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
		final var status = this.resource.getImportCatalogResource().getTask("service:prov:aws");
		Assertions.assertEquals(37, status.getDone());
		Assertions.assertEquals(51, status.getWorkload()); // 6 (regions) * 7 + 9
		Assertions.assertEquals("support", status.getPhase());
		Assertions.assertEquals(DEFAULT_USER, status.getAuthor());
		Assertions.assertEquals(nbTypes, status.getNbInstanceTypes().intValue());
		Assertions.assertEquals(count, status.getNbInstancePrices().intValue());
		Assertions.assertEquals(6, status.getNbLocations().intValue());
		Assertions.assertEquals(21, status.getNbStorageTypes().intValue());
	}

	private ProvQuoteInstance check(final QuoteVo quote, final double cost, final double computeCost) {
		Assertions.assertEquals(cost, quote.getCost().getMin(), DELTA);
		checkStorage(quote.getStorages().get(0));
		return checkInstance(quote.getInstances().get(0), computeCost);
	}

	private ProvQuoteInstance checkInstance(final ProvQuoteInstance instance, final double cost) {
		Assertions.assertEquals(cost, instance.getCost(), DELTA);
		final var price = instance.getPrice();
		Assertions.assertEquals(1680d, price.getInitialCost(), DELTA);
		Assertions.assertEquals(VmOs.LINUX, price.getOs());
		Assertions.assertEquals(ProvTenancy.SHARED, price.getTenancy());
		Assertions.assertEquals(46.667, price.getCost(), DELTA);
		Assertions.assertEquals(1680d, price.getCostPeriod(), DELTA);
		final var priceType = price.getTerm();
		Assertions.assertEquals("Reserved, 3yr, All Upfront", priceType.getName());
		Assertions.assertFalse(priceType.isEphemeral());
		Assertions.assertEquals(36, priceType.getPeriod());
		Assertions.assertEquals("c1.medium", price.getType().getName());
		return instance;
	}

	private ProvQuoteStorage checkStorage(final ProvQuoteStorage storage) {
		Assertions.assertEquals(0.44d, storage.getCost(), DELTA);
		Assertions.assertEquals(5, storage.getSize(), DELTA);
		Assertions.assertNotNull(storage.getQuoteInstance());
		Assertions.assertEquals("gp3", storage.getPrice().getType().getName());
		Assertions.assertEquals(Rate.BEST, storage.getPrice().getType().getLatency());
		return storage;
	}

	@Test
	void installOnLine() throws Exception {
		configuration.delete(CONF_URL_AWS_PRICES);
		configuration.delete(AwsPriceImportEc2.CONF_URL_EC2_PRICES_SPOT);
		configuration.put(AwsPriceImportBase.CONF_REGIONS, "eu-west-1"); // Only one region for UTs
		configuration.put(AwsPriceImportEc2.CONF_OS, "LINUX"); // Only one OS for UTs

		// Only "r4.large" and "t2.*","i.*,c1" for UTs
		configuration.put(AwsPriceImportEc2.CONF_ITYPE, "(r4|t2|i1|c1)\\..*");
		configuration.put(AwsPriceImportRds.CONF_DTYPE, "db\\.(r5|t2).*");

		// Aligned to :
		// https://aws.amazon.com/ec2/pricing/reserved-instances/pricing/
		// Check the reserved
		final var quote = installAndConfigure();
		final var instance = quote.getInstances().get(0);

		// Check the spot
		final var price = qiResource.lookup(instance.getConfiguration().getSubscription().getId(),
				builder().cpu(2).ram(1741).type("r4.large").ephemeral(true).build());
		Assertions.assertTrue(price.getCost() > 5d);
		Assertions.assertTrue(price.getCost() < 100d);
		final var instance2 = price.getPrice();
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
		mockEmpty();
		mock("/offers/v1.0/aws/index.json", "mock-server/aws/offers/v1.0/aws/index-only-savings-plan.json");
		startMockServer();

		Assertions.assertThrows(IOException.class, ()-> resource.install(false));
	}

	/**
	 * No prices found
	 */
	@Test
	void installIndexNotFound() throws Exception {
		// Install a new configuration
		applicationContext.getBean(SystemConfigurationRepository.class).findAll();
		initSpringSecurityContext(DEFAULT_USER);
		mock404("/offers/v1.0/aws/index.json");
		startMockServer();

		Assertions.assertThrows(IOException.class, () -> resource.install(false));
	}

	/**
	 * No savings plan
	 */
	@Test
	void installIndexNoSavingsPlan() throws Exception {
		// Install a new configuration
		applicationContext.getBean(SystemConfigurationRepository.class).findAll();
		initSpringSecurityContext(DEFAULT_USER);
		mockAll();
		mock("/offers/v1.0/aws/index.json", "mock-server/aws/offers/v1.0/aws/index-empty-savings-plan.json");
		startMockServer();

		resource.install(false);
		em.flush();
		em.clear();
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

		mockAll();
		mock404("/savingsPlan/v1.0/aws/AWSComputeSavingsPlan/current/eu-west-1/index.json");

		configuration.put(AwsPriceImportBase.CONF_REGIONS, "eu-west-1"); // Only one region for UTs
		startMockServer();
		checkNoSavingsPlan();
	}

	private void startMockServer() {
		clearAllCache();
		httpServer.start();
	}

	private void checkNoSavingsPlan() throws IOException, URISyntaxException {
		resource.install(false);
		em.flush();
		em.clear();
		Assertions.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), DELTA);

		// Only OD+RI prices have been imported
		Assertions.assertEquals(74, itRepository.findAll().size());
		Assertions.assertEquals(3, iptRepository.findAll().size()); // RI3y, OD, Spot
	}

	/**
	 * Unable to retrieve the EC2 CSV file
	 */
	@Test
	void installEc2CsvNotFound() throws Exception {
		mockAll();
		mock404("/offers/v1.0/aws/AmazonEC2/current/eu-west-1/index.csv");
		startMockServer();

		resource.install(false);
		em.flush();
		em.clear();
		Assertions.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), DELTA);

		// No instance imported
		Assertions.assertEquals(0, itRepository.findAll().size());
	}

	/**
	 * Unable to retrieve the ECS CSV file
	 */
	@Test
	void installFargateCsvNotFound() throws Exception {
		mockAll();
		mock404("/offers/v1.0/aws/AmazonECS/current/eu-west-1/index.csv");
		startMockServer();

		resource.install(false);
		em.flush();
		em.clear();
		Assertions.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), DELTA);

		// No instance imported
		Assertions.assertEquals(50, ctRepository.findAll().size());
		Assertions.assertEquals(100, cpRepository.findAll().size()); // 2 spot regions
	}

	/**
	 * Invalid EC2 CSV file
	 */
	@Test
	void installEc2CsvInvalidHeader() throws Exception {
		mockAll();
		mock("/offers/v1.0/aws/AmazonEC2/current/eu-west-1/index.csv", "mock-server/aws/index-header-not-found.csv");
		startMockServer();

		Assertions.assertEquals("Premature end of CSV file, headers were not found",
				Assertions.assertThrows(TechnicalException.class, () -> {
					resource.install(false);
				}).getMessage());
	}

	/**
	 * Invalid EC2 CSV file
	 */
	@Test
	void installFargateCsvInvalidHeader() throws Exception {
		mockAll();
		mock("/offers/v1.0/aws/AmazonECS/current/eu-west-1/index.csv", "mock-server/aws/index-header-not-found.csv");
		startMockServer();

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
		mockAll();
		mock("/offers/v1.0/aws/AmazonEFS/current/index.csv", "mock-server/aws/index-header-not-found.csv");
		startMockServer();

		Assertions.assertEquals("Premature end of CSV file, headers were not found",
				Assertions.assertThrows(TechnicalException.class, () -> {
					resource.install(false);
				}).getMessage());
	}

	/**
	 * Reserved prices are available but not the spot instances.
	 */
	@Test
	void installSpotEmpty() throws Exception {
		mockAll();
		configure(AwsPriceImportEc2.CONF_URL_EC2_PRICES_SPOT, "/any.js");
		configure(AwsPriceImportFargate.CONF_URL_FARGATE_PRICES_SPOT, "/spot-fargate.json");
		mock("/spot-fargate.json", "mock-server/aws/spot-fargate-empty.json");
		mock("/spot.json", "mock-server/aws/spot-empty.js");
		startMockServer();

		// Check the reserved
		resource.install(false);
		em.flush();
		em.clear();
		Assertions.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), DELTA);

		// The spot term is not installed
		Assertions.assertEquals(0, iptRepository.findAllBy("name", "Spot").size());
	}

	/**
	 * Reserved prices are valid, but not the spot instances.
	 */
	@Test
	void installSpotError() throws Exception {
		mockAll();
		mock("/spot.js", "mock-server/aws/spot-error.js");
		startMockServer();

		// Parse error expected
		Assertions.assertEquals("For input string: \"AAAAAA\"",
				Assertions.assertThrows(NumberFormatException.class, () -> {
					resource.install(false);
				}).getMessage());
	}

	/**
	 * Reserved prices are valid, but not the spot instances.
	 */
	@Test
	void installFargateSpotError() throws Exception {
		mockAll();
		mock("/spot.js", "mock-server/aws/spot-error.js");
		startMockServer();

		// Parse error expected
		Assertions.assertEquals("For input string: \"AAAAAA\"",
				Assertions.assertThrows(NumberFormatException.class, () -> {
					resource.install(false);
				}).getMessage());
	}

	/**
	 * Spot refers to a non existing/not available instance
	 */
	@Test
	void installSpotInstanceBrokenReference() throws Exception {
		mockAll();
		mock("/spot.js", "mock-server/aws/spot-unavailable-instance.js");
		startMockServer();

		// Parse error expected
		resource.install(false);

		// The unique spot could not be installed
		Assertions.assertEquals(0, ipRepository.findAllBy("term.name", "Spot").size());
	}

	/**
	 * No data available from the AWS end-points
	 */
	@Test
	void installSpotRegionNotFound() throws Exception {
		mockAll();
		mock("/spot.js", "mock-server/aws/spot-empty.js");
		startMockServer();

		resource.install(false);
		em.flush();
		em.clear();
		Assertions.assertEquals(0, provResource.getConfiguration(subscription).getCost().getMin(), DELTA);
		Assertions.assertEquals(0, ipRepository.findAllBy("term.name", "Spot").size());
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
		final var quote = repository.findBy("subscription.id", subscription);
		final var budget = new ProvBudget();
		budget.setName("Dept1");
		budget.setInitialCost(100000d);
		budget.setConfiguration(quote);
		em.persist(budget);
		quote.setBudget(budget);
		em.merge(quote);
		em.flush();
		em.clear();

		final var usage = new ProvUsage();
		usage.setName("36month");
		usage.setRate(100);
		usage.setDuration(36);
		usage.setConfiguration(repository.findBy("subscription.id", subscription));
		usage.setReservation(true);
		em.persist(usage);

		final var usageCSP = new ProvUsage();
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

		final var usageEC2SP = new ProvUsage();
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
		final var lookup = qiResource.lookup(subscription,
				builder().cpu(2).ram(1741).constant(true).usage("36month").type("c1.medium").build());
		Assertions.assertEquals("HB5V2X8TXQUTDZBV.NQ3QZPMQV9.6YS6EN2CT7", lookup.getPrice().getCode());

		final var ivo = new QuoteInstanceEditionVo();
		ivo.setCpu(1d);
		ivo.setRam(1);
		ivo.setPrice(lookup.getPrice().getId());
		ivo.setName("server1");
		ivo.setSubscription(subscription);
		final var createInstance = qiResource.create(ivo);
		Assertions.assertTrue(createInstance.getTotal().getMin() > 1);
		Assertions.assertTrue(createInstance.getId() > 0);
		em.flush();
		em.clear();

		// Add storage to this instance
		var sLookup = qsResource.lookup(subscription,
				QuoteStorageQuery.builder().size(5).latency(Rate.GOOD).instance(server1()).build()).get(0);
		Assertions.assertEquals("eu-west-1-gp3", sLookup.getPrice().getCode());
		final var svo = new QuoteStorageEditionVo();
		svo.setInstance(server1());
		svo.setSize(5);
		svo.setType(sLookup.getPrice().getType().getName());
		svo.setName("sda1");
		svo.setSubscription(subscription);
		final var createStorage = qsResource.create(svo);
		Assertions.assertTrue(createStorage.getCost().getMin() >= 0.44,
				String.valueOf(createStorage.getCost().getMin()));
		Assertions.assertTrue(createStorage.getTotal().getMin() > 40,
				String.valueOf(createStorage.getTotal().getMin()));
		Assertions.assertEquals("gp3", qsRepository.findOne(createStorage.getId()).getPrice().getType().getName());

		// Add storage (EFS) to this quote
		sLookup = qsResource.lookup(subscription,
				QuoteStorageQuery.builder().latency(Rate.GOOD).optimized(ProvStorageOptimized.THROUGHPUT).build())
				.get(0);
		Assertions.assertEquals("UZ53M743SDA37R4M", sLookup.getPrice().getCode());
		final var svo2 = new QuoteStorageEditionVo();
		svo2.setSize(1);
		svo2.setOptimized(ProvStorageOptimized.THROUGHPUT);
		svo2.setType(sLookup.getPrice().getType().getName());
		svo2.setName("nfs1");
		svo2.setSubscription(subscription);
		final var createEfs = qsResource.create(svo2);
		Assertions.assertEquals(0.176, createEfs.getCost().getMin(), DELTA);
		Assertions.assertEquals("efs-z", qsRepository.findOne(createEfs.getId()).getPrice().getType().getName());

		// Add storage (S3) to this quote
		sLookup = qsResource.lookup(subscription,
				QuoteStorageQuery.builder().latency(Rate.MEDIUM).optimized(ProvStorageOptimized.DURABILITY).build())
				.get(0);
		Assertions.assertEquals("QESS8VZ4CR8YK5WX", sLookup.getPrice().getCode());
		final var type = sLookup.getPrice().getType();
		Assertions.assertEquals(99.5d, type.getAvailability(), 0.000000001d);
		Assertions.assertEquals(11, type.getDurability9().intValue());

		final var svo3 = new QuoteStorageEditionVo();
		svo3.setSize(1);
		svo3.setOptimized(ProvStorageOptimized.DURABILITY);
		svo3.setType(sLookup.getPrice().getType().getName());
		svo3.setName("my-bucket");
		svo3.setSubscription(subscription);
		final var createS3 = qsResource.create(svo3);
		Assertions.assertEquals(0.01, createS3.getCost().getMin(), DELTA);
		Assertions.assertEquals("s3-z-ia", qsRepository.findOne(createS3.getId()).getPrice().getType().getName());

		// Request a database
		var bLookup = qbResource.lookup(subscription,
				QuoteDatabaseQuery.builder().cpu(4).ram(1741).constant(false).engine("MYSQL").build());
		Assertions.assertEquals("FQ2P47XZ3KZ97A3P.JRTCKXETXF.6YS6EN2CT7", bLookup.getPrice().getCode());
		Assertions.assertFalse(bLookup.getPrice().getType().getConstant().booleanValue());
		Assertions.assertNull(bLookup.getPrice().getLicense());
		Assertions.assertEquals("MYSQL", bLookup.getPrice().getEngine());
		Assertions.assertNull(bLookup.getPrice().getEdition());
		Assertions.assertNull(bLookup.getPrice().getStorageEngine());
		Assertions.assertEquals(0, bLookup.getPrice().getInitialCost());
		Assertions.assertEquals("OnDemand", bLookup.getPrice().getTerm().getName());

		final var qb1 = new QuoteDatabaseEditionVo();
		qb1.setCpu(4);
		qb1.setRam(1741);
		qb1.setConstant(false);
		qb1.setPhysical(false);
		qb1.setEngine("MYSQL");
		qb1.setSubscription(subscription);
		qb1.setPrice(bLookup.getPrice().getId());
		qb1.setName("database1");
		final var createDb = qbResource.create(qb1);
		Assertions.assertEquals("db.t2.xlarge", qbRepository.findOne(createDb.getId()).getPrice().getType().getName());

		bLookup = qbResource.lookup(subscription, QuoteDatabaseQuery.builder().cpu(2).ram(1741).type("db.r5.large")
				.license("BYOL").engine("ORACLE").edition("ENTERPRISE").build());
		Assertions.assertEquals("68NGR9GHC49W62UR.JRTCKXETXF.6YS6EN2CT7", bLookup.getPrice().getCode());
		Assertions.assertTrue(bLookup.getPrice().getType().getConstant().booleanValue());
		Assertions.assertEquals("BYOL", bLookup.getPrice().getLicense());
		Assertions.assertEquals("ORACLE", bLookup.getPrice().getEngine());
		Assertions.assertEquals("ENTERPRISE", bLookup.getPrice().getEdition());
		Assertions.assertNull(bLookup.getPrice().getStorageEngine());
		Assertions.assertEquals(0, bLookup.getPrice().getInitialCost());

		final var qb2 = new QuoteDatabaseEditionVo();
		qb2.setCpu(2);
		qb2.setRam(1741);
		qb2.setEngine("ORACLE");
		qb2.setEdition("ENTERPRISE");
		qb2.setLicense("BYOL");
		qb2.setType("db.r5.large");
		qb2.setSubscription(subscription);
		qb2.setPrice(bLookup.getPrice().getId());
		qb2.setName("database2");
		final var createDb2 = qbResource.create(qb2);
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
