package com.insanitydesign.vertx;

import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

import org.vertx.java.busmods.BusModBase;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;

import com.datastax.driver.core.BatchStatement;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ColumnDefinitions;
import com.datastax.driver.core.Host;
import com.datastax.driver.core.Metadata;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SimpleStatement;
import com.datastax.driver.core.Statement;

/**
 * The main persistor module and handler in one. Connects to Cassandra, registers and handles all actions from the
 * eventbus to the defined module address.
 * 
 * @author nea@insanitydesign
 */
public class CassandraPersistor extends BusModBase implements Handler<Message<JsonObject>> {

	/** Global Logger variable for convenience */
	private Logger LOG;

	/** The configured Cassandra cluster */
	private Cluster cluster;

	/** The connected session */
	private Session session;

	/* Configuration */
	/** The configured address or default */
	private String address;
	/** The configured hosts or default */
	private JsonArray hosts;
	/** The configured port or default */
	private int port;
	/** The configured keyspace or default */
	private String keyspace;

	/**
	 * Boot up the verticle and connect to the configured Cassandra cluster.
	 */
	@Override
	public void start() {
		//
		super.start();

		//
		this.LOG = container.logger();
		LOG.info("[Cassandra Persistor] Booting up...");

		//
		setAddress(getOptionalStringConfig("address", "vertx.cassandra.persistor"));
		setHosts(getOptionalArrayConfig("hosts", new JsonArray("[\"127.0.0.1\"]")));
		setPort(getOptionalIntConfig("port", 9042));
		setKeyspace(getOptionalStringConfig("keyspace", "vertxpersistor"));

		//
		Cluster.Builder builder = Cluster.builder();
		try {
			for(int i = getHosts().size() - 1; i >= 0; --i) {
				builder = builder.addContactPoint((String) getHosts().get(i));
			}
			//
			builder = builder.withPort(getPort());
			//
			setCluster(builder.build());

		} catch(Exception e) {
			LOG.error("[Cassandra Persistor] Cannot add hosts " + getHosts(), e);
			return;
		}

		//
		try {
			//
			Metadata metadata = getCluster().getMetadata();
			LOG.info("[Cassandra Persistor] Connected to cluster: " + metadata.getClusterName());
			//
			for(Host host : metadata.getAllHosts()) {
				LOG.info("[Cassandra Persistor] DC: " + host.getDatacenter() + " - Host: " + host.getAddress() + " - Rack: "
						+ host.getRack());
			}

			setSession(getCluster().connect());

		} catch(Exception e) {
			LOG.error("[Cassandra Persistor] Cannot connect/get session from Cassandra!", e);
			return;
		}

		//
		eb.registerHandler(getAddress(), this);

		//
		LOG.info("[Cassandra Persistor] ...booted!");
	}

	/**
	 * Handle all incoming actions and process unknown ones.
	 */
	@Override
	public void handle(Message<JsonObject> message) {
		//
		String action = message.body().getString("action");

		//
		if(action == null) {
			sendError(message, "[Cassandra Persistor] Please specify an action!");
			return;
		}

		//
		try {
			switch(action) {
			// Channel the raw statements
				case "raw":
					raw(message);
					break;

				default:
					sendError(message, "[Cassandra Persistor] Action '" + action + "' unknown!");
			}

			//
		} catch(Exception e) {
			sendError(message, e);
		}
	}

	/**
	 * Processes raw Cassandra CQL3 statement(s) and returns the resultset as JsonArray (if any)
	 * 
	 * @param message
	 */
	protected void raw(Message<JsonObject> message) {
		//
		JsonObject rawMessage = message.body();

		//
		Statement query = null;
		try {
			String statement = rawMessage.getString("statement");
			//
			if(statement != null) {
				query = new SimpleStatement(statement);

			} else {
				// Batch
				JsonArray statements = rawMessage.getArray("statements");
				query = new BatchStatement();
				for(Object stmt : statements) {
					((BatchStatement) query).add(new SimpleStatement(stmt.toString()));
				}
			}

		} catch(Exception e) {
			// An error happened
			sendError(message, "[Cassandra Persistor] Could not create query statement!", e);
			return;
		}

		//
		ResultSet resultSet = null;
		try {
			resultSet = getSession().execute(query);

		} catch(Exception e) {
			// An error happened
			sendError(message, e);
			return;
		}

		// Query went through but without results
		if(resultSet == null || resultSet.getAvailableWithoutFetching() <= 0) {
			sendOK(message);
			return;
		}

		// The returned result array
		JsonArray retVals = new JsonArray();

		// Iterate the results
		for(Row row : resultSet) {
			// Row result
			JsonObject retVal = new JsonObject();

			// Get the column definitions to iterate over the different types and check
			ColumnDefinitions rowColumnDefinitions = row.getColumnDefinitions();
			for(int i = 0; i < rowColumnDefinitions.size(); i++) {
				// Null empty columns
				if(row.isNull(i)) {
					continue;
				}

				// Read the column bytes unsafe and operate on the deserialized object instead of iterating over the
				// type of the definitions
				Object columnValue = rowColumnDefinitions.getType(i).deserialize(row.getBytesUnsafe(i));

				// Parse the returning object to a supported type
				retVal = addRow(rowColumnDefinitions.getName(i), columnValue, retVal);
			}

			// Add the row
			retVals.addObject(retVal);
		}

		// Return the result array
		message.reply(retVals);
	}

