package org.ligoj.app.plugin.prov.aws;

import java.io.IOException;
import java.io.Writer;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.prov.QuoteVo;
import org.ligoj.app.plugin.prov.model.InternetAccess;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;
import org.ligoj.app.plugin.prov.model.ProvQuoteStorage;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.ligoj.app.resource.subscription.SubscriptionResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service in charge of Terraform generation for AWS.
 */
@Service
public class ProvAwsTerraformService {
	@Autowired
	private SubscriptionResource subscriptionResource;

	private static final String SPOT_INSTANCE_PRICE_TYPE = "Spot";

	/**
	 * mapping between OS name and AMI search string.
	 */
	private static final Map<VmOs, String[]> MAPPING_OS_AMI = new EnumMap<>(VmOs.class);

	/**
	 * mapping between OS name and AMI's owner search string.
	 */
	private static final Map<VmOs, String> MAPPING_OS_OWNER = new EnumMap<>(VmOs.class);

	static {
		// AWS Images
		MAPPING_OS_AMI.put(VmOs.WINDOWS, new String[] { "name", "Windows_Server-2016-English-Full-Base*" });
		MAPPING_OS_AMI.put(VmOs.SUSE, new String[] { "name", "suse-sles-12*hvm-ssd-x86_64" });
		MAPPING_OS_AMI.put(VmOs.RHEL, new String[] { "name", "RHEL-7.4*" });
		MAPPING_OS_AMI.put(VmOs.LINUX, new String[] { "name", "amzn-ami-hvm-*-x86_64-gp2" });

		// CentOS https://wiki.centos.org/Cloud/AWS
		MAPPING_OS_AMI.put(VmOs.CENTOS, new String[] { "product-code", "aw0evgkw8e5c1q413zgy5pjce" });

		// Debian https://wiki.debian.org/Cloud/AmazonEC2Image
		MAPPING_OS_AMI.put(VmOs.DEBIAN, new String[] { "name", "debian-stretch-hvm-x86_64-gp2-*" });
	}

	static {
		// AWS Images
		MAPPING_OS_OWNER.put(VmOs.WINDOWS, "amazon");
		MAPPING_OS_OWNER.put(VmOs.SUSE, "amazon");
		MAPPING_OS_OWNER.put(VmOs.RHEL, "amazon");
		MAPPING_OS_OWNER.put(VmOs.LINUX, "amazon");

		// CentOS https://wiki.centos.org/Cloud/AWS
		MAPPING_OS_OWNER.put(VmOs.CENTOS, "aws-marketplace");

		// Debian https://wiki.debian.org/Cloud/AmazonEC2Image
		MAPPING_OS_OWNER.put(VmOs.DEBIAN, "379101102735");
	}

	/**
	 * Write Terraform content from a quote instance.
	 * 
	 * @param writer
	 *            Target output of Terraform content.
	 * @param quote
	 *            quote instance
	 * @param subscription
	 *            The related subscription.
	 * @throws IOException
	 *             When Terraform content cannot be written.
	 */
	public void writeTerraform(final Writer writer, final QuoteVo quote, final Subscription subscription) throws IOException {
		final String projectName = subscription.getProject().getName();
		writeProvider(writer);
		if (!quote.getInstances().isEmpty()) {
			writePublicKey(writer);
			writeNetwork(writer, projectName,
					quote.getInstances().stream().map(ProvQuoteInstance::getInternet).collect(Collectors.toSet()));
			writeSecurityGroup(writer, projectName);
			writeKeyPair(writer, projectName);
			final Set<VmOs> osToSearch = new HashSet<>();
			for (final ProvQuoteInstance instance : quote.getInstances()) {
				writeInstance(writer, quote, instance, projectName);
				osToSearch.add(instance.getPrice().getOs());
			}

			// AMI part
			final String account = subscriptionResource.getParameters(subscription.getId()).get(ProvAwsPluginResource.PARAMETER_ACCOUNT);
			for (final VmOs os : osToSearch) {
				writeAmiSearch(writer, os, account);
			}
		}
		writeStandaloneStorages(writer, projectName, quote.getStorages());
	}

	/**
	 * write Network
	 * 
	 * @param writer
	 *            writer
	 * @param project
	 *            project name
	 * @param types
	 *            internet access types of instances
	 * @throws IOException
	 *             exception
	 */
	private void writeNetwork(final Writer writer, final String project, final Set<InternetAccess> types) throws IOException {
		writer.write("/* network */\n");
		writer.write("resource \"aws_vpc\" \"terraform\" {\n");
		writer.write("  cidr_block = \"10.0.0.0/16\"\n");
		writer.write("}\n");
		if (types.contains(InternetAccess.PUBLIC) || types.contains(InternetAccess.PRIVATE_NAT)) {
			writeSubnet(writer, project, InternetAccess.PUBLIC, "10.0.1.0/24");
			writeInternetGateway(writer);
			writeRouteTable(writer, project, InternetAccess.PUBLIC);
		}
		if (types.contains(InternetAccess.PRIVATE_NAT)) {
			writeSubnet(writer, project, InternetAccess.PRIVATE_NAT, "10.0.2.0/24");
			writeNatGateway(writer);
			writeRouteTable(writer, project, InternetAccess.PRIVATE_NAT);
		}
		if (types.contains(InternetAccess.PRIVATE)) {
			writeSubnet(writer, project, InternetAccess.PRIVATE, "10.0.3.0/24");
		}
	}

