package com.alibaba.datax.plugin.rdbms.writer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Triple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.datax.common.element.Column;
import com.alibaba.datax.common.element.Column.Type;
import com.alibaba.datax.common.element.LongColumn;
import com.alibaba.datax.common.element.Record;
import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.plugin.RecordReceiver;
import com.alibaba.datax.common.plugin.TaskPluginCollector;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.plugin.rdbms.util.DBUtil;
import com.alibaba.datax.plugin.rdbms.util.DBUtilErrorCode;
import com.alibaba.datax.plugin.rdbms.util.DataBaseType;
import com.alibaba.datax.plugin.rdbms.util.RandomUtil;
import com.alibaba.datax.plugin.rdbms.util.RdbmsException;
import com.alibaba.datax.plugin.rdbms.writer.util.OriginalConfPretreatmentUtil;
import com.alibaba.datax.plugin.rdbms.writer.util.WriterUtil;

public class CommonRdbmsWriter {

	public static class Job {
		private DataBaseType dataBaseType;

		private static final Logger LOG = LoggerFactory.getLogger(Job.class);

		public Job(DataBaseType dataBaseType) {
			this.dataBaseType = dataBaseType;
			OriginalConfPretreatmentUtil.DATABASE_TYPE = this.dataBaseType;
		}

		public void init(Configuration originalConfig) {
			OriginalConfPretreatmentUtil.doPretreatment(originalConfig);

			LOG.debug("After job init(), originalConfig now is:[\n{}\n]", originalConfig.toJSON());
		}

		/*
		 * 目前只支持MySQL Writer跟Oracle Writer;检查PreSQL跟PostSQL语法以及insert，delete权限
		 */
		public void writerPreCheck(Configuration originalConfig, DataBaseType dataBaseType) {
			/* 检查PreSql跟PostSql语句 */
			prePostSqlValid(originalConfig, dataBaseType);
			/* 检查insert 跟delete权限 */
			privilegeValid(originalConfig, dataBaseType);
		}

		public void prePostSqlValid(Configuration originalConfig, DataBaseType dataBaseType) {
			/* 检查PreSql跟PostSql语句 */
			WriterUtil.preCheckPrePareSQL(originalConfig, dataBaseType);
			WriterUtil.preCheckPostSQL(originalConfig, dataBaseType);
		}

		public void privilegeValid(Configuration originalConfig, DataBaseType dataBaseType) {
			/* 检查insert 跟delete权限 */
			String username = originalConfig.getString(Key.USERNAME);
			String password = originalConfig.getString(Key.PASSWORD);
			List<Object> connections = originalConfig.getList(Constant.CONN_MARK, Object.class);

			for (int i = 0, len = connections.size(); i < len; i++) {
				Configuration connConf = Configuration.from(connections.get(i).toString());
				String jdbcUrl = connConf.getString(Key.JDBC_URL);
				List<String> expandedTables = connConf.getList(Key.TABLE, String.class);
				boolean hasInsertPri = DBUtil.checkInsertPrivilege(dataBaseType, jdbcUrl, username, password,
						expandedTables);

				if (!hasInsertPri) {
					throw RdbmsException.asInsertPriException(dataBaseType, originalConfig.getString(Key.USERNAME),
							jdbcUrl);
				}

				if (DBUtil.needCheckDeletePrivilege(originalConfig)) {
					boolean hasDeletePri = DBUtil.checkDeletePrivilege(dataBaseType, jdbcUrl, username, password,
							expandedTables);
					if (!hasDeletePri) {
						throw RdbmsException.asDeletePriException(dataBaseType, originalConfig.getString(Key.USERNAME),
								jdbcUrl);
					}
				}
			}
		}

