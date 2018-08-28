/**
 * 
 */
package com.alibaba.datax.callback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.datax.common.callback.EngineCallback;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.core.job.JobContainer;

/**
 * @author gangqiangpgq
 *
 */
public class NormalCallback implements EngineCallback {

	private static final Logger LOG = LoggerFactory.getLogger(JobContainer.class);

	/* (non-Javadoc)
	 * @see com.alibaba.datax.common.callback.EngineCallback#finishCallback(com.alibaba.datax.common.util.Configuration, long, long)
	 */
	@Override
	public void finishCallback(Configuration config, long totalReadRecords, long totalErrorRecords) {
		// TODO Auto-generated method stub
		LOG.info("数据同步完成，执行回调;总读取数量："+totalReadRecords+"，读取失败数量："+totalErrorRecords);
	}
}
