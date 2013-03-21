package com.impossibl.postgres.jdbc;

import java.sql.SQLException;
import java.sql.Savepoint;


/**
 * Reference to a savepoint
 * 
 * @author kdubb
 *
 */
class PGSavepoint implements Savepoint {

	Integer id;
	String name;

	PGSavepoint(int id) {
		this.id = id;
	}

	public PGSavepoint(String name) {
		this.name = name;
	}

	@Override
	public int getSavepointId() throws SQLException {
		if(id == null)
			throw new SQLException("named savepoints have no id");
		return id;
	}

	@Override
	public String getSavepointName() throws SQLException {
		if(name == null)
			throw new SQLException("auto savepoints have no name");
		return name;
	}

	public String toString() {
		return name != null ? name : Integer.toString(id);
	}

	public boolean isValid() {
		return id != null || name != null;
	}

	public void invalidate() {
		id = null;
		name = null;
	}

}