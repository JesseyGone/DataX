package com.alibaba.datax.plugin.writer.odpswriter;

import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.plugin.SlavePluginCollector;
import com.alibaba.datax.common.util.StrUtil;
import com.alibaba.datax.plugin.writer.odpswriter.util.OdpsUtil;
import com.alibaba.odps.tunnel.Column;
import com.alibaba.odps.tunnel.RecordSchema;
import com.alibaba.odps.tunnel.Upload;
import com.alibaba.odps.tunnel.io.ProtobufRecordWriter;
import com.alibaba.odps.tunnel.io.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

public class OdpsWriterProxy {
	private static final Logger LOG = LoggerFactory
			.getLogger(OdpsWriterProxy.class);

	private volatile boolean printColumnLess = true;// 是否打印对于源头字段数小于odps目的表的行的日志

	private SlavePluginCollector slavePluginCollector;

	private Upload slaveUpload;

	private RecordSchema schema;

	private ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(
			70 * 1024 * 1024);

	private int max_buffer_length = 64 * 1024 * 1024;

	private ProtobufRecordWriter protobufRecordWriter = null;

	private long blockId;

	private int intervalStep;

	private List<Integer> columnPositions;

	private List<Column.Type> tableOriginalColumnTypeList;

	public OdpsWriterProxy(Upload slaveUpload, long blockId, int intervalStep,
			List<Integer> columnPositions,
			SlavePluginCollector slavePluginCollector) throws IOException {
		this.slaveUpload = slaveUpload;
		this.schema = this.slaveUpload.getSchema();
		this.tableOriginalColumnTypeList = OdpsUtil
				.getTableOriginalColumnTypeList(this.schema);

		this.protobufRecordWriter = new ProtobufRecordWriter(schema,
				byteArrayOutputStream);
		this.blockId = blockId;
		this.intervalStep = intervalStep;
		this.columnPositions = columnPositions;
		this.slavePluginCollector = slavePluginCollector;

	}

	public void writeOneRecord(
			com.alibaba.datax.common.element.Record dataXRecord,
			List<Long> blocks) throws Exception {

		Record record = dataxRecordToOdpsRecord(dataXRecord, schema);

		if (null == record) {
			return;
		}

		protobufRecordWriter.write(record);

		if (byteArrayOutputStream.size() >= max_buffer_length) {
			protobufRecordWriter.close();
			OdpsUtil.slaveWriteOneBlock(this.slaveUpload,
					this.byteArrayOutputStream, blockId);
			LOG.info("write block {} ok.", blockId);

			blocks.add(blockId);
			byteArrayOutputStream.reset();
			protobufRecordWriter = new ProtobufRecordWriter(schema,
					byteArrayOutputStream);

			blockId += this.intervalStep;
		}
	}

	public void writeRemainingRecord(List<Long> blocks) throws Exception {
		// complete protobuf stream, then write to http
		protobufRecordWriter.close();
		if (byteArrayOutputStream.size() != 0) {
			OdpsUtil.slaveWriteOneBlock(this.slaveUpload,
					this.byteArrayOutputStream, blockId);
			LOG.info("write block {} ok.", blockId);

			blocks.add(blockId);
			// reset the buffer for next block
			byteArrayOutputStream.reset();
		}
	}

	public Record dataxRecordToOdpsRecord(
			com.alibaba.datax.common.element.Record dataXRecord,
			RecordSchema schema) throws Exception {
		int sourceColumnCount = dataXRecord.getColumnNumber();
		int destColumnCount = schema.getColumnCount();
		Record odpsRecord = new Record(destColumnCount);

		int userConfiguredColumnNumber = this.columnPositions.size();

		if (sourceColumnCount > userConfiguredColumnNumber) {
			String businessMessage = String
					.format("source columnNumber=[%s] bigger than configured destination columnNumber=[%s].",
							sourceColumnCount, userConfiguredColumnNumber);
			String message = StrUtil.buildOriginalCauseMessage(businessMessage,
					null);
			LOG.error(message);

			throw new DataXException(OdpsWriterErrorCode.COLUMN_NUMBER_ERROR,
					businessMessage);
		} else if (sourceColumnCount < userConfiguredColumnNumber) {
			if (printColumnLess) {
				printColumnLess = false;
				LOG.warn(
						"source columnNumber={} is less than configured destination columnNumber={}, DataX will fill some column with null.",
						dataXRecord.getColumnNumber(),
						userConfiguredColumnNumber);
			}
		}

		int currentIndex = -1;
		int sourceIndex = 0;
		try {
			for (int len = sourceColumnCount; sourceIndex < len; sourceIndex++) {
				currentIndex = columnPositions.get(sourceIndex);
				Column.Type type = this.tableOriginalColumnTypeList
						.get(currentIndex);
				switch (type) {
				case ODPS_STRING:
					odpsRecord.setString(currentIndex,
							dataXRecord.getColumn(sourceIndex).asString());
					break;
				case ODPS_BIGINT:
					odpsRecord.setBigint(currentIndex,
							dataXRecord.getColumn(sourceIndex).asLong());
					break;
				case ODPS_BOOLEAN:
					odpsRecord.setBoolean(currentIndex,
							dataXRecord.getColumn(sourceIndex).asBoolean());
					break;
				case ODPS_DATETIME:
					odpsRecord.setDatetime(currentIndex,
							dataXRecord.getColumn(sourceIndex).asDate());
					break;
				case ODPS_DOUBLE:
					odpsRecord.setDouble(currentIndex,
							dataXRecord.getColumn(sourceIndex).asDouble());
					break;
				default:
					break;
				}
			}

			return odpsRecord;
		} catch (Exception e) {
			String message = String.format(
					"Dirty record detail:sourceIndex=[%s], value=[%s].",
					sourceIndex, dataXRecord.getColumn(sourceColumnCount));
			this.slavePluginCollector.collectDirtyRecord(dataXRecord, e,
					message);

			return null;
		}

	}
}