		// 一般来说，是需要推迟到 task 中进行pre 的执行（单表情况例外）
		public void prepare(Configuration originalConfig) {
			int tableNumber = originalConfig.getInt(Constant.TABLE_NUMBER_MARK);
			if (tableNumber == 1) {
				String username = originalConfig.getString(Key.USERNAME);
				String password = originalConfig.getString(Key.PASSWORD);

				List<Object> conns = originalConfig.getList(Constant.CONN_MARK, Object.class);
				Configuration connConf = Configuration.from(conns.get(0).toString());

				// 这里的 jdbcUrl 已经 append 了合适后缀参数
				String jdbcUrl = connConf.getString(Key.JDBC_URL);
				originalConfig.set(Key.JDBC_URL, jdbcUrl);

				String table = connConf.getList(Key.TABLE, String.class).get(0);
				originalConfig.set(Key.TABLE, table);

				List<String> preSqls = originalConfig.getList(Key.PRE_SQL, String.class);
				List<String> renderedPreSqls = WriterUtil.renderPreOrPostSqls(preSqls, table);

				originalConfig.remove(Constant.CONN_MARK);
				if (null != renderedPreSqls && !renderedPreSqls.isEmpty()) {
					// 说明有 preSql 配置，则此处删除掉
					originalConfig.remove(Key.PRE_SQL);

					Connection conn = DBUtil.getConnection(dataBaseType, jdbcUrl, username, password);
					LOG.info("Begin to execute preSqls:[{}]. context info:{}.", StringUtils.join(renderedPreSqls, ";"),
							jdbcUrl);

					WriterUtil.executeSqls(conn, renderedPreSqls, jdbcUrl, dataBaseType);
					DBUtil.closeDBResources(null, null, conn);
				}
			}

			LOG.debug("After job prepare(), originalConfig now is:[\n{}\n]", originalConfig.toJSON());
		}

		public List<Configuration> split(Configuration originalConfig, int mandatoryNumber) {
			return WriterUtil.doSplit(originalConfig, mandatoryNumber);
		}

		// 一般来说，是需要推迟到 task 中进行post 的执行（单表情况例外）
		public void post(Configuration originalConfig) {
			int tableNumber = originalConfig.getInt(Constant.TABLE_NUMBER_MARK);
			if (tableNumber == 1) {
				String username = originalConfig.getString(Key.USERNAME);
				String password = originalConfig.getString(Key.PASSWORD);

				// 已经由 prepare 进行了appendJDBCSuffix处理
				String jdbcUrl = originalConfig.getString(Key.JDBC_URL);

				String table = originalConfig.getString(Key.TABLE);

				List<String> postSqls = originalConfig.getList(Key.POST_SQL, String.class);
				List<String> renderedPostSqls = WriterUtil.renderPreOrPostSqls(postSqls, table);

				if (null != renderedPostSqls && !renderedPostSqls.isEmpty()) {
					// 说明有 postSql 配置，则此处删除掉
					originalConfig.remove(Key.POST_SQL);

					Connection conn = DBUtil.getConnection(this.dataBaseType, jdbcUrl, username, password);

					LOG.info("Begin to execute postSqls:[{}]. context info:{}.",
							StringUtils.join(renderedPostSqls, ";"), jdbcUrl);
					WriterUtil.executeSqls(conn, renderedPostSqls, jdbcUrl, dataBaseType);
					DBUtil.closeDBResources(null, null, conn);
				}
			}
		}

		public void destroy(Configuration originalConfig) {
		}

	}

	public static class Task {
		protected static final Logger LOG = LoggerFactory.getLogger(Task.class);

		protected DataBaseType dataBaseType;
		private static final String VALUE_HOLDER = "?";

		protected String username;
		protected String password;
		protected String jdbcUrl;
		protected String table;

		protected boolean needDynamicTable; // 是否动态分表
		protected List<String> dynamicJdbcUrls; // 动态分表使用
		protected int dynamicTableCalIndex1; // 动态分表使用
		protected int dynamicTableCalIndex2; // 动态分表使用
		protected int dynamicTableCount1; // 动态分表使用
		protected int dynamicTableCount2; // 动态分表使用
		protected String dynamicTable; // 动态分表使用

		protected List<String> columns;
		protected List<String> preSqls;
		protected List<String> postSqls;
		protected int batchSize;
		protected int batchByteSize;
		protected int columnNumber = 0;
		// 表内分片信息
		protected boolean needSlice = false;
		protected int sliceMin = 0;
		protected int sliceMax = 0;
		protected TaskPluginCollector taskPluginCollector;

