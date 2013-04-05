package com.impossibl.postgres.jdbc;

import static com.impossibl.postgres.jdbc.ErrorUtils.chainWarnings;
import static com.impossibl.postgres.jdbc.Exceptions.NOT_ALLOWED_ON_PREP_STMT;
import static com.impossibl.postgres.jdbc.Exceptions.NOT_IMPLEMENTED;
import static com.impossibl.postgres.jdbc.Exceptions.PARAMETER_INDEX_OUT_OF_BOUNDS;
import static com.impossibl.postgres.jdbc.SQLTypeUtils.coerce;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.BatchUpdateException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import com.impossibl.postgres.protocol.BindExecCommand;
import com.impossibl.postgres.protocol.QueryCommand;
import com.impossibl.postgres.protocol.ResultField;
import com.impossibl.postgres.types.Type;



class PGPreparedStatement extends PGStatement implements PreparedStatement {

	
	
	List<Type> parameterTypes;
	List<Object> parameterValues;
	List<List<Object>> batchParameterValues;
	boolean wantsGeneratedKeys;
	
	
	
	PGPreparedStatement(PGConnection connection, int type, int concurrency, int holdability, String name, List<Type> parameterTypes, List<ResultField> resultFields) {
		super(connection, type, concurrency, holdability, name, resultFields);
		this.parameterTypes = parameterTypes;
		this.parameterValues = Arrays.asList(new Object[parameterTypes.size()]);
	}

	public boolean getWantsGeneratedKeys() {
		return wantsGeneratedKeys;
	}

	public void setWantsGeneratedKeys(boolean wantsGeneratedKeys) {
		this.wantsGeneratedKeys = wantsGeneratedKeys;
	}

	/**
	 * Ensure the given parameter index is valid for this statement
	 * 
	 * @throws SQLException
	 * 					If the parameter index is out of bounds
	 */
	void checkParameterIndex(int idx) throws SQLException {
		
		if(idx < 1 || idx > parameterValues.size())
			throw PARAMETER_INDEX_OUT_OF_BOUNDS;
	}
	
	void set(int parameterIdx, Object val) throws SQLException {
		
		set(parameterIdx, val, TimeZone.getDefault());
	}
	
	void set(int parameterIdx, Object val, TimeZone zone) throws SQLException {
		checkClosed();
		checkParameterIndex(parameterIdx);
		
		parameterIdx -= 1;
		
		Type paramType = parameterTypes.get(parameterIdx);
		
		if(val != null) {
			val = coerce(val, paramType, Collections.<String,Class<?>>emptyMap(), zone, connection);
		}
		
		parameterValues.set(parameterIdx, val);
	}

	void internalClose() throws SQLException {

		super.internalClose();
		
		parameterTypes = null;
		parameterValues = null;
	}

	@Override
	public boolean execute() throws SQLException {
		
		boolean res = super.executeStatement(name, parameterTypes, parameterValues);
		
		if(wantsGeneratedKeys) {
			generatedKeysResultSet = getResultSet();
		}

		return res;
	}

	@Override
	public PGResultSet executeQuery() throws SQLException {

		execute();

		return getResultSet();
	}

	@Override
	public int executeUpdate() throws SQLException {

		execute();

		return getUpdateCount();
	}

	@Override
	public void addBatch() throws SQLException {
		checkClosed();
		
		if(batchParameterValues == null) {
			batchParameterValues = new ArrayList<>();
		}
		
		List<Object> currentParameterValues = parameterValues;
		parameterValues = asList(new Object[parameterValues.size()]);
		
		batchParameterValues.add(currentParameterValues);
	}

	@Override
	public void clearBatch() throws SQLException {
		checkClosed();
		
		batchParameterValues = null;
	}

