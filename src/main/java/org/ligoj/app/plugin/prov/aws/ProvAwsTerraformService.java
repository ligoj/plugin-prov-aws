/*
  * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.FileWriterWithEncoding;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.prov.model.AbstractQuoteResource;
import org.ligoj.app.plugin.prov.model.ProvLocation;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;
import org.ligoj.app.plugin.prov.model.ProvQuoteStorage;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.app.plugin.prov.terraform.Context;
import org.ligoj.app.plugin.prov.terraform.InstanceMode;
import org.ligoj.app.plugin.prov.terraform.TerraformUtils;
import org.ligoj.app.resource.subscription.SubscriptionResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;

/**
 * Service in charge of Terraform generation for AWS.
 */
@Service
public class ProvAwsTerraformService {

	/**
	 * Mapping between OS name and AMI base name handled by Terraform.
	 */
	private Map<VmOs, String> mappingOsAmi = new EnumMap<>(VmOs.class);

	/**
	 * Mapping between OS name and root device name.
	 */
	private final Map<VmOs, String> mappingOsRootDevice = new EnumMap<>(VmOs.class);

	/**
	 * Mapping between OS name and EBS device name. The value is a format using the last char and increment it by the
	 * index (base 0). Sample, for index <code>4</code>:
	 * <ul>
	 * <li>Format <code>/dev/sda1</code> gives <code>/dev/sda4</code></li>
	 * <li>Format <code>/dev/xvda</code> gives <code>/dev/xvdj</code></li>
	 * </ul>
	 * Note that the root device does not use this format. The first non root EBS device has index <code>0</code>.
	 *
	 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/device_naming.html">device_naming.html</a> for
	 *      recommendations.
	 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/WindowsGuide/device_naming.html">device_naming.html</a>
	 *      for recommendations.
	 *
	 */
	private final Map<VmOs, String> mappingOsEbsDevice = new EnumMap<>(VmOs.class);

	/**
	 * Default Root device name.
	 */
	private static final String DEFAULT_ROOT_DEVICE = "/dev/sda1";

	/**
	 * Default EBS device format. See {@link #mappingOsEbsDevice} for more details.
	 */
	private static final String DEFAULT_EBS_DEVICE = "/dev/sdf";

	public ProvAwsTerraformService() {
		// AMIs
		mappingOsAmi.put(VmOs.SUSE, "suse-sles");
		mappingOsAmi.put(VmOs.LINUX, "amazon");

		// Root devices
		mappingOsRootDevice.put(VmOs.LINUX, "/dev/xvda"); // AMZ

		// EBS devices
		mappingOsEbsDevice.put(VmOs.WINDOWS, "xvdf"); // only Windows
	}

	@Autowired
	private SubscriptionResource subscriptionResource;

	@Autowired
	protected TerraformUtils utils;

	/**
	 * Generate the Terraform configuration files:
	 * <ul>
	 * <li><code>./terraform.tfvars</code> the project, public keys, customizations and subscription variables.</li>
	 * <li><code>./secrets.auto.tfvars</code> the secret variables, cannot be downloaded.</li>
	 * <li><code>./$my-region.tf</code> the region specific configuration.</li>
	 * <li><code>./$my-region/instance-$instance.tf</code> for instances of this quote in a region.</li>
	 * <li><code>./$my-region/dashboard-*</code> for dashboard in a region.</li>
	 * </ul>
	 * Static files are:
	 * <ul>
	 * <li><code>./main.tf</code> declaring global configuration.</li>
	 * <li><code>./variables.tf</code> declaring global variables.</li>
	 * <li><code>./$my-region.tf</code> the region specific configuration.</li>
	 * <li><code>./$my-region/variables.tf</code> a specific region variables.</li>
	 * <li><code>./$my-region/provider.tf</code> for provider configuration.</li>
	 * <li><code>./$my-region/vpc.tf</code> for VPC configuration.</li>
	 * <li><code>./$my-region/ami-$os.tf</code> for enabled OS in this region.</li>
	 * </ul>
	 *
	 * @param context
	 *            The Terraform context holding the subscription, the quote and the user inputs.
	 * @throws IOException
	 *             When Terraform content cannot be written.
	 */
	public void write(final Context context) throws IOException {
		writeStatics(context);
		writeContext(context);
		writeRegions(context);
		writeSecrets(context.getSubscription());
	}