		// 作为日志显示信息时，需要附带的通用信息。比如信息所对应的数据库连接等信息，针对哪个表做的操作
		protected String BASIC_MESSAGE;

		protected String insert_or_replace_template;

		protected String writeRecordSql;
		protected String writeMode;
		protected boolean emptyAsNull;
		protected Triple<List<String>, List<Integer>, List<String>> resultSetMetaData;

		public Task(DataBaseType dataBaseType) {
			this.dataBaseType = dataBaseType;
		}

		public void init(Configuration writerSliceConfig) {
			this.username = writerSliceConfig.getString(Key.USERNAME);
			this.password = writerSliceConfig.getString(Key.PASSWORD);
			this.jdbcUrl = writerSliceConfig.getString(Key.JDBC_URL);
			this.table = writerSliceConfig.getString(Key.TABLE);

			// 动态分表使用
			this.needDynamicTable = writerSliceConfig.getBool(Key.NEED_DYNAMIC_TABLE, false);
			this.dynamicJdbcUrls = writerSliceConfig.getList(Key.DYNAMIC_JDBC_URLS, null, String.class);
			this.dynamicTableCalIndex1 = writerSliceConfig.getInt(Key.DYNAMIC_TABLE_CAL_INDEX1, -1);
			this.dynamicTableCalIndex2 = writerSliceConfig.getInt(Key.DYNAMIC_TABLE_CAL_INDEX2, -1);
			this.dynamicTableCount1 = writerSliceConfig.getInt(Key.DYNAMIC_TABLE_COUNT1, -1);
			this.dynamicTableCount2 = writerSliceConfig.getInt(Key.DYNAMIC_TABLE_COUNT2, -1);
			this.dynamicTable = writerSliceConfig.getString(Key.DYNAMIC_TABLE, StringUtils.EMPTY);

			this.columns = writerSliceConfig.getList(Key.COLUMN, String.class);
			this.columnNumber = this.columns.size();

			this.preSqls = writerSliceConfig.getList(Key.PRE_SQL, String.class);
			this.postSqls = writerSliceConfig.getList(Key.POST_SQL, String.class);
			this.batchSize = writerSliceConfig.getInt(Key.BATCH_SIZE, Constant.DEFAULT_BATCH_SIZE);
			this.batchByteSize = writerSliceConfig.getInt(Key.BATCH_BYTE_SIZE, Constant.DEFAULT_BATCH_BYTE_SIZE);

			this.needSlice = writerSliceConfig.getBool(Key.NEED_SLICE, false); // 默认不需要表内分片
			if (needSlice) {
				// 需要分片时，获取分片信息
				this.sliceMin = writerSliceConfig.getInt(Key.SLICE_MIN); // 最小分片值
				this.sliceMax = writerSliceConfig.getInt(Key.SLICE_MAX); // 最大分片值
			}

			writeMode = writerSliceConfig.getString(Key.WRITE_MODE, "INSERT");
			emptyAsNull = writerSliceConfig.getBool(Key.EMPTY_AS_NULL, true);
			insert_or_replace_template = writerSliceConfig.getString(Constant.INSERT_OR_REPLACE_TEMPLATE_MARK);
			this.writeRecordSql = String.format(insert_or_replace_template, this.table);

			BASIC_MESSAGE = String.format("jdbcUrl:[%s], table:[%s]", this.jdbcUrl, this.table);
		}

		public void prepare(Configuration writerSliceConfig) {

			Connection connection = DBUtil.getConnection(this.dataBaseType, this.jdbcUrl, username, password);

			DBUtil.dealWithSessionConfig(connection, writerSliceConfig, this.dataBaseType, BASIC_MESSAGE);

			int tableNumber = writerSliceConfig.getInt(Constant.TABLE_NUMBER_MARK);
			if (tableNumber != 1) {
				LOG.info("Begin to execute preSqls:[{}]. context info:{}.", StringUtils.join(this.preSqls, ";"),
						BASIC_MESSAGE);
				WriterUtil.executeSqls(connection, this.preSqls, BASIC_MESSAGE, dataBaseType);
			}

			DBUtil.closeDBResources(null, null, connection);
		}

