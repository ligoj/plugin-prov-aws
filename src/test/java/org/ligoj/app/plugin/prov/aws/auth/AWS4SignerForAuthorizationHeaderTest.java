/**
 * 
 */
package org.ligoj.app.plugin.prov.aws.auth;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * @author alocquet
 *
 */
public class AWS4SignerForAuthorizationHeaderTest {

	/**
	 * signer
	 */
	final private AWS4SignerForAuthorizationHeader signer = new AWS4SignerForAuthorizationHeader();

	/**
	 * Test method for
	 * {@link org.ligoj.app.plugin.prov.aws.auth.AWS4SignerForAuthorizationHeader#computeSignature(org.ligoj.app.plugin.prov.aws.auth.AWS4SignatureQuery)}.
	 */
	@Test
	public void testComputeSignature() throws Exception {
		ReflectionTestUtils.setField(signer, "clock",
				Clock.fixed(LocalDateTime.of(2017, 5, 29, 22, 15).toInstant(ZoneOffset.UTC), ZoneOffset.UTC.normalized()));
		final AWS4SignatureQuery signatureQuery = AWS4SignatureQuery.builder().awsAccessKey("awsAccessKey").awsSecretKey("awsSecretKey")
				.regionName("eu-west-1").httpMethod("GET").serviceName("s3").host("host").path("path").build();
		Assert.assertEquals(
				"AWS4-HMAC-SHA256 Credential=awsAccessKey/20170529/eu-west-1/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=fadef8b387b1c3fbb22a99cc9782aefefefdb2ba654ca81ce8a7189f546e0100",
				signer.computeSignature(signatureQuery));
	}

}