	/**
	 * write a nat gateway
	 * 
	 * @param writer
	 *            writer
	 * @throws IOException
	 *             exception
	 */
	private void writeNatGateway(final Writer writer) throws IOException {
		writer.write("resource \"aws_eip\" \"" + InternetAccess.PRIVATE_NAT.name() + "\" {\n");
		writer.write("  vpc      = true\n");
		writer.write("}\n");
		writer.write("resource \"aws_nat_gateway\" \"default\" {\n");
		writer.write("  allocation_id = \"${aws_eip." + InternetAccess.PRIVATE_NAT.name() + ".id}\"\n");
		writer.write("  subnet_id     = \"${aws_subnet." + InternetAccess.PUBLIC.name() + ".id}\"\n");
		writer.write("}\n");
	}

	/**
	 * write a route table
	 * 
	 * @param writer
	 *            writer
	 * @param project
	 *            project name
	 * @param type
	 *            internet access
	 * @throws IOException
	 *             exception
	 */
	private void writeRouteTable(final Writer writer, final String project, final InternetAccess type) throws IOException {
		writer.write("resource \"aws_route_table\" \"" + type.name() + "\" {\n");
		writer.write("  vpc_id     = \"${aws_vpc.terraform.id}\"\n");
		writer.write("  route {\n");
		writer.write("    cidr_block = \"0.0.0.0/0\"\n");
		if (type == InternetAccess.PUBLIC) {
			writer.write("    gateway_id = \"${aws_internet_gateway.default.id}\"\n");
		} else {
			writer.write("        nat_gateway_id = \"${aws_nat_gateway.default.id}\"\n");
		}
		writer.write("  }\n");
		writeTags(writer, project, type.name());
		writer.write("}\n");
		writer.write("resource \"aws_route_table_association\" \"" + type.name() + "\" {\n");
		writer.write("    subnet_id = \"${aws_subnet." + type.name() + ".id}\"\n");
		writer.write("    route_table_id = \"${aws_route_table." + type.name() + ".id}\"\n");
		writer.write("}\n");
	}

	/**
	 * write internet gateway
	 * 
	 * @param writer
	 *            writer
	 * @throws IOException
	 *             exception
	 */
	private void writeInternetGateway(final Writer writer) throws IOException {
		writer.write("resource \"aws_internet_gateway\" \"default\" {\n");
		writer.write("  vpc_id     = \"${aws_vpc.terraform.id}\"\n");
		writer.write("}\n");
	}

	/**
	 * write a subnet
	 * 
	 * @param writer
	 *            writer
	 * @param project
	 *            project name
	 * @param type
	 *            internet access type
	 * @param cidr
	 *            cidr
	 * @throws IOException
	 *             exception
	 */
	private void writeSubnet(final Writer writer, final String project, final InternetAccess type, final String cidr) throws IOException {
		writer.write("/* " + type.name() + " subnet */\n");
		writer.write("resource \"aws_subnet\" \"" + type + "\" {\n");
		writer.write("  vpc_id     = \"${aws_vpc.terraform.id}\"\n");
		writer.write("  cidr_block = \"" + cidr + "\"\n");
		if (type == InternetAccess.PUBLIC) {
			writer.write("  map_public_ip_on_launch = true\n");
		}
		writeTags(writer, project, type.name());
		writer.write("}\n");
	}

