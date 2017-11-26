package org.ligoj.app.plugin.prov.aws;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.transaction.Transactional;

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
import org.ligoj.app.plugin.prov.aws.auth.AWS4SignatureQuery;
import org.ligoj.app.plugin.prov.aws.auth.AWS4SignatureQuery.AWS4SignatureQueryBuilder;
import org.ligoj.app.plugin.prov.aws.in.ProvAwsPriceImportResource;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvQuote;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.app.resource.plugin.CurlRequest;
import org.ligoj.bootstrap.core.NamedBean;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
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

	protected int subscription;

	@Before
	public void prepareData() throws IOException {
		persistSystemEntities();
		persistEntities("csv", new Class[] { Node.class, Project.class, CacheCompany.class, CacheUser.class, DelegateNode.class,
				Parameter.class, ProvLocation.class, Subscription.class, ParameterValue.class, ProvQuote.class },
				StandardCharsets.UTF_8.name());
		this.subscription = getSubscription("gStack");
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
	public void install() throws Exception {
		final ProvAwsPluginResource resource2 = new ProvAwsPluginResource();
		resource2.priceImport = Mockito.mock(ProvAwsPriceImportResource.class);
		resource2.install();
	}

	@Test
	public void updateCatalog() throws Exception {
		// Re-Install a new configuration
		final ProvAwsPluginResource resource2 = new ProvAwsPluginResource();
		super.applicationContext.getAutowireCapableBeanFactory().autowireBean(resource2);
		resource2.priceImport = Mockito.mock(ProvAwsPriceImportResource.class);
		resource2.updateCatalog("service:prov:aws:account");
	}

	@Test(expected = BusinessException.class)
	public void updateCatalogNoRight() throws Exception {
		initSpringSecurityContext("any");

		// Re-Install a new configuration
		resource.updateCatalog("service:prov:aws:account");
	}
	
	@Test
	public void terraform() throws Exception {
		final ProvAwsPluginResource resource2 = new ProvAwsPluginResource();
		super.applicationContext.getAutowireCapableBeanFactory().autowireBean(resource2);
		resource2.terraformService = Mockito.mock(ProvAwsTerraformService.class);
		final ByteArrayOutputStream bos = new ByteArrayOutputStream();
		resource2.terraform(bos, subscription, null);
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

	@Test
	public void create() throws Exception {
		final ProvAwsPluginResource resource = Mockito.spy(ProvAwsPluginResource.class);
		Mockito.doReturn(true).when(resource).validateAccess(ArgumentMatchers.anyInt());
		resource.create(subscription);
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
	public void validateAccessUp() {
		Assert.assertTrue(validateAccess(HttpStatus.SC_OK));
	}

	@Test
	public void validateAccessDown() {
		Assert.assertFalse(validateAccess(HttpStatus.SC_FORBIDDEN));
	}

	@Test
	public void checkStatus() throws Exception {
		Assert.assertTrue(validateAccess(HttpStatus.SC_OK));
		final ProvAwsPluginResource resource = Mockito.spy(this.resource);
		Mockito.doReturn(MOCK_URL).when(resource).toUrl(ArgumentMatchers.any());
		final Map<String, String> parameters = new HashMap<>();
		parameters.put("service:prov:aws:access-key-id", "12345678901234567890");
		parameters.put("service:prov:aws:secret-access-key", "abcdefghtiklmnopqrstuvwxyz");
		parameters.put("service:prov:aws:account", "123456789");
		Assert.assertTrue(resource.checkStatus(null, parameters));
	}

	private boolean validateAccess(int status) {
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
	 * Return the subscription identifier of the given project. Assumes there is
	 * only one subscription for a service.
	 */
	protected int getSubscription(final String project) {
		return getSubscription(project, ProvAwsPluginResource.KEY);
	}
}