	@Override
	public int[] executeBatch() throws SQLException {
		checkClosed();
		
		if(batchParameterValues == null || batchParameterValues.isEmpty()) {
			return new int[0];
		}
		
		int[] counts = new int[batchParameterValues.size()];
		Arrays.fill(counts, SUCCESS_NO_INFO);
		
		List<Object[]> generatedKeys = new ArrayList<>();
		
		BindExecCommand command = connection.getProtocol().createBindExec(null, name, parameterTypes, Collections.emptyList(), resultFields, Object[].class);

		for(int c=0, sz=batchParameterValues.size(); c < sz; ++c) {
			
			List<Object> parameterValues = batchParameterValues.get(c);
			
			command.setParameterValues(parameterValues);
			
			SQLWarning warnings = connection.execute(command, true);
			
			warningChain = chainWarnings(warningChain, warnings);
			
			List<QueryCommand.ResultBatch> resultBatches = command.getResultBatches();
			if(resultBatches.size() != 1) {
				throw new BatchUpdateException(counts);
			}
		
			QueryCommand.ResultBatch resultBatch = resultBatches.get(0);
			if(resultBatch.rowsAffected == null) {
				throw new BatchUpdateException(counts);
			}
			
			if(wantsGeneratedKeys) {
				generatedKeys.add((Object[])resultBatch.results.get(0));
			}
			
			counts[c] = (int)(long)resultBatch.rowsAffected;
		}
		
		generatedKeysResultSet = createResultSet(resultFields, generatedKeys);

		return counts;
	}

	@Override
	public void clearParameters() throws SQLException {
		checkClosed();

		for (int c = 0; c < parameterValues.size(); ++c) {
		
			parameterValues.set(c, null);
		
		}
		
	}

	@Override
	public ParameterMetaData getParameterMetaData() throws SQLException {
		checkClosed();
		
		return new PGParameterMetaData(parameterTypes, connection.getTypeMap());
	}

	@Override
	public ResultSetMetaData getMetaData() throws SQLException {
		checkClosed();
		
		return new PGResultSetMetaData(connection, resultFields, connection.getTypeMap());
	}

	@Override
	public void setNull(int parameterIndex, int sqlType) throws SQLException {
		set(parameterIndex, null);
	}

	@Override
	public void setBoolean(int parameterIndex, boolean x) throws SQLException {
		set(parameterIndex, x);
	}

	@Override
	public void setByte(int parameterIndex, byte x) throws SQLException {
		set(parameterIndex, x);
	}

	@Override
	public void setShort(int parameterIndex, short x) throws SQLException {
		set(parameterIndex, x);
	}

	@Override
	public void setInt(int parameterIndex, int x) throws SQLException {
		set(parameterIndex, x);
	}

	@Override
	public void setLong(int parameterIndex, long x) throws SQLException {		
		set(parameterIndex, x);
	}

	@Override
	public void setFloat(int parameterIndex, float x) throws SQLException {		
		set(parameterIndex, x);
	}

	@Override
	public void setDouble(int parameterIndex, double x) throws SQLException {		
		set(parameterIndex, x);
	}

	@Override
	public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {		
		set(parameterIndex, x);
	}

	@Override
	public void setString(int parameterIndex, String x) throws SQLException {		
		set(parameterIndex, x);
	}

	@Override
	public void setBytes(int parameterIndex, byte[] x) throws SQLException {		
		set(parameterIndex, x);
	}

	@Override
	public void setDate(int parameterIndex, Date x) throws SQLException {		
		setDate(parameterIndex, x, Calendar.getInstance());
	}

	@Override
	public void setTime(int parameterIndex, Time x) throws SQLException {		
		setTime(parameterIndex, x, Calendar.getInstance());
	}

	@Override
	public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {		
		setTimestamp(parameterIndex, x, Calendar.getInstance());
	}

	@Override
	public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
		
		TimeZone zone = cal.getTimeZone();
		
