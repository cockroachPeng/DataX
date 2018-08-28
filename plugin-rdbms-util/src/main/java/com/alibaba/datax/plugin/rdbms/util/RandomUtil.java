/**
 * 
 */
package com.alibaba.datax.plugin.rdbms.util;

import java.util.Random;

/**
 * @author gangqiangpgq
 */
public abstract class RandomUtil {

	private static Random random = new Random();

	public static int getLongRandom(int min, int max) {

		if (min > max) {
			throw new IllegalArgumentException("参数异常，min：" + min + "，必须小于max：" + max);
		} else if (min == max) {
			return min;
		}

		int intervalLong = max - min + 1; // 间隔值
		int randomLong = random.nextInt(intervalLong) + min;

		return randomLong;
	}
}