		public void startWriteWithConnection(RecordReceiver recordReceiver, TaskPluginCollector taskPluginCollector,
				Connection connection, Configuration writerSliceConfig) {
			this.taskPluginCollector = taskPluginCollector;

			// 用于写入数据的时候的类型根据目的表字段类型转换
			this.resultSetMetaData = DBUtil.getColumnMetaData(connection, this.table,
					StringUtils.join(this.columns, ","));
			// 写数据库的SQL语句
			calcWriteRecordSql();

			List<Record> writeBuffer = new ArrayList<Record>(this.batchSize);
			int bufferBytes = 0;
			int curBatchSize = 0;
			Map<String, Map<String, List<Record>>> dynamicTableRecods = new HashMap<String, Map<String, List<Record>>>(); // 动态分表使用

			try {
				Record record;
				while ((record = recordReceiver.getFromReader()) != null) {

					if (record.getColumnNumber() != this.columnNumber) {
						// 源头读取字段列数与目的表字段写入列数不相等，直接报错
						throw DataXException.asDataXException(DBUtilErrorCode.CONF_ERROR,
								String.format("列配置信息有错误. 因为您配置的任务中，源头读取字段数:%s 与 目的表要写入的字段数:%s 不相等. 请检查您的配置并作出修改.",
										record.getColumnNumber(), this.columnNumber));
					}

					if (needDynamicTable) {
						// 计算分库分表
						Column table_cal_col_1 = record.getColumn(dynamicTableCalIndex1);
						if (!Type.INT.equals(table_cal_col_1.getType())
								&& !Type.LONG.equals(table_cal_col_1.getType())) {
							throw new IllegalArgumentException("动态分表时，分库分表字段只支持int和long类型");
						}

						Long table_cal_1 = table_cal_col_1.asLong();
						long tableIndex1 = cal_table_index1(table_cal_1, dynamicTableCount1);
						long dbIndex = cal_db_index(tableIndex1, dynamicJdbcUrls.size(), dynamicTableCount1);
						String dynamicTableTarget = dynamicTable + "_" + tableIndex1; // 计算分表

						if (dynamicTableCalIndex2 != -1) { // 需要2维分库分表
							Column table_cal_col_2 = record.getColumn(dynamicTableCalIndex2);
							if (!Type.INT.equals(table_cal_col_1.getType())
									&& !Type.LONG.equals(table_cal_col_1.getType())) {
								throw new IllegalArgumentException("动态分表时，分库分表字段只支持int和long类型");
							}
							Long table_cal_2 = table_cal_col_2.asLong();
							long tableIndex2 = cal_table_index2(table_cal_2, dynamicTableCount2);

							dynamicTableTarget = dynamicTableTarget + "_" + tableIndex2; // 计算分表
						}
						String dynamicJdbcUrl = dynamicJdbcUrls.get((int) dbIndex); // 计算分库

						Map<String, List<Record>> tableRecordsMap = dynamicTableRecods.get(dynamicJdbcUrl);
						if (tableRecordsMap == null) {
							tableRecordsMap = new HashMap<String, List<Record>>();
							dynamicTableRecods.put(dynamicJdbcUrl, tableRecordsMap);
						}

						List<Record> tableRecords = tableRecordsMap.get(dynamicTableTarget);

						if (tableRecords == null) {
							tableRecords = new LinkedList<Record>();
							tableRecordsMap.put(dynamicTableTarget, tableRecords);
						}

						tableRecords.add(record);
					} else {
						writeBuffer.add(record);
					}

					curBatchSize++;
					bufferBytes += record.getMemorySize();

					if (curBatchSize >= batchSize || bufferBytes >= batchByteSize) {

						batchInsert(connection, writeBuffer, dynamicTableRecods);

						bufferBytes = 0;
						curBatchSize = 0;
					}
				}
				if (curBatchSize > 0) {

					batchInsert(connection, writeBuffer, dynamicTableRecods);

					bufferBytes = 0;
					curBatchSize = 0;
				}
			} catch (Exception e) {
				throw DataXException.asDataXException(DBUtilErrorCode.WRITE_DATA_ERROR, e);
			} finally {
				dynamicTableRecods.clear();
				writeBuffer.clear();
				bufferBytes = 0;
				curBatchSize = 0;
				DBUtil.closeDBResources(null, null, connection);
			}
		}

