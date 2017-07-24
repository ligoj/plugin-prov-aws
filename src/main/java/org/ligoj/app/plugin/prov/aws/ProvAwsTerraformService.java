package org.ligoj.app.plugin.prov.aws;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.prov.QuoteStorageVo;
import org.ligoj.app.plugin.prov.QuoteVo;
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
	private final static Map<VmOs, String[]> MAPPING_OS_AMI = new HashMap<>();

	/**
	 * mapping between OS name and AMI's owner search string.
	 */
	private final static Map<VmOs, String> MAPPING_OS_OWNER = new HashMap<>();

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
	 */
	public void writeTerraform(final Writer writer, final QuoteVo quote, final Subscription subscription) throws IOException {
		final String projectName = subscription.getProject().getName();
		writeProvider(writer);
		if (!quote.getInstances().isEmpty()) {
			writePublicKey(writer);
			writeSecurityGroup(writer, projectName);
			writeKeyPair(writer, projectName);
			final Set<VmOs> osToSearch = new HashSet<>();
			for (final ProvQuoteInstance instance : quote.getInstances()) {
				writeInstance(writer, quote, instance, projectName);
				osToSearch.add(instance.getInstancePrice().getOs());
			}

			// AMI part
			final String account = subscriptionResource.getParameters(subscription.getId()).get(ProvAwsResource.PARAMETER_ACCOUNT);
			for (final VmOs os : osToSearch) {
				writeAmiSearch(writer, os, account);
			}
		}
		writeStandaloneStorages(writer, projectName, quote.getStorages());
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
		final VmOs os = instance.getInstancePrice().getOs();
		final String instanceName = instance.getName();
		final String instanceType = instance.getInstancePrice().getInstance().getName();
		final boolean spot = SPOT_INSTANCE_PRICE_TYPE.equals(instance.getInstancePrice().getType().getName());

		writer.write("/* instance */\n");
		if (spot) {
			writer.write("resource \"aws_spot_instance_request\" \"vm-" + instanceName + "\" {\n");
			writer.write("  spot_price    = \"0.03\"\n");
		} else {
			writer.write("resource \"aws_instance\" \"vm-" + instanceName + "\" {\n");
		}
		writer.write("  ami           = \"${data.aws_ami.ami-" + os.name() + ".id}\"\n");
		writer.write("  instance_type = \"" + instanceType + "\"\n");
		writer.write("  key_name    	= \"" + projectName + "-key\"\n");
		writer.write("  vpc_security_group_ids = [ \"${aws_security_group.vm-sg.id}\" ]\n");
		writeInstanceStorages(writer, quote, instance);
		writeTags(writer, projectName, projectName + "-" + instanceName);
		writer.write("}\n");
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
		writer.write("  description = \"Allow ssh inbound traffic and all outbund traffic\"\n");
		writer.write("  ingress {\n");
		writer.write("    from_port   = 22\n");
		writer.write("    to_port     = 22\n");
		writer.write("    protocol    = \"TCP\"\n");
		writer.write("    cidr_blocks = [\"0.0.0.0/0\"]\n");
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
			writer.write("    volume_type = \"" + storage.getType().getName() + "\"\n");
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
	 *            project name
	 * @param storages
	 *            storages
	 * @throws IOException
	 *             exception thrown during write
	 */
	private void writeStandaloneStorages(final Writer writer, final String projectName, final List<QuoteStorageVo> storages)
			throws IOException {
		for (final QuoteStorageVo storage : storages) {
			if (storage.getQuoteInstance() == null) {
				writer.write("resource \"aws_ebs_volume\" \"" + storage.getName() + "\" {\n");
				writer.write("  availability_zone = \"eu-west-1a\"\n");
				writer.write("  type = \"" + storage.getType().getName() + "\"\n");
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