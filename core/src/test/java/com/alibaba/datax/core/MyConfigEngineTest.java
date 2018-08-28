/**
 * 
 */
package com.alibaba.datax.core;

import java.util.HashMap;
import java.util.Map;

import com.alibaba.datax.callback.NormalCallback;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.core.util.ConfigurationValidate;

/**
 * @author gangqiangpgq
 *
 */
public class MyConfigEngineTest {

	public static void main(String[] args) {

		Map<String, Object> config = new HashMap<String, Object>();

		Configuration configuration = Configuration.from(config);

		configuration.setCallback(new NormalCallback());

		ConfigurationValidate.doValidate(configuration);
		Engine engine = new Engine();
		engine.start(configuration);
	}
}