		// 4
		// 0 ... 3
		// 4 ... 7
		// 8 ... 11
		// 12 ... 15
		private long cal_db_index(long table_index1, long db_count, long table_count1) {
			return table_index1 / (table_count1 / db_count);
		}

		private long cal_table_index1(long table1, long tableCount1) {
			return table1 % tableCount1;
		}

		private long cal_table_index2(long table2, long tableCount2) {
			return (table2 >> 32) % tableCount2;
		}

		private void batchInsert(Connection connection, List<Record> writeBuffer,
				Map<String, Map<String, List<Record>>> dynamicTableRecods) throws SQLException {

			if (needDynamicTable) {

				Set<Entry<String, Map<String, List<Record>>>> dynamicTableRecodsEntrys = dynamicTableRecods.entrySet();

				for (Entry<String, Map<String, List<Record>>> dynamicTableRecodsEntry : dynamicTableRecodsEntrys) {
					String dynamicJdbcUrl = dynamicTableRecodsEntry.getKey();
					Map<String, List<Record>> dynamicTablesMap = dynamicTableRecods.get(dynamicJdbcUrl);

					Set<Entry<String, List<Record>>> dynamicTablesEntry = dynamicTablesMap.entrySet();

					for (Entry<String, List<Record>> entry : dynamicTablesEntry) {

						String dynamicWriteRecordSql = String.format(insert_or_replace_template, entry.getKey());
						if (!VALUE_HOLDER.equals(calcValueHolder(""))) {
							List<String> valueHolders = new ArrayList<String>(columnNumber);
							for (int i = 0; i < columns.size(); i++) {
								String type = resultSetMetaData.getRight().get(i);
								valueHolders.add(calcValueHolder(type));
							}
							insert_or_replace_template = WriterUtil.getWriteTemplate(columns, valueHolders, writeMode);
							dynamicWriteRecordSql = String.format(insert_or_replace_template, entry.getKey());
						} else {
							dynamicWriteRecordSql = String.format(insert_or_replace_template, entry.getKey());
						}

						// 创建动态连接
						Connection dynamicCon = DBUtil.getConnection(dataBaseType, dynamicJdbcUrl, username, password);
						try {
							doBatchInsert(dynamicCon, entry.getValue(), dynamicWriteRecordSql);
						} catch (Exception e) {
							throw DataXException.asDataXException(DBUtilErrorCode.WRITE_DATA_ERROR, e);
						} finally {
							// 手动关闭
							dynamicCon.close();
						}
					}
				}

				dynamicTableRecods.clear();
			} else {
				doBatchInsert(connection, writeBuffer);
				writeBuffer.clear();
			}
		}

		// TODO 改用连接池，确保每次获取的连接都是可用的（注意：连接可能需要每次都初始化其 session）
		public void startWrite(RecordReceiver recordReceiver, Configuration writerSliceConfig,
				TaskPluginCollector taskPluginCollector) {
			Connection connection = DBUtil.getConnection(this.dataBaseType, this.jdbcUrl, username, password);
			DBUtil.dealWithSessionConfig(connection, writerSliceConfig, this.dataBaseType, BASIC_MESSAGE);

			startWriteWithConnection(recordReceiver, taskPluginCollector, connection, writerSliceConfig);
		}

		public void post(Configuration writerSliceConfig) {
			int tableNumber = writerSliceConfig.getInt(Constant.TABLE_NUMBER_MARK);

			boolean hasPostSql = (this.postSqls != null && this.postSqls.size() > 0);
			if (tableNumber == 1 || !hasPostSql) {
				return;
			}

			Connection connection = DBUtil.getConnection(this.dataBaseType, this.jdbcUrl, username, password);

			LOG.info("Begin to execute postSqls:[{}]. context info:{}.", StringUtils.join(this.postSqls, ";"),
					BASIC_MESSAGE);
			WriterUtil.executeSqls(connection, this.postSqls, BASIC_MESSAGE, dataBaseType);
			DBUtil.closeDBResources(null, null, connection);
		}