	/**
	 * Write the global subscription and project context.
	 */
	private void writeContext(final Context context) throws IOException {
		final Project project = context.getSubscription().getProject();
		context.add("project.id", project.getId().toString()).add("project.pkey", project.getPkey())
				.add("project.name", project.getName())
				.add("subscription.id", context.getSubscription().getId().toString());
		template(context, s -> replace(s, context), "terraform.keep.auto.tfvars");
	}

	private void writeStatics(final Context context) throws IOException {
		copy(context, "main.tf");
		copy(context, "variables.keep.tf");
	}

	private void writeRegions(final Context context) throws IOException {
		final Set<String> locations = new HashSet<>();
		context.getQuote().getInstances().stream().map(this::getLocation).map(ProvLocation::getName)
				.forEach(locations::add);
		for (final String location : locations) {
			context.setLocation(location);
			context.add("region", location);
			writeRegion(context);
		}
	}

	private ProvLocation getLocation(final AbstractQuoteResource resource) {
		return Objects.requireNonNullElse(resource.getLocation(), resource.getConfiguration().getLocation());
	}

	private void writeRegion(final Context context) throws IOException {
		final List<ProvQuoteInstance> instances = new ArrayList<>();
		context.getQuote().getInstances().stream().filter(i -> getLocation(i).getName().equals(context.getLocation()))
				.forEach(instances::add);
		final Map<InstanceMode, List<ProvQuoteInstance>> modes = new EnumMap<>(InstanceMode.class);
		Arrays.stream(InstanceMode.values()).forEach(m -> modes.put(m, new ArrayList<>()));
		instances.stream().forEach(i -> modes.get(toMode(i)).add(i));
		context.setModes(modes);

		writeRegionStatics(context);
		writeRegionOs(context, instances);
		writeRegionDashboard(context);
		writeRegionInstances(context);
	}

	/**
	 * Write dashboard: charts and Markdown.
	 */
	private void writeRegionDashboard(final Context context) throws IOException {
		final Map<InstanceMode, List<ProvQuoteInstance>> modes = context.getModes();
		// Charts
		context.setInstances(modes.get(InstanceMode.AUTO_SCALING));
		templateFromTo(
				context.add("scaling", getDashboardScaling(context)).add("balancing", getDashboardBalancing(context))
						.add("latency", getDashboardLatency(context)).add("network", getDashboardNetwork(context)),
				"my-region/dashboard-widgets.tpl.json", context.getLocation(), "dashboard-widgets.tpl.json");

		// Markdown
		templateFromTo(context.add("alb", getMd(modes.get(InstanceMode.AUTO_SCALING),
				"ALB|[${alb{{i}}_name}](/ec2/v2/home?region=${region}#LoadBalancers:search=${alb{{i}}_dns})|[http](http://${alb{{i}}_dns})"))
				.add("ec2", getMd(modes.get(InstanceMode.VM),
						"EC2|[${ec2{{i}}_name}](/ec2/v2/home?region=${region}#Instances:search=${ec2{{i}}})|[http](http://${ec2{{i}}_ip})"))
				.add("spot", getMd(modes.get(InstanceMode.EPHEMERAL),
						"EC2|[${spot{{i}}_name}](/ec2sp/v1/spot/home?region=${region}#)|[http](http://${spot{{i}}_ip})"))
				.add("asg", getMd(modes.get(InstanceMode.AUTO_SCALING),
						"EC2/AS|[${asg{{i}}_name}](/ec2/autoscaling/home?region=${region}#AutoScalingGroups:id=${asg{{i}}};view=details)|")),
				"my-region/dashboard-widgets.tpl.md", context.getLocation(), "dashboard-widgets.tpl.md");

		// References for MD template and CloudWatch widgets
		templateFromTo(context.add("references", getDashboardReferences(context)), "my-region/dashboard.tf",
				context.getLocation(), "dashboard.tf");
	}

