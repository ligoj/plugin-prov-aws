package org.ligoj.app.plugin.prov.aws;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.ligoj.app.api.SubscriptionStatusWithData;
import org.ligoj.app.dao.SubscriptionRepository;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Parameter;
import org.ligoj.app.plugin.prov.AbstractProvResource;
import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.QuoteVo;
import org.ligoj.app.plugin.prov.aws.auth.AWS4SignatureQuery;
import org.ligoj.app.plugin.prov.aws.auth.AWS4SignatureQuery.AWS4SignatureQueryBuilder;
import org.ligoj.app.plugin.prov.aws.auth.AWS4SignerForAuthorizationHeader;
import org.ligoj.app.plugin.prov.aws.in.ProvAwsPriceImportResource;
import org.ligoj.app.plugin.prov.in.ImportCatalogService;
import org.ligoj.app.plugin.prov.model.ProvStorageType;
import org.ligoj.app.plugin.prov.terraform.Terraforming;
import org.ligoj.app.resource.node.NodeResource;
import org.ligoj.app.resource.plugin.CurlProcessor;
import org.ligoj.app.resource.plugin.CurlRequest;
import org.ligoj.bootstrap.core.NamedBean;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The provisioning service for AWS. There is complete quote configuration along
 * the subscription.
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
	 * Configuration key used for {@link #DEFAULT_ENABLED_REGION}
	 */
	public static final String CONF_ENABLED_REGION = KEY + ":enabled-region";

	/**
	 * Parameter used for AWS authentication
	 */
	public static final String PARAMETER_ACCESS_KEY_ID = KEY + ":access-key-id";

	/**
	 * Parameter used for AWS authentication
	 */
	public static final String PARAMETER_SECRET_ACCESS_KEY = KEY + ":secret-access-key";

	/**
	 * AWS Account Id.
	 */
	public static final String PARAMETER_ACCOUNT = KEY + ":account";

	@Autowired
	private AWS4SignerForAuthorizationHeader signer;

	@Autowired
	private ConfigurationResource configuration;

	@Autowired
	private NodeResource nodeResource;

	@Autowired
	private SubscriptionRepository sRepository;

	@Autowired
	protected ProvAwsPriceImportResource priceImport;

	@Autowired
	protected ProvAwsTerraformService terraformService;

	@Override
	public String getKey() {
		return KEY;
	}

	/**
	 * Check AWS connection and account.
	 * 
	 * @param subscription
	 *            subscription
	 * @return <code>true</code> if AWS connection is up
	 */
	@Override
	public boolean checkStatus(final String node, final Map<String, String> parameters) throws Exception {
		return validateAccess(parameters);
	}

	@Override
	public List<Class<?>> getInstalledEntities() {
		return Arrays.asList(Node.class, ProvStorageType.class, Parameter.class);
	}

	@Override
	public void create(final int subscription) throws Exception {
		if (!validateAccess(subscription)) {
			throw new BusinessException("Cannot access to AWS services with these parameters");
		}
	}

	@Override
	public SubscriptionStatusWithData checkSubscriptionStatus(final int subscription, final String node,
			final Map<String, String> parameters) throws Exception {
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
	public void install() throws IOException, URISyntaxException {
		priceImport.install();
	}

	@Override
	public void updateCatalog(final String node) throws IOException, URISyntaxException {
		// AWS catalog is shared with all instances, require tool level access
		nodeResource.checkWritableNode(KEY);
		priceImport.install();
	}

	@Override
	public void terraform(final OutputStream output, final int subscription, final QuoteVo quote) throws IOException {
		final Writer writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
		terraformService.writeTerraform(writer, quote, sRepository.findOne(subscription));
		writer.flush();
	}

	@Override
	public String[] commandLineParameters(final int subscription) {
		final Map<String, String> parameters = subscriptionResource.getParameters(subscription);
		return new String[] { "-var", "'AWS_ACCESS_KEY_ID=" + parameters.get(PARAMETER_ACCESS_KEY_ID) + "'", "-var",
				"'AWS_SECRET_ACCESS_KEY=" + parameters.get(PARAMETER_SECRET_ACCESS_KEY) + "'" };
	}

	/**
	 * Return EC2 keys
	 * 
	 * @param subscription
	 *            The related subscription.
	 * @return EC2 keys related to given subscription.
	 */
	@Path("ec2/keys/{subscription:\\d+}")
	@GET
	public List<NamedBean<String>> getEC2Keys(@PathParam("subscription") final int subscription) {
		// Call "DescribeKeyPairs" service
		final String query = "Action=DescribeKeyPairs&Version=2016-11-15";
		final AWS4SignatureQueryBuilder signatureQueryBuilder = AWS4SignatureQuery.builder().service("ec2")
				.host("ec2." + getRegion() + ".amazonaws.com").path("/").body(query);
		final CurlRequest request = newRequest(signatureQueryBuilder, subscription);
		// extract key pairs from response
		final List<NamedBean<String>> keys = new ArrayList<>();
		if (new CurlProcessor().process(request)) {
			final Matcher keyNames = Pattern.compile("<keyName>(.*)</keyName>").matcher(request.getResponse());
			while (keyNames.find()) {
				keys.add(new NamedBean<>(keyNames.group(1), null));
			}
		}
		return keys;
	}

	/**
	 * Create Curl request for AWS service. Initialize default values for
	 * awsAccessKey, awsSecretKey and regionName and compute signature.
	 * 
	 * @param signatureBuilder
	 *            {@link AWS4SignatureQueryBuilder} initialized with values used for
	 *            this call (headers, parameters, host, ...)
	 * @param subscription
	 *            Subscription's identifier.
	 * @return initialized request
	 */
	protected CurlRequest newRequest(final AWS4SignatureQueryBuilder signatureBuilder, final int subscription) {
		return newRequest(signatureBuilder, subscriptionResource.getParameters(subscription));
	}

	/**
	 * Create Curl request for AWS service. Initialize default values for
	 * awsAccessKey, awsSecretKey and regionName and compute signature.
	 * 
	 * @param signatureBuilder
	 *            {@link AWS4SignatureQueryBuilder} initialized with values used for
	 *            this call (headers, parameters, host, ...)
	 * @param subscription
	 *            Subscription's identifier.
	 * @return initialized request
	 */
	protected CurlRequest newRequest(final AWS4SignatureQueryBuilder signatureBuilder, final Map<String, String> parameters) {
		final AWS4SignatureQuery signatureQuery = signatureBuilder.accessKey(parameters.get(PARAMETER_ACCESS_KEY_ID))
				.secretKey(parameters.get(PARAMETER_SECRET_ACCESS_KEY)).region(getRegion()).build();
		final String authorization = signer.computeSignature(signatureQuery);
		final CurlRequest request = new CurlRequest(signatureQuery.getMethod(), toUrl(signatureQuery), signatureQuery.getBody());
		request.getHeaders().putAll(signatureQuery.getHeaders());
		request.getHeaders().put("Authorization", authorization);
		request.setSaveResponse(true);
		return request;
	}

	/**
	 * Return the URL built from the {@link AWS4SignatureQuery}
	 */
	protected String toUrl(final AWS4SignatureQuery signatureQuery) {
		return "https://" + signatureQuery.getHost() + signatureQuery.getPath();
	}

	/**
	 * Check AWS connection and account.
	 * 
	 * @param parameters
	 *            Subscription parameters.
	 * @return <code>true</code> if AWS connection is up
	 */
	private boolean validateAccess(final Map<String, String> parameters) {
		// Call S3 ls service
		// TODO Use EC2 instead of S3
		final AWS4SignatureQueryBuilder signatureQueryBuilder = AWS4SignatureQuery.builder().method("GET").service("s3").host(getS3Host())
				.path("/");
		return new CurlProcessor().process(newRequest(signatureQueryBuilder, parameters));
	}

	/**
	 * Return the default S3 hot to validate the account
	 */
	protected String getS3Host() {
		return "s3-" + getRegion() + ".amazonaws.com";
	}

	/**
	 * Return the default region for this plug-in.
	 */
	protected String getRegion() {
		return configuration.get(CONF_REGION, DEFAULT_REGION);
	}

	/**
	 * Check AWS connection and account.
	 * 
	 * @param subscription
	 *            Subscription identifier.
	 * @return <code>true</code> if AWS connection is up
	 */
	public boolean validateAccess(final int subscription) {
		// Call S3 ls service
		// TODO Use EC2 instead of S3
		final AWS4SignatureQueryBuilder signatureQueryBuilder = AWS4SignatureQuery.builder().method("GET").service("s3").host(getS3Host())
				.path("/");
		return new CurlProcessor().process(newRequest(signatureQueryBuilder, subscription));
	}

}
