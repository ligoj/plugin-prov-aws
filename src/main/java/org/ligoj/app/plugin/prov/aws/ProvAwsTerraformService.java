package org.ligoj.app.plugin.prov.aws;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.prov.QuoteStorageVo;
import org.ligoj.app.plugin.prov.QuoteVo;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.springframework.stereotype.Service;

/**
 * Service in charge of terraform generation for AWS.
 * 
 * @author alocquet
 */
@Service
public class ProvAwsTerraformService {

	private static final String SPOT_INSTANCE_PRICE_TYPE = "Spot";

	/**
	 * mapping between os name and ami search string.
	 */
	private final static Map<VmOs, String> MAPPING_OS_AMI = new HashMap<>();

	static {
		MAPPING_OS_AMI.put(VmOs.WINDOWS, "Windows_Server-2016-English-Full-Base*");
		MAPPING_OS_AMI.put(VmOs.SUSE, "suse-sles-12*hvm-ssd-x86_64");
		MAPPING_OS_AMI.put(VmOs.RHE, "RHEL-7.4*");
		MAPPING_OS_AMI.put(VmOs.LINUX, "amzn-ami-hvm-*-x86_64-gp2");
	}

	/**
	 * generate terraform from a quote instance.
	 * 
	 * @param quoteInstance
	 *            quote instance
	 * 
	 * @return terraform as string
	 * @throws IOException
	 */
	public void writeTerraform(final Writer writer, final QuoteVo quote, final Subscription subscription)
			throws IOException {
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
			for (final VmOs os : osToSearch) {
				writeAmiSearch(writer, os);
			}
		}
		writeStandaloneStorages(writer, projectName, quote.getStorages());
	}

	/**
	 * write an instance in terraform
	 * 
	 * @param writer
	 *            where we write
	 * @param quote
	 *            global quote
	 * @param instance
	 *            instance definition
	 * @param projectName
	 *            project name
	 * @throws IOException
	 *             exception thrown during write
	 */
	private void writeInstance(final Writer writer, final QuoteVo quote, final ProvQuoteInstance instance,
			final String projectName) throws IOException {
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
	 * write an ami search in terraform
	 * 
	 * @param writer
	 *            where we write
	 * @param os
	 *            instance os
	 * @throws IOException
	 *             exception thrown during write
	 */
	private void writeAmiSearch(final Writer writer, final VmOs os) throws IOException {
		writer.write("/* search ami id */\n");
		writer.write("data \"aws_ami\" \"ami-" + os.name() + "\" {\n");
		writer.write("  most_recent = true\n");
		writer.write("  filter {\n");
		writer.write("    name   = \"name\"\n");
		writer.write("    values = [\"" + MAPPING_OS_AMI.get(os) + "\"]\n");
		writer.write("  }\n");
		writer.write("  filter {\n");
		writer.write("    name   = \"virtualization-type\"\n");
		writer.write("    values = [\"hvm\"]\n");
		writer.write("  }\n");
		writer.write("  owners = [\"amazon\", \"309956199498\"]\n");
		writer.write("}\n");
	}

	/**
	 * write a key pair in terraform
	 * 
	 * @param writer
	 *            where we write
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
	 * write a security group in terraform
	 * 
	 * @param writer
	 *            where we write
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
	 * write public key
	 * 
	 * @param writer
	 *            where we write
	 * @throws IOException
	 *             exception thrown during write
	 */
	private void writePublicKey(final Writer writer) throws IOException {
		writer.write("variable publickey {\n");
		writer.write("  description = \"SSH Public key used to access nginx EC2 Server\"\n");
		writer.write("}\n");
	}

	/**
	 * write provider
	 * 
	 * @param writer
	 *            where we write
	 * @throws IOException
	 *             exception thrown during write
	 */
	private void writeProvider(final Writer writer) throws IOException {
		writer.write("provider \"aws\" {\n");
		writer.write("  region = \"eu-west-1\"\n");
		writer.write("}\n");
	}

	/**
	 * write storages in terraform
	 * 
	 * @param writer
	 *            where we write
	 * @param quote
	 *            original quote
	 * @param instance
	 *            instance
	 * @throws IOException
	 *             exception thrown during write
	 */
	private void writeInstanceStorages(final Writer writer, final QuoteVo quote, final ProvQuoteInstance instance)
			throws IOException {
		int idx = 0;
		for (final QuoteStorageVo storage : quote.getStorages().stream()
				.filter(storage -> storage.getQuoteInstance() == instance.getId()).collect(Collectors.toList())) {
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
	 * write storages in terraform
	 * 
	 * @param writer
	 *            where we write
	 * @param projectName
	 *            project name
	 * @param storages
	 *            storages
	 * @throws IOException
	 *             exception thrown during write
	 */
	private void writeStandaloneStorages(final Writer writer, final String projectName,
			final List<QuoteStorageVo> storages) throws IOException {
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
	 * write an instance in terraform
	 * 
	 * @param writer
	 *            where we write
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