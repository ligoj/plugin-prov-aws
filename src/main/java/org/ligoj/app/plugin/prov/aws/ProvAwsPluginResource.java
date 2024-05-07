/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.ligoj.app.api.SubscriptionStatusWithData;
import org.ligoj.app.plugin.prov.AbstractProvResource;
import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.aws.auth.AWS4SignatureQuery;
import org.ligoj.app.plugin.prov.aws.auth.AWS4SignatureQuery.AWS4SignatureQueryBuilder;
import org.ligoj.app.plugin.prov.aws.auth.AWS4SignerForAuthorizationHeader;
import org.ligoj.app.plugin.prov.aws.catalog.AwsPriceImport;
import org.ligoj.app.plugin.prov.catalog.ImportCatalogService;
import org.ligoj.app.plugin.prov.terraform.TerraformContext;
import org.ligoj.app.plugin.prov.terraform.Terraforming;
import org.ligoj.bootstrap.core.NamedBean;
import org.ligoj.bootstrap.core.curl.CurlProcessor;
import org.ligoj.bootstrap.core.curl.CurlRequest;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The provisioning service for AWS. There is complete quote configuration along the subscription.
 */
@Service
@Path(ProvAwsPluginResource.URL)
@Produces(MediaType.APPLICATION_JSON)
public class ProvAwsPluginResource extends AbstractProvResource implements Terraforming, ImportCatalogService {

	/**
	 * Plug-in key.
	 */
	public static final String URL = ProvResource.SERVICE_URL + "/aws";

	/**
	 * Plug-in key.
	 */
	public static final String KEY = URL.replace('/', ':').substring(1);

	/**
	 * The default region, fixed for now.
	 */
	private static final String DEFAULT_REGION = "eu-west-1";

	/**
	 * Configuration key used for {@link #DEFAULT_REGION}
	 */
	public static final String CONF_REGION = KEY + ":region";

	/**
	 * Parameter used for AWS authentication
	 */
	public static final String PARAMETER_ACCESS_KEY_ID = KEY + ":access-key-id";

	/**
	 * Parameter used for AWS authentication
	 */
	public static final String PARAMETER_SECRET_ACCESS_KEY = KEY + ":secret-access-key";

	/**
	 * AWS Account identifier.
	 */
	public static final String PARAMETER_ACCOUNT = KEY + ":account";

	@Autowired
	private AWS4SignerForAuthorizationHeader signer;

	@Autowired
	private ConfigurationResource configuration;

	@Autowired
	protected AwsPriceImport priceImport;

	@Autowired
	protected ProvAwsTerraformService terraformService;

	@Override
	public String getKey() {
		return KEY;
	}

	/**
	 * Check AWS connection and account.
	 *
	 * @param node       The node identifier. May be <code>null</code>.
	 * @param parameters the parameter values of the node.
	 * @return <code>true</code> if AWS connection is up
	 */
	@Override
	public boolean checkStatus(final String node, final Map<String, String> parameters) {
		return validateAccess(parameters);
	}

	@Override
	public void create(final int subscription) {
		if (!validateAccess(subscription)) {
			throw new BusinessException("Cannot access to AWS services with these parameters");
		}
	}

	@Override
	public SubscriptionStatusWithData checkSubscriptionStatus(final int subscription, final String node,
			final Map<String, String> parameters) {
		// Validate the account
		if (validateAccess(subscription)) {
			// Return the quote details
			return super.checkSubscriptionStatus(subscription, node, parameters);
		}
		return new SubscriptionStatusWithData(false);
	}

	/**
	 * Fetch the prices from the AWS server. Install or update the prices
	 */
	@Override
	public void install() throws IOException {
		priceImport.install(false);
	}

	@Override
	public void updateCatalog(final String node, final boolean force) throws IOException {
		// AWS catalog is shared with all instances, require tool level access
		nodeResource.checkWritableNode(KEY);
		priceImport.install(force);
	}

	@Override
	public void generate(final TerraformContext context) throws IOException {
		terraformService.write(context);
	}

