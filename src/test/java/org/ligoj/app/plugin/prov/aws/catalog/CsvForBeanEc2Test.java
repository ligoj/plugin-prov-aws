/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws.catalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.ligoj.app.plugin.prov.aws.catalog.vm.ec2.CsvForBeanEc2;
import org.springframework.core.io.ClassPathResource;

/**
 * Test class of {@link CsvForBeanEc2}
 */
class CsvForBeanEc2Test {

	private void assertReadNull(final String file) throws IOException {
		final var reader = new BufferedReader(
				new InputStreamReader(new ClassPathResource(file).getInputStream()));
		Assertions.assertNull(new CsvForBeanEc2(reader).read());
	}

	@Test
	void readNotCompute() throws IOException {
		assertReadNull("mock-server/aws/index-ec2-small-not-compute.csv");
	}

	@Test
	void readTooFewData() throws IOException {
		assertReadNull("mock-server/aws/index-ec2-small-too-few.csv");
	}

	@Test
	void readDedicatedHost() throws IOException {
		assertReadNull("mock-server/aws/index-ec2-small-dedicated-host.csv");
	}

	@Test
	void readNotUsed() throws IOException {
		assertReadNull("mock-server/aws/index-ec2-small-not-used.csv");
	}

	@Test
	void readNAOs() throws IOException {
		final var reader = new BufferedReader(new InputStreamReader(
				new ClassPathResource("mock-server/aws/index-ec2-small-compute.csv").getInputStream()));
		var beanEc2 = new CsvForBeanEc2(reader);
		Assertions.assertEquals("HB5V2X8TXQUTDZBV", beanEc2.read().getSku());
		Assertions.assertEquals("HB5V2X8TXQUTDZBW", beanEc2.read().getSku());
	}

	@Test
	void read() throws IOException {
		final var reader = new BufferedReader(new InputStreamReader(
				new ClassPathResource("mock-server/aws/index-ec2-small-ok.csv").getInputStream()));
		Assertions.assertNotNull(new CsvForBeanEc2(reader).read());
	}
}
