/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.prov.aws;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.prov.QuoteVo;
import org.ligoj.app.plugin.prov.model.ProvQuoteInstance;

import lombok.Getter;
import lombok.Setter;

/**
 * A context holder.
 */
@Getter
@Setter
public class Context {

	private Subscription subscription;
	private QuoteVo quote;
	private String location;
	private Map<InstanceMode, List<ProvQuoteInstance>> modes;
	private List<ProvQuoteInstance> instances;
	private final Map<String, String> context = new HashMap<>();

	/**
	 * Add a value to the context.
	 * 
	 * @param key
	 *            Key of the context.
	 * @param value
	 *            Value of the context.
	 * @return This object.
	 */
	public Context add(final String key, final String value) {
		context.put(key, value);
		return this;
	}

	public String get(final String key) {
		return context.get(key);
	}
}
