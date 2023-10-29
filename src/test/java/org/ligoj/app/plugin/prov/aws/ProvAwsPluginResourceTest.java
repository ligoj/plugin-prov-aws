/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws;

import jakarta.transaction.Transactional;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.iam.model.CacheCompany;
import org.ligoj.app.iam.model.CacheUser;
import org.ligoj.app.model.*;
import org.ligoj.app.plugin.prov.aws.auth.AWS4SignatureQuery;
import org.ligoj.app.plugin.prov.aws.auth.AWS4SignatureQuery.AWS4SignatureQueryBuilder;
import org.ligoj.app.plugin.prov.aws.catalog.AwsPriceImport;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvQuote;
import org.ligoj.app.plugin.prov.terraform.TerraformContext;
import org.ligoj.bootstrap.core.curl.CurlRequest;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * Test class of {@link ProvAwsPluginResource}
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
class ProvAwsPluginResourceTest extends AbstractServerTest {

	private static final String MOCK_URL = "http://localhost:" + MOCK_PORT + "/mock";

	@Autowired
	private ProvAwsPluginResource resource;

	protected int subscription;

	@BeforeEach
	void prepareData() throws IOException {
		persistSystemEntities();
		persistEntities("csv",
				new Class<?>[]{Node.class, Project.class, CacheCompany.class, CacheUser.class, DelegateNode.class,
						Parameter.class, ProvLocation.class, Subscription.class, ParameterValue.class,
						ProvQuote.class},
				StandardCharsets.UTF_8);
		this.subscription = getSubscription("Jupiter");
	}

	@Test
	void getKey() {
		Assertions.assertEquals("service:prov:aws", resource.getKey());
	}

	@Test
	void getName() {
		Assertions.assertEquals("AWS", resource.getName());
	}

	@Test
	void install() throws IOException {
		final var resource2 = new ProvAwsPluginResource();
		resource2.priceImport = Mockito.mock(AwsPriceImport.class);
		resource2.install();
	}

	@Test
	void updateCatalog() throws IOException {
		// Re-Install a new configuration
		final var resource2 = new ProvAwsPluginResource();
		super.applicationContext.getAutowireCapableBeanFactory().autowireBean(resource2);
		resource2.priceImport = Mockito.mock(AwsPriceImport.class);
		resource2.updateCatalog("service:prov:aws:account", false);
		resource2.updateCatalog("service:prov:aws:account", true);
	}

	@Test
	void updateCatalogNoRight() {
		initSpringSecurityContext("any");

		// Re-Install a new configuration
		Assertions.assertEquals("read-only-node", Assertions.assertThrows(BusinessException.class,
				() -> resource.updateCatalog("service:prov:aws:account", false)).getMessage());
	}

	@Test
	void generate() throws IOException {
		final var resource2 = new ProvAwsPluginResource();
		super.applicationContext.getAutowireCapableBeanFactory().autowireBean(resource2);
		resource2.terraformService = Mockito.mock(ProvAwsTerraformService.class);
		final var context = new TerraformContext();
		context.setSubscription(em.find(Subscription.class, subscription));
		resource2.generate(context);
	}

	@Test
	void generateSecrets() throws IOException {
		final var resource2 = new ProvAwsPluginResource();
		resource2.terraformService = Mockito.mock(ProvAwsTerraformService.class);
		resource2.generateSecrets(new TerraformContext());
	}

	/**
	 * retrieve keys from AWS
	 */
	@SuppressWarnings("unchecked")
	@Test
	void getEC2Keys() {
		final var resource = newSpyResource();
		final var mockRequest = new CurlRequest("GET", MOCK_URL, null);
		mockRequest.setSaveResponse(true);
		Mockito.doReturn(mockRequest).when(resource).newRequest(ArgumentMatchers.any(AWS4SignatureQueryBuilder.class),
				ArgumentMatchers.any(Map.class));
		httpServer.stubFor(get(urlEqualTo("/mock"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("<keyName>my-key</keyName>")));
		httpServer.start();

		final var keys = resource.getEC2Keys(subscription);
		Assertions.assertFalse(keys.isEmpty());
		Assertions.assertEquals(1, keys.size());
		Assertions.assertEquals("my-key", keys.get(0).getId());
	}

	private ProvAwsPluginResource newSpyResource() {
		final var resource0 = new ProvAwsPluginResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource0);
		return Mockito.spy(resource0);
	}

