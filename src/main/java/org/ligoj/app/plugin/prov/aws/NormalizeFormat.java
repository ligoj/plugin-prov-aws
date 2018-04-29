/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws;

import java.text.FieldPosition;
import java.util.Locale;

/**
 * Normalizer format to upper case and without diacritical marks.
 */
public class NormalizeFormat extends org.ligoj.app.resource.NormalizeFormat {

	/**
	 * SID
	 */
	private static final long serialVersionUID = 1L;

	@Override
	public StringBuffer format(final Object obj, final StringBuffer toAppendTo, final FieldPosition pos) {
		toAppendTo.append(org.ligoj.app.api.Normalizer.normalize(obj.toString()).toLowerCase(Locale.ENGLISH));
		return toAppendTo;
	}

}