	/**
	 * Process the different types of values possible.
	 * 
	 * @param columnName
	 * @param columnValue
	 * @param retVal
	 * @return The JsonObject representing this row entry
	 */
	protected JsonObject addRow(String columnName, Object columnValue, JsonObject retVal) {
		//
		if(columnValue instanceof Number) {
			retVal.putNumber(columnName, (Number) columnValue);

		} else if(columnValue instanceof String) {
			retVal.putString(columnName, (String) columnValue);

		} else if(columnValue instanceof Boolean) {
			retVal.putBoolean(columnName, (Boolean) columnValue);

		} else if(columnValue instanceof Date) {
			retVal.putString(columnName, ((Date) columnValue).toString());

		} else if(columnValue instanceof UUID) {
			retVal.putString(columnName, ((UUID) columnValue).toString());

		} else if(columnValue instanceof byte[]) {
			retVal.putBinary(columnName, (byte[]) columnValue);

		} else if(columnValue instanceof Collection<?>) {
			retVal.putArray(columnName, addCollection((Collection<?>) columnValue, new JsonArray()));

		} else if(columnValue instanceof Map<?, ?>) {
			//
			JsonObject retMap = new JsonObject();
			//
			Map<?, ?> columnValueMap = (Map<?, ?>) columnValue;
			for(Entry<?, ?> entry : columnValueMap.entrySet()) {
				addRow(entry.getKey().toString(), entry.getValue(), retMap);
			}

			//
			retVal.putObject(columnName, retMap);

		} else {
			// If nothing works, try to add the object directly but catch if not
			try {
				retVal.putValue(columnName, columnValue);
			} catch(Exception e) {
				LOG.info("[Cassandra Persistor] Could not add value of column " + columnName);
			}
		}

		//
		return retVal;
	}

	/**
	 * Process the collections (List and Set) and add the values to an array to return.
	 * 
	 * @param columnValue
	 * @param retVal
	 * @return The JsonArray representing these collection values 
	 */
	protected JsonArray addCollection(Collection<?> columnValue, JsonArray retVal) {
		//
		for(Object collectionValue : columnValue) {
			//
			if(collectionValue instanceof Number) {
				retVal.addNumber((Number) collectionValue);

			} else if(collectionValue instanceof String) {
				retVal.addString((String) collectionValue);

			} else if(collectionValue instanceof Boolean) {
				retVal.addBoolean((Boolean) collectionValue);

			} else if(columnValue instanceof Date) {
				retVal.addString(((Date) columnValue).toString());

			} else if(collectionValue instanceof UUID) {
				retVal.addString(((UUID) collectionValue).toString());

			} else if(collectionValue instanceof byte[]) {
				retVal.addBinary((byte[]) collectionValue);

			} else if(collectionValue instanceof Collection<?>) {
				retVal.addArray(addCollection((Collection<?>) collectionValue, new JsonArray()));

			} else {
				// If nothing works, try to add the object directly but catch if not
				try {
					retVal.add(columnValue);
				} catch(Exception e) {
				}
			}
		}

		//
		return retVal;
	}

	/**
	 * Convenience general error message handler
	 * 
	 * @param message
	 * @param e
	 */
	protected void sendError(Message<JsonObject> message, Exception e) {
		sendError(message, "[Cassandra Persistor] " + e.getMessage(), e);
	}

	/**
	 * 
	 */
	@Override
	public void stop() {
		//
		eb.unregisterHandler(getAddress(), this);

		//
		if(getSession() != null) {
			getSession().close();
		}

		//
		if(getCluster() != null) {
			getCluster().close();
		}

		//
		super.stop();
	}

	/* ***** GETTER/SETTER ***** */
	public Cluster getCluster() {
		return cluster;
	}

	public void setCluster(Cluster cluster) {
		this.cluster = cluster;
	}

	public Session getSession() {
		return session;
	}

	public void setSession(Session session) {
		this.session = session;
	}

	public String getAddress() {
		return address;
	}

	public void setAddress(String address) {
		this.address = address;
	}

	public JsonArray getHosts() {
		return hosts;
	}

	public void setHosts(JsonArray hosts) {
		this.hosts = hosts;
	}

	public String getKeyspace() {
		return keyspace;
	}

	public void setKeyspace(String keyspace) {
		this.keyspace = keyspace;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public int getPort() {
		return port;
	}
}