	private String getDashboardReferences(final Context context) {
		final StringBuilder buffer = new StringBuilder();
		appendDashboardReferences(buffer, context, context.getModes().get(InstanceMode.VM),
				"ec2{{i}} = \"${aws_instance.{{key}}.id}\"", "ec2{{i}}_name = \"{{name}}\"",
				"ec2{{i}}_ip = \"${aws_instance.{{key}}.public_ip}\"");
		appendDashboardReferences(buffer, context, context.getModes().get(InstanceMode.EPHEMERAL),
				"spot{{i}}    = \"${aws_spot_instance_request.{{key}}.id}\"", "spot{{i}}_name = \"{{name}}\"",
				"spot{{i}}_ip = \"${aws_spot_instance_request.{{key}}.public_ip}\"");
		appendDashboardReferences(buffer, context, context.getModes().get(InstanceMode.AUTO_SCALING),
				"asg{{i}}     = \"${aws_autoscaling_group.{{key}}.name}\"", "asg{{i}}_name = \"{{name}}\"");
		appendDashboardReferences(buffer, context, context.getModes().get(InstanceMode.AUTO_SCALING),
				"alb{{i}}     = \"${aws_lb.{{key}}.arn_suffix}\"",
				"alb{{i}}_tg  = \"${aws_lb_target_group.{{key}}.arn_suffix}\"", "alb{{i}}_name = \"{{name}}\"",
				"alb{{i}}_dns = \"${aws_lb.{{key}}.dns_name}\"");
		return buffer.toString();
	}

	private void appendDashboardReferences(final StringBuilder buffer, final Context context,
			final List<ProvQuoteInstance> instances, final String... formats) {
		final NormalizeFormat normalizeFormat = new NormalizeFormat();
		for (final String format : formats) {
			int index = 0;
			for (final ProvQuoteInstance instance : instances) {
				buffer.append('\n').append(replace(format, context.add("i", String.valueOf(index))
						.add("key", normalizeFormat.format(instance.getName())).add("name", instance.getName())));
				index++;
			}
		}
	}

	private String getMd(final List<ProvQuoteInstance> instances, final String format) {
		final StringBuilder buffer = new StringBuilder();
		for (int index = 0; index < instances.size(); index++) {
			buffer.append('\n').append(replace(format, "{{i}}", String.valueOf(index)));
		}
		return buffer.toString();
	}

	private String getDashboardNetwork(final Context context) throws IOException {
		final String format = toString("my-region/dashboard-widgets-line.json");
		return newMetric(context, format, "AWS/ApplicationELB", "LoadBalancer", "${alb{{i}}}",
				new String[] { "ProcessedBytes", "-", "-", "${alb{{i}}_name}" });
	}

	private String getDashboardLatency(final Context context) throws IOException {
		final String format = toString("my-region/dashboard-widgets-line.json");
		return newMetric(context, format, "AWS/ApplicationELB", "LoadBalancer", "${alb{{i}}}",
				new String[] { "TargetResponseTime", "-", "-", "${alb{{i}}_name}" });
	}

	private String getDashboardScaling(final Context context) throws IOException {
		final String format = toString("my-region/dashboard-widgets-area.json");
		return newMetric(context, format, "AWS/AutoScaling", "AutoScalingGroupName", "${asg{{i}}}",
				new String[] { "GroupInServiceInstances", "2ca02c", "left", "${asg{{i}}_name}" },
				new String[] { "GroupPendingInstances", "ff7f0e", "right", "Pending ${asg{{i}}_name}" },
				new String[] { "GroupTerminatingInstances", "d62728", "right", "Term. ${asg{{i}}_name}" });
	}

	private String getDashboardBalancing(final Context context) throws IOException {
		final String format = toString("my-region/dashboard-widgets-area.json");
		return newMetric(context, format, "AWS/ApplicationELB", "TargetGroup",
				"${alb{{i}}_tg}\", \"LoadBalancer\", \"${alb{{i}}}\"",
				new String[] { "HealthyHostCount", "2ca02c", "left", "OK ${alb{{i}}_name}" },
				new String[] { "UnHealthyHostCount", "d62728", "right", "KO ${alb{{i}}_name}" });
	}

	private String newMetric(final Context context, final String format, final String service, final String idProperty,
			final String id, String[]... variants) throws IOException {
		final List<ProvQuoteInstance> instances = context.getInstances();
		final StringBuilder buffer = new StringBuilder();
		final String serviceFmt = replace(format, "{{service}}", service);
		Arrays.stream(variants).forEach(variant -> {
			for (int index = 0; index < instances.size(); index++) {
				if (buffer.length() > 0) {
					buffer.append(',');
				}
				buffer.append('\n');
				buffer.append(replace(serviceFmt, "{{property}}", replace(idProperty, "{{i}}", String.valueOf(index)),
						"{{metric}}", variant[0], "{{id}}", id.replace("{{i}}", String.valueOf(index)), "{{color}}",
						variant[1], "{{position}}", variant[2], "{{label}}",
						replace(variant[3], "{{i}}", String.valueOf(index))));
			}
		});
		return buffer.toString();
	}