	/**
	 * Return EC2 key names.
	 *
	 * @param subscription The related subscription.
	 * @return EC2 keys related to given subscription.
	 */
	@Path("ec2/keys/{subscription:\\d+}")
	@GET
	public List<NamedBean<String>> getEC2Keys(@PathParam("subscription") final int subscription) {
		// Call "DescribeKeyPairs" service
		final var query = "Action=DescribeKeyPairs&Version=2016-11-15";
		final var builder = AWS4SignatureQuery.builder().service("ec2").region(getRegion()).path("/").body(query);
		final var request = newRequest(builder, subscription);
		// extract key pairs from response
		final var keys = new ArrayList<NamedBean<String>>();
		try (var curlProcessor = new CurlProcessor()) {
			if (curlProcessor.process(request)) {
				final var keyNames = Pattern.compile("<keyName>(.*)</keyName>").matcher(request.getResponse());
				while (keyNames.find()) {
					keys.add(new NamedBean<>(keyNames.group(1), null));
				}
			}
		}
		return keys;
	}

	/**
	 * Create Curl request for AWS service. Initialize default values for awsAccessKey, awsSecretKey and regionName and
	 * compute signature.
	 *
	 * @param builder      {@link AWS4SignatureQueryBuilder} initialized with values used for this call (headers,
	 *                     parameters, host, ...)
	 * @param subscription Subscription's identifier.
	 * @return initialized request
	 */
	protected CurlRequest newRequest(final AWS4SignatureQueryBuilder builder, final int subscription) {
		return newRequest(builder, subscriptionResource.getParameters(subscription));
	}

	/**
	 * Create Curl request for AWS service. Initialize default values for awsAccessKey, awsSecretKey and regionName and
	 * compute signature.
	 *
	 * @param builder    {@link AWS4SignatureQueryBuilder} initialized with values used for this call (headers,
	 *                   parameters, host, ...)
	 * @param parameters Subscription's parameters.
	 * @return Initialized request.
	 */
	protected CurlRequest newRequest(final AWS4SignatureQueryBuilder builder, final Map<String, String> parameters) {
		final var query = builder.accessKey(parameters.get(PARAMETER_ACCESS_KEY_ID))
				.secretKey(parameters.get(PARAMETER_SECRET_ACCESS_KEY)).region(getRegion()).build();
		final var authorization = signer.computeSignature(query);
		final var request = new CurlRequest(query.getMethod(), toUrl(query), query.getBody());
		request.getHeaders().putAll(query.getHeaders());
		request.getHeaders().put("Authorization", authorization);
		request.setSaveResponse(true);
		return request;
	}

	/**
	 * Return the URL from a query.
	 *
	 * @param query Source {@link AWS4SignatureQuery}
	 * @return The base host URL from a query.
	 */
	protected String toUrl(final AWS4SignatureQuery query) {
		return "https://" + query.getHost() + query.getPath();
	}

	/**
	 * Return the default region for this plug-in.
	 *
	 * @return the default region.
	 */
	protected String getRegion() {
		return configuration.get(CONF_REGION, DEFAULT_REGION);
	}

	/**
	 * Check AWS connection and account.
	 *
	 * @param parameters Subscription parameters.
	 * @return <code>true</code> if AWS connection is up
	 */
	private boolean validateAccess(final Map<String, String> parameters) {
		// Call STS GetCallerIdentity
		final var query = "Action=GetCallerIdentity&Version=2011-06-15";
		final var builder = AWS4SignatureQuery.builder().service("sts").path("/").body(query);
		try (var curlProcessor = new CurlProcessor()) {
			return curlProcessor.process(newRequest(builder, parameters));
		}
	}

	/**
	 * Check AWS connection and account.
	 *
	 * @param subscription Subscription identifier.
	 * @return <code>true</code> if AWS connection is up
	 */
	public boolean validateAccess(final int subscription) {
		// Call STS GetCallerIdentity
		final var query = "Action=GetCallerIdentity&Version=2011-06-15";
		final var builder = AWS4SignatureQuery.builder().service("sts").path("/").body(query);
		try (var curlProcessor = new CurlProcessor()) {
			return curlProcessor.process(newRequest(builder, subscription));
		}
	}

	@Override
	public void generateSecrets(final TerraformContext context) throws IOException {
		terraformService.writeSecrets(context.getSubscription());
	}

	@Override
	public String getName() {
		return "AWS";
	}}