		public void destroy(Configuration writerSliceConfig) {
		}

		protected void doBatchInsert(Connection connection, List<Record> buffer) throws SQLException {
			doBatchInsert(connection, buffer, this.writeRecordSql);
		}

		protected void doBatchInsert(Connection connection, List<Record> buffer, String writeRecordSql)
				throws SQLException {
			PreparedStatement preparedStatement = null;
			try {
				connection.setAutoCommit(false);
				preparedStatement = connection.prepareStatement(writeRecordSql);

				for (Record record : buffer) {
					preparedStatement = fillPreparedStatement(preparedStatement, record);
					preparedStatement.addBatch();
				}
				preparedStatement.executeBatch();
				connection.commit();
			} catch (SQLException e) {
				LOG.warn("回滚此次写入, 采用每次写入一行方式提交. 因为:" + e.getMessage());
				connection.rollback();
				doOneInsert(connection, buffer, writeRecordSql);
			} catch (Exception e) {
				throw DataXException.asDataXException(DBUtilErrorCode.WRITE_DATA_ERROR, e);
			} finally {
				DBUtil.closeDBResources(preparedStatement, null);
			}
		}

		protected void doOneInsert(Connection connection, List<Record> buffer, String writeRecordSql) {
			PreparedStatement preparedStatement = null;
			try {
				connection.setAutoCommit(true);
				preparedStatement = connection.prepareStatement(writeRecordSql);

				for (Record record : buffer) {
					try {
						preparedStatement = fillPreparedStatement(preparedStatement, record);
						preparedStatement.execute();
					} catch (SQLException e) {
						LOG.debug(e.toString());

						this.taskPluginCollector.collectDirtyRecord(record, e);
					} finally {
						// 最后不要忘了关闭 preparedStatement
						preparedStatement.clearParameters();
					}
				}
			} catch (Exception e) {
				throw DataXException.asDataXException(DBUtilErrorCode.WRITE_DATA_ERROR, e);
			} finally {
				DBUtil.closeDBResources(preparedStatement, null);
			}
		}

		// 直接使用了两个类变量：columnNumber,resultSetMetaData
		protected PreparedStatement fillPreparedStatement(PreparedStatement preparedStatement, Record record)
				throws SQLException {
			for (int i = 0; i < this.columnNumber; i++) {
				int columnSqltype = this.resultSetMetaData.getMiddle().get(i);
				preparedStatement = fillPreparedStatementColumnType(preparedStatement, i, columnSqltype,
						record.getColumn(i));
			}

			// add by cockroachPeng 支持分片
			if (needSlice) {
				int sliceId = RandomUtil.getLongRandom(this.sliceMin, this.sliceMax);
				LongColumn sliceColumn = new LongColumn(sliceId); // 随机分片
				preparedStatement = fillPreparedStatementColumnType(preparedStatement, this.columnNumber + 1,
						Types.BIGINT, sliceColumn); // 最后一列作为随机列
			}

			return preparedStatement;
		}