	/**
	 * Write referenced OS
	 */
	private void writeRegionOs(final Context context, final List<ProvQuoteInstance> instances) throws IOException {
		final Set<VmOs> oss = new HashSet<>();
		instances.stream().map(ProvQuoteInstance::getOs).forEach(oss::add);
		for (final VmOs os : oss) {
			templateFromTo(context.add("name", toAmiName(os)), "my-region/ami-os.tf", context.getLocation(),
					"ami-" + toAmiName(os) + ".tf");
		}
	}

	private String toAmiName(final VmOs os) {
		return mappingOsAmi.getOrDefault(os, os.name().toLowerCase(Locale.ENGLISH));
	}

	private void writeRegionStatics(final Context context) throws IOException {
		copyFromTo(context, "my-region/provider.tf", context.getLocation(), "provider.keep.tf");
		copyFromTo(context, "my-region/variables.keep.tf", context.getLocation(), "variables.keep.tf");
		copyFromTo(context, "my-region/vpc.tf", context.getLocation(), "vpc.tf");
	}

	/**
	 * Write instances configuration.
	 */
	private void writeRegionInstances(final Context context) throws IOException {
		// Write the the region bootstrap module : will be persistent to handle emptied region
		templateFromTo(context, "my-region.tf", context.getLocation() + ".keep.tf");

		// Write the instances, ALB,... within this instance
		final NormalizeFormat normalizeFormat = new NormalizeFormat();
		for (final Map.Entry<InstanceMode, List<ProvQuoteInstance>> entry : context.getModes().entrySet()) {
			final List<ProvQuoteInstance> instances = entry.getValue();
			final String mode = entry.getKey().name().toLowerCase(Locale.ENGLISH);
			final String template = "my-region/instance-" + mode + ".tf";
			for (final ProvQuoteInstance instance : instances) {
				context.add("key", normalizeFormat.format(instance.getName())).add("os", toAmiName(instance.getOs()))
						.add("type", instance.getPrice().getType().getName()).add("name", instance.getName())
						.add("spot-price", String.valueOf(instance.getMaxVariableCost()))
						.add("min", String.valueOf(instance.getMinQuantity()))
						.add("max", String.valueOf(ObjectUtils.defaultIfNull(instance.getMaxQuantity(), 10)))
						.add("root-device", getEbsDevices(instance, true, 0, 1)).add("user-data", getUserData(instance))
						.add("ebs-devices", getEbsDevices(instance, entry.getKey() == InstanceMode.AUTO_SCALING, 1,
								instance.getStorages().size()));
				templateFromTo(context, template, context.getLocation(), mode + "-" + context.get("key") + ".tf");
			}
		}
	}

	/**
	 * Return user-data related to given instance.
	 */
	private String getUserData(ProvQuoteInstance instance) throws IOException {
		final String sh = "terraform/user-data/nginx/" + instance.getOs().name().toLowerCase() + ".sh";
		try (InputStream shInput = ClassUtils.getDefaultClassLoader().getResourceAsStream(sh)) {
			if (shInput != null) {
				return "  user_data = <<-EOF\n" + IOUtils.toString(shInput, StandardCharsets.UTF_8) + "\n  EOF";
			}
		}
		return "";
	}

	private String getEbsDevices(final ProvQuoteInstance instance, final boolean intern, final int startIndex,
			final int endIndex) throws IOException {
		final StringBuilder builder = new StringBuilder();
		int idx = 0;
		final NormalizeFormat normalizeFormat = new NormalizeFormat();
		for (final ProvQuoteStorage storage : instance.getStorages()) {
			if (idx >= startIndex && idx < endIndex) {
				final String format = getDeviceFormat(intern, builder, idx);
				builder.append('\n').append(replace(format, "{{key}}", normalizeFormat.format(storage.getName()),
						"{{type}}", storage.getPrice().getType().getName(), "{{device}}",
						toDeviceName(instance.getOs(), idx), "{{instance}}", normalizeFormat.format(instance.getName()),
						"{{size}}", String.valueOf(storage.getSize())));
			}
			idx++;
		}
		return builder.toString();
	}

