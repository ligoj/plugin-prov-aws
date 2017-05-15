package org.ligoj.app.plugin.prov.aws;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.dao.NodeRepository;
import org.ligoj.app.model.Node;
import org.ligoj.app.plugin.prov.AbstractProvResource;
import org.ligoj.app.plugin.prov.ProvResource;
import org.ligoj.app.plugin.prov.dao.ProvInstancePriceTypeRepository;
import org.ligoj.app.plugin.prov.model.ProvInstance;
import org.ligoj.app.plugin.prov.model.ProvInstancePriceType;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The provisioning service for AWS. There is complete quote configuration along
 * the subscription.
 */
@Service
@Path(ProvAwsResource.SERVICE_URL)
@Produces(MediaType.APPLICATION_JSON)
public class ProvAwsResource extends AbstractProvResource {

	/**
	 * Plug-in key.
	 */
	public static final String SERVICE_URL = ProvResource.SERVICE_URL + "/aws";

	/**
	 * Plug-in key.
	 */
	public static final String SERVICE_KEY = SERVICE_URL.replace('/', ':').substring(1);

	private static final String AWS_EC2_PRICES = "https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/eu-west-1/index.csv";
	private static final Pattern AWS_EC2_LEASING_LENGTH = Pattern.compile("(\\d)yr");

	@Autowired
	private ConfigurationResource configuration;

	@Autowired
	private NodeRepository nodeRepository;

	@Autowired
	private ProvInstancePriceTypeRepository ipt;

	@Override
	public String getKey() {
		return SERVICE_KEY;
	}

	/**
	 * Fetch the prices from the AWS server
	 */
	@Override
	public void install() throws IOException, URISyntaxException {
		installReservedPrices();
	}

	protected void installReservedPrices() throws IOException, URISyntaxException {
		final BufferedReader reader = new BufferedReader(new InputStreamReader(
				new URI(configuration.get(SERVICE_KEY + ":reserved-ec2-prices-url", AWS_EC2_PRICES)).toURL()
						.openStream()));
		final AwsCsvForBean csvReader = new AwsCsvForBean(reader);

		final Map<String, ProvInstancePriceType> priceTypes = new HashMap<>();
		final Map<String, ProvInstance> instances = new HashMap<>();

		// Node is already persisted
		final Node awsNode = nodeRepository.findByName(SERVICE_KEY);

		// Build the first instance
		AwsInstancePrice instancePrice = null;
		do {
			// Read the next one
			instancePrice = csvReader.read();
			if (instancePrice == null) {
				break;
			}

			// Instance this price
			install(instancePrice, instances, priceTypes, awsNode);
		} while (instancePrice != null);
	}

	private void install(final AwsInstancePrice csvPrice, final Map<String, ProvInstance> instances,
			final Map<String, ProvInstancePriceType> priceTypes, final Node awsNode) {
		// Build the instance
		final ProvInstance instance = instances.computeIfAbsent(csvPrice.getInstanceType(),
				k -> newProvInstance(csvPrice, awsNode));

		// Build the instance price
		final ProvInstancePriceType priceType = priceTypes.computeIfAbsent(csvPrice.getOfferTermCode(),
				k -> newProvInstancePriceType(csvPrice, awsNode));

	}

	private ProvInstance newProvInstance(final AwsInstancePrice csvPrice, final Node awsNode) {
		final ProvInstance instance = new ProvInstance();
		instance.setCpu(csvPrice.getCpu());
		instance.setName(csvPrice.getInstanceType());

		// Convert GiB to MiB, and rounded
		instance.setRam((int) Math.round(
				Double.parseDouble(StringUtils.removeEndIgnoreCase(csvPrice.getMemory(), " GiB").replace(",", ""))
						* 1024d));
		instance.setConstant(!"Variable".equals(csvPrice.getEcu()));
		instance.setDescription(
				ArrayUtils.toString(new String[] { csvPrice.getPhysicalProcessor(), csvPrice.getClockSpeed() }));
		instance.setCpu(csvPrice.getCpu());
		instance.setNode(awsNode);
		return instance;
	}

	private ProvInstancePriceType newProvInstancePriceType(final AwsInstancePrice csvPrice, final Node awsNode) {
		final ProvInstancePriceType result = new ProvInstancePriceType();
		result.setNode(awsNode);

		// Build the name from the leasing, purchase option and offering class
		result.setName(Arrays
				.stream(new String[] { csvPrice.getTermType(), csvPrice.getLeaseContractLength(),
						StringUtils.trimToNull(StringUtils.remove(csvPrice.getPurchaseOption(), "No Upfront")),
						StringUtils.trimToNull(StringUtils.remove(csvPrice.getOfferingClass(), "standard")) })
				.filter(Objects::nonNull).collect(Collectors.joining(", ")));
		if (csvPrice.getLeaseContractLength() != null) {
			// Check if there is a leasing option
			Matcher matcher = AWS_EC2_LEASING_LENGTH.matcher(csvPrice.getLeaseContractLength());
			if (matcher.find()) {
				// Convert year to minutes
				result.setPeriod(Integer.parseInt(matcher.group(1)) * 60 * 24 * 365);
			}
		}
		return result;
	}
}