		protected PreparedStatement fillPreparedStatementColumnType(PreparedStatement preparedStatement,
				int columnIndex, int columnSqltype, Column column) throws SQLException {
			java.util.Date utilDate;
			switch (columnSqltype) {
			case Types.CHAR:
			case Types.NCHAR:
			case Types.CLOB:
			case Types.NCLOB:
			case Types.VARCHAR:
			case Types.LONGVARCHAR:
			case Types.NVARCHAR:
			case Types.LONGNVARCHAR:
				preparedStatement.setString(columnIndex + 1, column.asString());
				break;

			case Types.SMALLINT:
			case Types.INTEGER:
			case Types.BIGINT:
			case Types.NUMERIC:
			case Types.DECIMAL:
			case Types.FLOAT:
			case Types.REAL:
			case Types.DOUBLE:
				String strValue = column.asString();
				if (emptyAsNull && "".equals(strValue)) {
					preparedStatement.setString(columnIndex + 1, null);
				} else {
					preparedStatement.setString(columnIndex + 1, strValue);
				}
				break;

			// tinyint is a little special in some database like mysql
			// {boolean->tinyint(1)}
			case Types.TINYINT:
				Long longValue = column.asLong();
				if (null == longValue) {
					preparedStatement.setString(columnIndex + 1, null);
				} else {
					preparedStatement.setString(columnIndex + 1, longValue.toString());
				}
				break;

			// for mysql bug, see http://bugs.mysql.com/bug.php?id=35115
			case Types.DATE:
				if (this.resultSetMetaData.getRight().get(columnIndex).equalsIgnoreCase("year")) {
					if (column.asBigInteger() == null) {
						preparedStatement.setString(columnIndex + 1, null);
					} else {
						preparedStatement.setInt(columnIndex + 1, column.asBigInteger().intValue());
					}
				} else {
					java.sql.Date sqlDate = null;
					try {
						utilDate = column.asDate();
					} catch (DataXException e) {
						throw new SQLException(String.format("Date 类型转换错误：[%s]", column));
					}

					if (null != utilDate) {
						sqlDate = new java.sql.Date(utilDate.getTime());
					}
					preparedStatement.setDate(columnIndex + 1, sqlDate);
				}
				break;

			case Types.TIME:
				java.sql.Time sqlTime = null;
				try {
					utilDate = column.asDate();
				} catch (DataXException e) {
					throw new SQLException(String.format("TIME 类型转换错误：[%s]", column));
				}

				if (null != utilDate) {
					sqlTime = new java.sql.Time(utilDate.getTime());
				}
				preparedStatement.setTime(columnIndex + 1, sqlTime);
				break;

			case Types.TIMESTAMP:
				java.sql.Timestamp sqlTimestamp = null;
				try {
					utilDate = column.asDate();
				} catch (DataXException e) {
					throw new SQLException(String.format("TIMESTAMP 类型转换错误：[%s]", column));
				}

				if (null != utilDate) {
					sqlTimestamp = new java.sql.Timestamp(utilDate.getTime());
				}
				preparedStatement.setTimestamp(columnIndex + 1, sqlTimestamp);
				break;

			case Types.BINARY:
			case Types.VARBINARY:
			case Types.BLOB:
			case Types.LONGVARBINARY:
				preparedStatement.setBytes(columnIndex + 1, column.asBytes());
				break;

			case Types.BOOLEAN:
				preparedStatement.setString(columnIndex + 1, column.asString());
				break;

			// warn: bit(1) -> Types.BIT 可使用setBoolean
			// warn: bit(>1) -> Types.VARBINARY 可使用setBytes
			case Types.BIT:
				if (this.dataBaseType == DataBaseType.MySql) {
					preparedStatement.setBoolean(columnIndex + 1, column.asBoolean());
				} else {
					preparedStatement.setString(columnIndex + 1, column.asString());
				}
				break;
			default:
				throw DataXException.asDataXException(DBUtilErrorCode.UNSUPPORTED_TYPE, String.format(
						"您的配置文件中的列配置信息有误. 因为DataX 不支持数据库写入这种字段类型. 字段名:[%s], 字段类型:[%d], 字段Java类型:[%s]. 请修改表中该字段的类型或者不同步该字段.",
						this.resultSetMetaData.getLeft().get(columnIndex),
						this.resultSetMetaData.getMiddle().get(columnIndex),
						this.resultSetMetaData.getRight().get(columnIndex)));
			}
			return preparedStatement;
		}

		private void calcWriteRecordSql() {
			if (!VALUE_HOLDER.equals(calcValueHolder(""))) {
				List<String> valueHolders = new ArrayList<String>(columnNumber);
				for (int i = 0; i < columns.size(); i++) {
					String type = resultSetMetaData.getRight().get(i);
					valueHolders.add(calcValueHolder(type));
				}
				insert_or_replace_template = WriterUtil.getWriteTemplate(columns, valueHolders, writeMode);
				writeRecordSql = String.format(insert_or_replace_template, this.table);
			}
		}

		protected String calcValueHolder(String columnType) {
			return VALUE_HOLDER;
		}
	}
}
