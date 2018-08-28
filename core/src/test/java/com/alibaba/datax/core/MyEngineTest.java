/**
 * 
 */
package com.alibaba.datax.core;

import java.io.File;
import java.io.FileWriter;

import org.junit.Before;
import org.junit.Test;

import com.alibaba.datax.callback.NormalCallback;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.core.scaffold.base.MyCaseInitializer;
import com.alibaba.datax.core.util.ConfigParser;
import com.alibaba.datax.core.util.ExceptionTracker;
import com.alibaba.datax.core.util.container.LoadUtil;

/**
 * @author gangqiangpgq
 *
 */
public class MyEngineTest extends MyCaseInitializer {
	private Configuration configuration;

	@Before
	public void setUp() {
		String path = MyEngineTest.class.getClassLoader().getResource(".").getFile();

		this.configuration = ConfigParser.parse(path + File.separator + "job" + File.separator + "amg_brand_buff_cat_spu_ref_0.json");
		configuration.setCallback(new NormalCallback());
		LoadUtil.bind(this.configuration);
	}

	@Test
	public void test_entry() throws Throwable {
		String jobConfig = this.configuration.toString();

		String jobFile = "./amg_brand_buff_cat_spu_ref_0.json";
		FileWriter writer = new FileWriter(jobFile);
		writer.write(jobConfig);
		writer.flush();
		writer.close();
		String[] args = { "-job", jobFile, "jobid", "423432", "-mode", "standalone" };

		Engine.entry(args);
	}

	public void testNN() {
		try {
			throwEE();
		} catch (Exception e) {
			String tarce = ExceptionTracker.trace(e);
			if (e instanceof NullPointerException) {
				System.out.println(tarce);
			}
		}
	}

	public static void throwEE() {
		String aa = null;
		aa.toString();
		// throw new NullPointerException();
	}

}
