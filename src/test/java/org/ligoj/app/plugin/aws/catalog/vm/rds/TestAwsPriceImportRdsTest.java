/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.aws.catalog.vm.rds;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Test class of {@link AwsPriceImportRds}: RDS storage volume type resolution.
 */
class TestAwsPriceImportRdsTest {

	@Test
	void toVolumeCode() {
		Assertions.assertEquals("gp", AwsPriceImportRds.toVolumeCode("General Purpose"));
		Assertions.assertEquals("gp3", AwsPriceImportRds.toVolumeCode("General Purpose-GP3"));
		Assertions.assertEquals("io", AwsPriceImportRds.toVolumeCode("Provisioned IOPS"));
		Assertions.assertEquals("io2", AwsPriceImportRds.toVolumeCode("Provisioned IOPS-IO2"));
		Assertions.assertEquals("magnetic", AwsPriceImportRds.toVolumeCode("Magnetic"));

		// Legacy labels still used by some engines like Oracle and SQL Server
		Assertions.assertEquals("gp", AwsPriceImportRds.toVolumeCode("General Purpose (SSD)"));
		Assertions.assertEquals("io", AwsPriceImportRds.toVolumeCode("Provisioned IOPS (SSD)"));

		// Unsupported volume type
		Assertions.assertNull(AwsPriceImportRds.toVolumeCode("Some Future Volume"));
	}
}
