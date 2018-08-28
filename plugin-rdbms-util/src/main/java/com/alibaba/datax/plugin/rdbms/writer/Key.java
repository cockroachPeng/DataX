package com.alibaba.datax.plugin.rdbms.writer;

public final class Key {
	public final static String JDBC_URL = "jdbcUrl";

	public final static String USERNAME = "username";

	public final static String PASSWORD = "password";

	public final static String TABLE = "table";

	public final static String COLUMN = "column";

	public final static String NEED_DYNAMIC_TABLE = "needDynamicTable"; // 是否动态分表
	public final static String DYNAMIC_JDBC_URLS = "dynamicJdbcUrls"; // 动态分表使用
	public final static String DYNAMIC_TABLE_CAL_INDEX1 = "dynamicTableCalIndex1"; // 动态分表使用
	public final static String DYNAMIC_TABLE_CAL_INDEX2 = "dynamicTableCalIndex2"; // 动态分表使用
	public final static String DYNAMIC_TABLE_COUNT1 = "dynamicTableCount1"; // 动态分表使用
	public final static String DYNAMIC_TABLE_COUNT2 = "dynamicTableCount2"; // 动态分表使用
	public final static String DYNAMIC_TABLE = "dynamicTable"; // 动态分表使用

	// 可选值为：insert,replace，默认为 insert （mysql 支持，oracle 没用 replace 机制，只能
	// insert,oracle 可以不暴露这个参数）
	public final static String WRITE_MODE = "writeMode";

	public final static String PRE_SQL = "preSql";

	public final static String POST_SQL = "postSql";

	public final static String TDDL_APP_NAME = "appName";

	// 默认值：256
	public final static String BATCH_SIZE = "batchSize";

	// 默认值：32m
	public final static String BATCH_BYTE_SIZE = "batchByteSize";

	public final static String EMPTY_AS_NULL = "emptyAsNull";

	public final static String DB_NAME_PATTERN = "dbNamePattern";

	public final static String DB_RULE = "dbRule";

	public final static String TABLE_NAME_PATTERN = "tableNamePattern";

	public final static String TABLE_RULE = "tableRule";

	public final static String DRYRUN = "dryRun";

	// add by cockroachPeng 支持单表数据新增分片
	public final static String SLICE_MIN = "sliceMin"; // 最小分片值，包含

	public final static String SLICE_MAX = "sliceMax"; // 最大分片值，包含

	public final static String NEED_SLICE = "needSlice"; // 是否需要分片

	public final static String SLICE_COLUMN_NAME = "sliceColumnName"; // 分片名
}