package org.ligoj.app.plugin.prov.aws.auth;

import java.util.HashMap;
import java.util.Map;

import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;

/**
 * Query used to sign AWS V4 API Query
 * 
 * @author alocquet
 */
@Builder
@Getter
public class AWS4SignatureQuery {

	/**
	 * query host
	 */
	@NonNull
	private String host;
	/**
	 * query path
	 */
	@NonNull
	private String path;

	/**
	 * HTTP method such as "POST", "GET",... Default is "POST"
	 */
	@NonNull
	@Builder.Default
	private String method = "POST";
	
	/**
	 * AWS service name : s3, ec2, ...
	 */
	@NonNull
	private String service;
	/**
	 * region name : eu-west-1, ...
	 */
	@NonNull
	private String region;
	/**
	 * AWS Access Key
	 */
	@NonNull
	private String accessKey;
	/**
	 * AWS Secret Key : I won't give my secret key for the javadoc :)
	 */
	@NonNull
	private String secretKey;
	/**
	 * query headers
	 */
	private Map<String, String> headers;
	/**
	 * query parameters
	 */
	private Map<String, String> queryParameters;
	/**
	 * query body
	 */
	private String body;

	/**
	 * Builder class : used to initialize some attributes with default values
	 */
	public static class AWS4SignatureQueryBuilder {
		/**
		 * query headers
		 */
		private Map<String, String> headers = new HashMap<>();
		/**
		 * query parameters
		 */
		private Map<String, String> queryParameters = new HashMap<>();
	}

}