	/**
	 * error when we retrieve keys from AWS
	 */
	@Test
	void getEC2KeysError() {
		Assertions.assertTrue(resource.getEC2Keys(subscription).isEmpty());
	}

	/**
	 * prepare call to AWS
	 */
	@Test
	void newRequest() {
		final var request = resource.newRequest(AWS4SignatureQuery.builder().path("/").body("body").service("s3"),
				subscription);
		Assertions.assertTrue(request.getHeaders().containsKey("Authorization"));
		Assertions.assertEquals("https://s3-eu-west-1.amazonaws.com/", request.getUrl());
		Assertions.assertEquals("POST", request.getMethod());
		Assertions.assertEquals("body", request.getContent());
	}

	@Test
	void create() {
		final var resource = newSpyResource();
		Mockito.doReturn(true).when(resource).validateAccess(ArgumentMatchers.anyInt());
		resource.create(subscription);
	}

	@Test
	void createFailed() {
		final var resource = newSpyResource();
		Mockito.doReturn(false).when(resource).validateAccess(ArgumentMatchers.anyInt());
		Assertions.assertEquals("Cannot access to AWS services with these parameters",
				Assertions.assertThrows(BusinessException.class, () -> resource.create(-1)).getMessage());
	}

	@Test
	void checkSubscriptionStatusUp() {
		final var status = resource.checkSubscriptionStatus(subscription, null, new HashMap<>());
		Assertions.assertTrue(status.getStatus().isUp());
	}

	@Test
	void checkSubscriptionStatusDown() {
		final var resource = newSpyResource();
		Mockito.doReturn(false).when(resource).validateAccess(ArgumentMatchers.anyInt());
		final var status = resource.checkSubscriptionStatus(subscription, null, new HashMap<>());
		Assertions.assertFalse(status.getStatus().isUp());
	}

	@Test
	void validateAccessUp() {
		Assertions.assertTrue(validateAccess(HttpStatus.SC_OK));
	}

	@Test
	void validateAccessDown() {
		Assertions.assertFalse(validateAccess(HttpStatus.SC_FORBIDDEN));
	}

	@Test
	void checkStatus() {
		Assertions.assertTrue(validateAccess(HttpStatus.SC_OK));
		final var resource = newSpyResource();
		Mockito.doReturn(MOCK_URL).when(resource).toUrl(ArgumentMatchers.any());
		final var parameters = new HashMap<String, String>();
		parameters.put("service:prov:aws:access-key-id", "12345678901234567890");
		parameters.put("service:prov:aws:secret-access-key", "secret_secret_secret");
		parameters.put("service:prov:aws:account", "123456789");
		Assertions.assertTrue(resource.checkStatus(null, parameters));
	}

	@SuppressWarnings("unchecked")
	private boolean validateAccess(int status) {
		final var resource = newSpyResource();
		final var mockRequest = new CurlRequest("POST", MOCK_URL, null);
		mockRequest.setSaveResponse(true);
		Mockito.doReturn("any").when(resource).getRegion();
		Mockito.doReturn(mockRequest).when(resource).newRequest(ArgumentMatchers.any(AWS4SignatureQueryBuilder.class),
				ArgumentMatchers.any(Map.class));

		httpServer.stubFor(post(urlEqualTo("/mock")).willReturn(aResponse().withStatus(status)));
		httpServer.start();
		return resource.validateAccess(subscription);
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is only one subscription for a service.
	 */
	private int getSubscription(final String project) {
		return getSubscription(project, ProvAwsPluginResource.KEY);
	}
}
