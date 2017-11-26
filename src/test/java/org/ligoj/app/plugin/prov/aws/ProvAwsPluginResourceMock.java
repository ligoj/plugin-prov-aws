package org.ligoj.app.plugin.prov.aws;

/**
 * Configuration class used to mock AWS calls
 */
public class ProvAwsPluginResourceMock extends ProvAwsPluginResource {
	@Override
	public boolean validateAccess(int subscription) {
		return true;
	}

}