	private String getDeviceFormat(final boolean intern, final StringBuilder builder, int idx) throws IOException {
		final String deviceSuffix;
		if (intern) {
			if (idx >= 1) {
				deviceSuffix = "-1";
			} else {
				deviceSuffix = "-0";
			}
		} else {
			deviceSuffix = "";
		}
		return toString(String.format("my-region/instance-device%s.tf", deviceSuffix));
	}

	private String toDeviceName(final VmOs os, final int index) {
		if (index == 0) {
			// Root device
			return mappingOsRootDevice.getOrDefault(os, DEFAULT_ROOT_DEVICE);
		}
		final String format = mappingOsEbsDevice.getOrDefault(os, DEFAULT_EBS_DEVICE);
		return format.substring(0, format.length() - 1)
				+ String.valueOf((char) (format.charAt(format.length() - 1) + index - 1));
	}

	/**
	 * Write the secrets required by the provider.
	 *
	 * @param subscription
	 *            The subscription identifier.
	 * @throws IOException
	 *             When secret cannot be written.
	 */
	public void writeSecrets(final Subscription subscription) throws IOException {
		try (final Writer out = new FileWriterWithEncoding(utils.toFile(subscription, "secrets.auto.tfvars"),
				StandardCharsets.UTF_8)) {
			final Map<String, String> parameters = subscriptionResource.getParametersNoCheck(subscription.getId());
			out.write("access_key = \"");
			out.write(parameters.get(ProvAwsPluginResource.PARAMETER_ACCESS_KEY_ID));
			out.write("\"\nsecret_key = \"");
			out.write(parameters.get(ProvAwsPluginResource.PARAMETER_SECRET_ACCESS_KEY));
			out.write("\"\n");
		}
	}

	private void copy(final Context context, final String... fragments) throws IOException {
		Files.copy(new ClassPathResource("terraform/" + String.join("/", fragments)).getInputStream(),
				utils.toFile(context.getSubscription(), fragments).toPath(), StandardCopyOption.REPLACE_EXISTING);
	}

	private void copyFromTo(final Context context, final String from, final String... toFragments) throws IOException {
		Files.copy(new ClassPathResource("terraform/" + from).getInputStream(),
				utils.toFile(context.getSubscription(), toFragments).toPath(), StandardCopyOption.REPLACE_EXISTING);
	}

	private void template(final Context context, final Function<String, String> formater, final String... fragments)
			throws IOException {
		try (InputStream source = new ClassPathResource("terraform/" + String.join("/", fragments)).getInputStream();
				FileOutputStream target = new FileOutputStream(utils.toFile(context.getSubscription(), fragments));
				Writer targetW = new OutputStreamWriter(target);) {
			targetW.write(formater.apply(IOUtils.toString(source, StandardCharsets.UTF_8)));
		}
	}

	private void templateFromTo(final Context context, final String from, final String... toFragments)
			throws IOException {
		try (InputStream source = new ClassPathResource("terraform/" + from).getInputStream();
				FileOutputStream target = new FileOutputStream(utils.toFile(context.getSubscription(), toFragments));
				Writer targetW = new OutputStreamWriter(target);) {
			targetW.write(replace(IOUtils.toString(source, StandardCharsets.UTF_8), context));
		}
	}

	private String replace(String source, String... replaces) {
		String result = source;
		for (int index = 0; index < replaces.length; index += 2) {
			result = StringUtils.replace(result, replaces[index], replaces[index + 1]);
		}
		return result;
	}

	private String replace(String source, final Context context) {
		String result = source;
		for (final Map.Entry<String, String> entry : context.getContext().entrySet()) {
			result = StringUtils.replace(result, String.format("{{%s}}", entry.getKey()), entry.getValue());
		}
		return result;
	}

	private InstanceMode toMode(final ProvQuoteInstance instance) {
		// xLB
		if (instance.getMinQuantity() != 1 || instance.getMaxQuantity() == null
				|| instance.getMaxQuantity().doubleValue() > instance.getMinQuantity()) {
			return InstanceMode.AUTO_SCALING;
		}

		// Single EC2 but with a price condition
		if (ObjectUtils.defaultIfNull(instance.getMaxVariableCost(), 0d) > 0) {
			return InstanceMode.EPHEMERAL;
		}
		// Single EC2
		return InstanceMode.VM;
	}

	private String toString(final String path) throws IOException {
		return IOUtils.toString(new ClassPathResource("terraform/" + path).getURI(), StandardCharsets.UTF_8);
	}
}