	/**
	 * Write an instance.
	 * 
	 * @param writer
	 *            Target output of Terraform content.
	 * @param quote
	 *            quote instance
	 * @param instance
	 *            instance definition
	 * @param projectName
	 *            project name
	 * @throws IOException
	 *             exception thrown during write
	 */
	private void writeInstance(final Writer writer, final QuoteVo quote, final ProvQuoteInstance instance, final String projectName)
			throws IOException {
		final VmOs os = instance.getPrice().getOs();
		final String instanceName = instance.getName();
		final String instanceType = instance.getPrice().getType().getName();
		final boolean spot = SPOT_INSTANCE_PRICE_TYPE.equals(instance.getPrice().getTerm().getName());
		final boolean autoscaling = instance.getMinQuantity() != 1 || instance.getMaxQuantity() == null;
		writer.write("/* instance */\n");
		if (autoscaling) {
			writer.write("resource \"aws_launch_configuration\" \"vm-" + instanceName + "\" {\n");
		} else if (spot) {
			writer.write("resource \"aws_spot_instance_request\" \"vm-" + instanceName + "\" {\n");
			writer.write("  spot_price    = \"0.03\"\n");
		} else {
			writer.write("resource \"aws_instance\" \"vm-" + instanceName + "\" {\n");
		}
		writer.write("  " + (autoscaling ? "image_id" : "ami") + "           = \"${data.aws_ami.ami-" + os.name() + ".id}\"\n");
		writer.write("  name    		= \"" + instanceName + "\"\n");
		writer.write("  instance_type = \"" + instanceType + "\"\n");
		writer.write("  key_name    	= \"" + projectName + "-key\"\n");
		writer.write("  " + (autoscaling ? "security_groups" : "vpc_security_group_ids") + " = [ \"${aws_security_group.vm-sg.id}\" ]\n");
		writeInstanceStorages(writer, quote, instance);
		if (!autoscaling) {
			writer.write("  subnet_id     = \"${aws_subnet." + instance.getInternet().name() + ".id}\"\n");
			writeTags(writer, projectName, projectName + "-" + instanceName);
		}
		writer.write("}\n");

		// autoscaling
		if (autoscaling) {
			writer.write("/* autoscaling */\n");
			writer.write("resource \"aws_autoscaling_group\" \"" + instanceName + "autoscaling-group\" {\n");
			writer.write("  min_size                  = " + instance.getMinQuantity() + "\n");
			writer.write("  max_size                  = " + instance.getMaxQuantity() + "\n");
			writer.write("  launch_configuration      = \"${aws_launch_configuration.vm-" + instanceName + ".name}\"\n");
			writer.write("  vpc_zone_identifier     = [\"${aws_subnet." + instance.getInternet().name() + ".id}\"]\n");
			writer.write("  tags = [{\n");
			writer.write("    key=\"Project\"\n");
			writer.write("    value=\"" + projectName + "\"\n");
			writer.write("    propagate_at_launch = true\n");
			writer.write("  }, {\n");
			writer.write("    key=\"Name\"\n");
			writer.write("    value=\"" + projectName + "-" + instanceName + "\"\n");
			writer.write("    propagate_at_launch = true\n");
			writer.write("  }]\n");
			writer.write("}\n");
		}
	}

	/**
	 * Write an AMI search Terraform query.
	 * 
	 * @param writer
	 *            Target output of Terraform content.
	 * @param os
	 *            Instance OS.
	 * @param account
	 *            Target account Id used to filter the available AMI.
	 * @throws IOException
	 *             exception thrown during write
	 * @see http://docs.aws.amazon.com/cli/latest/reference/ec2/describe-images.html
	 * @see https://www.terraform.io/docs/providers/aws/d/ami.html
	 */
	private void writeAmiSearch(final Writer writer, final VmOs os, final String account) throws IOException {
		writer.write("/* search ami id */\n");
		writer.write("data \"aws_ami\" \"ami-" + os.name() + "\" {\n");
		writer.write("  most_recent = true\n");

		// Add specific filters of this OS
		for (int i = 0; i < MAPPING_OS_AMI.get(os).length; i += 2) {
			writer.write("  filter {\n");
			writer.write("    name   = \"" + MAPPING_OS_AMI.get(os)[i] + "\"\n");
			writer.write("    values = [\"" + MAPPING_OS_AMI.get(os)[i + 1] + "\"]\n");
			writer.write("  }\n");
		}

		// Add generic filters : HVM, ...
		writer.write("  filter {\n");
		writer.write("    name   = \"virtualization-type\"\n");
		writer.write("    values = [\"hvm\"]\n");
		writer.write("  }\n");

		// Target account before the generic account
		writer.write("  owners = [\"" + account + "\", \"" + MAPPING_OS_OWNER.get(os) + "\"]\n");
		writer.write("}\n");
	}

	/**
	 * Write a key pair.
	 * 
	 * @param writer
	 *            Target output of Terraform content.
	 * @param projectName
	 *            project Name
	 * @throws IOException
	 *             exception thrown during write
	 */
	private void writeKeyPair(final Writer writer, final String projectName) throws IOException {
		writer.write("/* key pair*/\n");
		writer.write("resource \"aws_key_pair\" \"vm-keypair\" {\n");
		writer.write("  key_name   = \"" + projectName + "-key\"\n");
		writer.write("  public_key = \"${var.publickey}\"\n");
		writer.write("}\n");
	}

