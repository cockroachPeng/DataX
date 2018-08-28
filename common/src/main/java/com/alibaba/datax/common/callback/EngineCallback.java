/**
 * 
 */
package com.alibaba.datax.common.callback;

import com.alibaba.datax.common.util.Configuration;

/**
 * engine callback
 * 
 * @author gangqiangpgq
 */
public interface EngineCallback {

	/**
	 * 读取完成回调
	 * 
	 * @param config			配置信息
	 * @param totalReadRecords	总读取条数
	 * @param totalErrorRecords	总读取失败条数
	 */
	public void finishCallback(Configuration config, long totalReadRecords, long totalErrorRecords);
}