		set(parameterIndex, x, zone);
	}

	@Override
	public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {

		
		TimeZone zone = cal.getTimeZone();
		
		set(parameterIndex, x, zone);
	}

	@Override
	public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
		checkClosed();
		checkParameterIndex(parameterIndex);

		TimeZone zone = cal.getTimeZone();
		
		set(parameterIndex, x, zone);
	}

	@Override
	public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {

		_setBinaryStream(parameterIndex, x, (long) -1);
	}

	@Override
	public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
		
		if(length < 0) {
			throw new SQLException("Invalid length");
		}
		
		if(x != null) {

			x = ByteStreams.limit(x, length);
		}
		else if(length != 0) {
			throw new SQLException("Invalid length");
		}		

		_setBinaryStream(parameterIndex, x, length);
	}

	@Override
	public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
		
		if(length < 0) {
			throw new SQLException("Invalid length");
		}
		
		if(x != null) {

			x = ByteStreams.limit(x, length);
		}
		else if(length != 0) {
			throw new SQLException("Invalid length");
		}

		_setBinaryStream(parameterIndex, x, length);
	}
	
	public void _setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
		
		if(x == null) {
			
			set(parameterIndex, null);
		}
		else {
			
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try {
				
				long read = ByteStreams.copy(x, out);
				
				if(length != -1 && read != length) {
					throw new SQLException("Not enough data in stream");
				}
				
			}
			catch(IOException e) {
				throw new SQLException(e);
			}
	
			set(parameterIndex, out.toByteArray());
			
		}
		
	}

	@Override
	public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {

		InputStreamReader reader = new InputStreamReader(x, UTF_8);
		
		setCharacterStream(parameterIndex, reader, length);
	}

	@Override
	public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
		setAsciiStream(parameterIndex, x, (long) -1);
	}

	@Override
	public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
		setAsciiStream(parameterIndex, x, (long) length);
	}

	@Override
	public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {

		InputStreamReader reader = new InputStreamReader(x, US_ASCII);
		
		setCharacterStream(parameterIndex, reader, length);
	}

	@Override
	public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
		setCharacterStream(parameterIndex, reader, (long) -1);
	}

	@Override
	public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
		setCharacterStream(parameterIndex, reader, (long) length);
	}

	@Override
	public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {

		StringWriter writer = new StringWriter();
		try {
			CharStreams.copy(reader, writer);
		}
		catch(IOException e) {
			throw new SQLException(e);
		}
		
		set(parameterIndex, writer.toString());
	}

	@Override
	public void setObject(int parameterIndex, Object x) throws SQLException {		
		set(parameterIndex, x);
	}

	@Override
	public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
		checkClosed();
		
		setObject(parameterIndex, x, targetSqlType, 0);
	}

	@Override
	public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
		checkClosed();
		checkParameterIndex(parameterIndex);

		set(parameterIndex, x);
	}

	@Override
	public void setBlob(int parameterIndex, Blob x) throws SQLException {
		set(parameterIndex, x); 
	}

	@Override
	public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
		setBlob(parameterIndex, ByteStreams.limit(inputStream, length));
	}

	@Override
	public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
		
		Blob blob = connection.createBlob();
		
		try {
			ByteStreams.copy(inputStream, blob.setBinaryStream(0));
		}
		catch(IOException e) {
			throw new SQLException(e);
		}

		set(parameterIndex, blob);
	}

	@Override
	public void setArray(int parameterIndex, Array x) throws SQLException {
		set(parameterIndex, x);
	}

	@Override
	public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
		set(parameterIndex, null);
	}

	@Override
	public void setURL(int parameterIndex, URL x) throws SQLException {
		set(parameterIndex, x);
	}

	@Override
	public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public void setRowId(int parameterIndex, RowId x) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public void setRef(int parameterIndex, Ref x) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public void setNString(int parameterIndex, String value) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public void setClob(int parameterIndex, Clob x) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public void setNClob(int parameterIndex, NClob value) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public void setClob(int parameterIndex, Reader reader) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public void setNClob(int parameterIndex, Reader reader) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
		checkClosed();
		throw NOT_IMPLEMENTED;
	}

	@Override
	public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
		throw NOT_ALLOWED_ON_PREP_STMT;
	}

	@Override
	public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
		throw NOT_ALLOWED_ON_PREP_STMT;
	}

	@Override
	public int executeUpdate(String sql, String[] columnNames) throws SQLException {
		throw NOT_ALLOWED_ON_PREP_STMT;
	}

	@Override
	public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
		throw NOT_ALLOWED_ON_PREP_STMT;
	}

	@Override
	public boolean execute(String sql, int[] columnIndexes) throws SQLException {
		throw NOT_ALLOWED_ON_PREP_STMT;
	}

	@Override
	public boolean execute(String sql, String[] columnNames) throws SQLException {
		throw NOT_ALLOWED_ON_PREP_STMT;
	}

	@Override
	public boolean execute(String sql) throws SQLException {
		throw NOT_ALLOWED_ON_PREP_STMT;
	}

	@Override
	public ResultSet executeQuery(String sql) throws SQLException {
		throw NOT_ALLOWED_ON_PREP_STMT;
	}

	@Override
	public int executeUpdate(String sql) throws SQLException {
		throw NOT_ALLOWED_ON_PREP_STMT;
	}

	@Override
	public void addBatch(String sql) throws SQLException {
		throw NOT_ALLOWED_ON_PREP_STMT;
	}

}
