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
import org.ligoj.app.plugin.prov.LowestInstancePrice;
import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.QuoteInstanceEditionVo;
import org.ligoj.app.plugin.prov.QuoteVo;
import org.ligoj.app.plugin.prov.model.ProvInstancePrice;
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

	private int newSubscription() throws Exception {
		final SubscriptionEditionVo vo = new SubscriptionEditionVo();
		vo.setMode(SubscriptionMode.CREATE);
		vo.setNode("service:prov:aws:account");
		vo.setProject(projectRepository.findByNameExpected("gStack").getId());
		return subscriptionResource.create(vo);
	}

	@Test
	public void installOffLine() throws Exception {
		initSpringSecurityContext(DEFAULT_USER);
		configuration.saveOrUpdate(ProvAwsResource.CONF_URL_PRICES, "http://localhost:" + MOCK_PORT + "/index.csv");
		httpServer.stubFor(get(urlEqualTo("/index.csv")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/aws/index.csv").getInputStream(), "UTF-8"))));
		httpServer.start();
		final QuoteVo quote = check();
		Assert.assertEquals(2.928, quote.getCost(), 0.001);
		Assert.assertEquals(2.928, quote.getInstances().get(0).getCost(), 0.001);
		Assert.assertNull(quote.getInstances().get(0).getInstancePrice().getInitialCost());
		Assert.assertEquals(ProvTenancy.SHARED, quote.getInstances().get(0).getInstancePrice().getTenancy());
		Assert.assertEquals(0.0035, quote.getInstances().get(0).getInstancePrice().getCost(), 0.001);
		Assert.assertEquals("t1.micro", quote.getInstances().get(0).getInstancePrice().getInstance().getName());
	}

	@Test(expected = ConnectException.class)
	public void installFailed() throws Exception {
		initSpringSecurityContext(DEFAULT_USER);
		configuration.saveOrUpdate(ProvAwsResource.CONF_URL_PRICES, "http://localhost:" + MOCK_PORT + "/index.csv");
		check();
	}

	@Test
	public void installOnLine() throws Exception {
		configuration.delete(ProvAwsResource.CONF_URL_PRICES);
		final QuoteVo quote = check();

		// Aligned to :
		// https://aws.amazon.com/fr/ec2/pricing/reserved-instances/pricing/
		final ProvQuoteInstance instance = quote.getInstances().get(0);
		Assert.assertEquals(2.196, instance.getCost(), 0.001);
		final ProvInstancePrice price = instance.getInstancePrice();
		Assert.assertEquals(0.003, price.getCost(), 0.001);
		Assert.assertEquals(77d, price.getInitialCost(), 0.001);
		Assert.assertEquals(ProvTenancy.SHARED, price.getTenancy());
		Assert.assertEquals("Reserved, 3yr, All Upfront", price.getType().getName());
		Assert.assertEquals(1576800, price.getType().getPeriod().intValue());
		Assert.assertEquals("t2.nano", price.getInstance().getName());
	}

	private QuoteVo check() throws IOException, URISyntaxException, Exception {
		resource.install();
		em.flush();
		em.clear();
		final int subscription = newSubscription();
		em.flush();
		em.clear();
		Assert.assertEquals(0, provResource.getConfiguration(subscription).getCost(), 0.001);

		final LowestInstancePrice price = provResource.lookupInstance(subscription, 1, 1, false, VmOs.LINUX, null,
				null);
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
}
