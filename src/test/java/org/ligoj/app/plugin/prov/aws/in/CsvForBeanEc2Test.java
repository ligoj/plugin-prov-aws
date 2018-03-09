package org.ligoj.app.plugin.prov.aws.in;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Test class of {@link CsvForBeanEc2}
 */
public class CsvForBeanEc2Test {

	@Test
	public void readNotCompute() throws IOException {
		final BufferedReader reader = new BufferedReader(new InputStreamReader(
				new ClassPathResource("mock-server/aws/index-small-not-compute.csv").getInputStream()));
		Assertions.assertNull(new CsvForBeanEc2(reader).read());
	}
	@Test
	public void readTooFewData() throws IOException {
		final BufferedReader reader = new BufferedReader(new InputStreamReader(
				new ClassPathResource("mock-server/aws/index-small-too-few.csv").getInputStream()));
		Assertions.assertNull(new CsvForBeanEc2(reader).read());
	}
	@Test
	public void readDedicatedHost() throws IOException {
		final BufferedReader reader = new BufferedReader(new InputStreamReader(
				new ClassPathResource("mock-server/aws/index-small-dedicated-host.csv").getInputStream()));
		Assertions.assertNull(new CsvForBeanEc2(reader).read());
	}
	@Test
	public void readNoOs() throws IOException {
		final BufferedReader reader = new BufferedReader(new InputStreamReader(
				new ClassPathResource("mock-server/aws/index-small-no-os.csv").getInputStream()));
		Assertions.assertNull(new CsvForBeanEc2(reader).read());
	}
	@Test
	public void readNAOs() throws IOException {
		final BufferedReader reader = new BufferedReader(new InputStreamReader(
				new ClassPathResource("mock-server/aws/index-small-na-os.csv").getInputStream()));
		Assertions.assertNull(new CsvForBeanEc2(reader).read());
	}

	@Test
	public void read() throws IOException {
		final BufferedReader reader = new BufferedReader(new InputStreamReader(
				new ClassPathResource("mock-server/aws/index-small-ok.csv").getInputStream()));
		Assertions.assertNotNull(new CsvForBeanEc2(reader).read());
	}
}