	/**
	 * Write a security group.
	 * 
	 * @param writer
	 *            Target output of Terraform content.
	 * @param projectName
	 *            project Name
	 * @throws IOException
	 *             exception thrown during write
	 */
	private void writeSecurityGroup(final Writer writer, final String projectName) throws IOException {
		writer.write("/* security group */\n");
		writer.write("resource \"aws_security_group\" \"vm-sg\" {\n");
		writer.write("  name        = \"" + projectName + "-sg\"\n");
		writer.write("  description = \"Allow ssh inbound traffic, all inbound traffic in security group and all outbund traffic\"\n");
		writer.write("  vpc_id     = \"${aws_vpc.terraform.id}\"\n");
		writer.write("  ingress {\n");
		writer.write("    from_port   = 22\n");
		writer.write("    to_port     = 22\n");
		writer.write("    protocol    = \"TCP\"\n");
		writer.write("    cidr_blocks = [\"0.0.0.0/0\"]\n");
		writer.write("  }\n");
		writer.write("  ingress {\n");
		writer.write("    from_port   = 0\n");
		writer.write("    to_port     = 0\n");
		writer.write("    protocol    = \"TCP\"\n");
		writer.write("    self        = true\n");
		writer.write("  }\n");
		writer.write("  egress {\n");
		writer.write("    from_port = 0\n");
		writer.write("    to_port = 0\n");
		writer.write("    protocol = \"-1\"\n");
		writer.write("    cidr_blocks = [\"0.0.0.0/0\"]\n");
		writer.write("  }\n");
		writeTags(writer, projectName, projectName);
		writer.write("}\n");
	}

	/**
	 * Write public key.
	 * 
	 * @param writer
	 *            Target output of Terraform content.
	 * @throws IOException
	 *             exception thrown during write
	 */
	private void writePublicKey(final Writer writer) throws IOException {
		writer.write("variable publickey {\n");
		writer.write("  description = \"SSH Public key used to access nginx EC2 Server\"\n");
		writer.write("}\n");
	}

	/**
	 * Write provider.
	 * 
	 * @param writer
	 *            Target output of Terraform content.
	 * @throws IOException
	 *             exception thrown during write
	 */
	private void writeProvider(final Writer writer) throws IOException {
		writer.write("variable \"AWS_ACCESS_KEY_ID\" {}\n");
		writer.write("variable \"AWS_SECRET_ACCESS_KEY\" {}\n");

		writer.write("provider \"aws\" {\n");
		writer.write("  region = \"eu-west-1\"\n");
		writer.write("  access_key = \"${var.AWS_ACCESS_KEY_ID}\"\n");
		writer.write("  secret_key = \"${var.AWS_SECRET_ACCESS_KEY}\"\n");
		writer.write("}\n");
	}

	/**
	 * Write instance storages.
	 * 
	 * @param writer
	 *            Target output of Terraform content.
	 * @param quote
	 *            quote instance
	 * @param instance
	 *            instance
	 * @throws IOException
	 *             exception thrown during write
	 */
	private void writeInstanceStorages(final Writer writer, final QuoteVo quote, final ProvQuoteInstance instance) throws IOException {
		int idx = 0;
		for (final ProvQuoteStorage storage : instance.getStorages()) {
			if (idx == 0) {
				writer.write("  root_block_device {\n");
			} else {
				writer.write("  ebs_block_device {\n");
				writer.write("    device_name = \"/dev/sda" + idx + "\"\n");
			}
			writer.write("    volume_type = \"" + storage.getPrice().getType().getName() + "\"\n");
			writer.write("    volume_size = " + storage.getSize() + "\n");
			writer.write("  }\n");
			idx++;
		}
	}

	/**
	 * Write standalone storages.
	 * 
	 * @param writer
	 *            Target output of Terraform content.
	 * @param projectName
	 *            The project name.
	 * @param storages
	 *            Storages of the quote.
	 */
	private void writeStandaloneStorages(final Writer writer, final String projectName, final List<ProvQuoteStorage> storages)
			throws IOException {
		for (final ProvQuoteStorage storage : storages) {
			if (storage.getQuoteInstance() == null) {
				writer.write("resource \"aws_ebs_volume\" \"" + storage.getName() + "\" {\n");
				writer.write("  availability_zone = \"eu-west-1a\"\n");
				writer.write("  type = \"" + storage.getPrice().getType().getName() + "\"\n");
				writer.write("  size = " + storage.getSize() + "\n");
				writeTags(writer, projectName, projectName + "-" + storage.getName());
				writer.write("}\n");
			}
		}
	}

	/**
	 * Write a tag.
	 * 
	 * @param writer
	 *            Target output of Terraform content.
	 * @param project
	 *            project name
	 * @param name
	 *            resource name
	 * @throws IOException
	 *             exception thrown during write
	 */
	private void writeTags(final Writer writer, final String project, final String name) throws IOException {
		writer.write("  tags = {\n");
		writer.write("    Project = \"" + project + "\"\n");
		writer.write("    Name = \"" + name + "\"\n");
		writer.write("  }\n");
	}
}