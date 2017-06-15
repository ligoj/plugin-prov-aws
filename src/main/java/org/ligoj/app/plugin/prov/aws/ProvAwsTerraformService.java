package org.ligoj.app.plugin.prov.aws;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;
import org.ligoj.app.plugin.prov.model.VmOs;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Service in charge of terraform generation for AWS.
 * 
 * @author alocquet
 */
@Slf4j
@Service
public class ProvAwsTerraformService {
	/**
	 * mapping between os name and ami search string.
	 */
	private final static Map<VmOs, String> MAPPING_OS_AMI = new HashMap<>();
	/**
	 * terraform template
	 */
	private static String TERRAFORM_TEMPLATE;

	static {
		MAPPING_OS_AMI.put(VmOs.WINDOWS, "Windows_Server-2016-English-Full-Base");
		MAPPING_OS_AMI.put(VmOs.SUSE, "suse-sles-12*hvm-ssd-x86_64");
		MAPPING_OS_AMI.put(VmOs.RHE, "RHEL-7.4*");
		MAPPING_OS_AMI.put(VmOs.LINUX, "amzn-ami-hvm-*-x86_64-gp2");
		try {
			TERRAFORM_TEMPLATE = new String(Files.readAllBytes(Paths.get(Thread.currentThread().getContextClassLoader()
					.getResource("terraform/template/terraform.tf.mustache").toURI().getPath())));
		} catch (final IOException | URISyntaxException e) {
			log.error("Unexpected error. Terraform template should be in plugin-prov-aws jar.", e);
			TERRAFORM_TEMPLATE = StringUtils.EMPTY;
		}
	}

	/**
	 * generate terraform from a quote instance.
	 * 
	 * @param quoteInstance
	 *            quote instance
	 * 
	 * @return terraform as string
	 */
	public String getTerraform(final ProvQuoteInstance quoteInstance) {
		// generate template with regexp. Replace with mustache.java if the
		// template become more complex
		return TERRAFORM_TEMPLATE
				.replaceAll("\\{\\{project-name\\}\\}",
						quoteInstance.getConfiguration().getSubscription().getProject().getName())
				.replaceAll("\\{\\{instance-name\\}\\}", quoteInstance.getName())
				.replaceAll("\\{\\{instance-type\\}\\}", quoteInstance.getInstancePrice().getInstance().getName())
				.replaceAll("\\{\\{ami-search\\}\\}", MAPPING_OS_AMI.get(quoteInstance.getInstancePrice().getOs()));
	}

}
