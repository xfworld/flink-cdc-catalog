package com.superior.flink.cdc.catalog;

import com.superior.flink.mapper.OracleTypeMapper;
import com.ververica.cdc.connectors.oracle.source.config.OracleSourceOptions;
import com.ververica.cdc.connectors.oracle.table.OracleTableSourceFactory;
import org.apache.commons.compress.utils.Lists;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.connector.jdbc.dialect.JdbcDialectTypeMapper;
import org.apache.flink.connector.jdbc.table.JdbcConnectorOptions;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.catalog.CatalogBaseTable;
import org.apache.flink.table.catalog.CatalogTable;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.table.catalog.UniqueConstraint;
import org.apache.flink.table.catalog.exceptions.CatalogException;
import org.apache.flink.table.catalog.exceptions.DatabaseNotExistException;
import org.apache.flink.table.catalog.exceptions.TableNotExistException;
import org.apache.flink.table.factories.Factory;
import org.apache.flink.table.types.DataType;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.TemporaryClassLoaderContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.ververica.cdc.connectors.base.options.JdbcSourceOptions.*;
import static org.apache.flink.connector.jdbc.table.JdbcConnectorOptions.*;
import static org.apache.flink.connector.jdbc.table.JdbcConnectorOptions.TABLE_NAME;
import static org.apache.flink.connector.jdbc.table.JdbcDynamicTableFactory.IDENTIFIER;
import static org.apache.flink.table.factories.FactoryUtil.CONNECTOR;

public class OracleCatalog extends AbstractJdbcCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(OracleCatalog.class);

    private static final String ORACLE_CONNECTOR = "oracle-cdc";

    private final JdbcDialectTypeMapper dialectTypeMapper;

    private static final Set<String> builtinDatabases = new HashSet<String>();

    public OracleCatalog(
            ClassLoader userClassLoader,
            String catalogName,
            String defaultDatabase,
            String username,
            String pwd,
            String baseUrl) {
        super(userClassLoader, catalogName, defaultDatabase, username, pwd, baseUrl);

        String driverVersion =
                Preconditions.checkNotNull(getDriverVersion(), "Driver version must not be null.");
        String databaseVersion =
                Preconditions.checkNotNull(
                        getDatabaseVersion(), "Database version must not be null.");
        LOG.info("Driver version: {}, database version: {}", driverVersion, databaseVersion);
        this.dialectTypeMapper = new OracleTypeMapper(databaseVersion, driverVersion);
    }

    @Override
    protected String getJdbcUrl() {
        return baseUrl + defaultDatabase;
    }

    @Override
    public Optional<Factory> getFactory() {
        return Optional.of(new OracleTableSourceFactory());
    }

    @Override
    protected String getConnector() {
        return ORACLE_CONNECTOR;
    }

    @Override
    public List<String> listDatabases() throws CatalogException {
        return extractColumnValuesBySQL(
                this.getJdbcUrl(),
                "select username from sys.dba_users where DEFAULT_TABLESPACE <> 'SYSTEM' and DEFAULT_TABLESPACE <> 'SYSAUX' ",
                1,
                dbName -> !builtinDatabases.contains(dbName));
    }

    @Override
    public List<String> listTables(String databaseName)
            throws DatabaseNotExistException, CatalogException {
        Preconditions.checkState(
                StringUtils.isNotBlank(databaseName), "Database name must not be blank.");
        if (!databaseExists(databaseName)) {
            throw new DatabaseNotExistException(getName(), databaseName);
        }

        return extractColumnValuesBySQL(
                baseUrl + databaseName,
                "SELECT table_name FROM sys.all_tables where OWNER = ?",
                1,
                null,
                databaseName);
    }

    @Override
    public CatalogBaseTable getTable(ObjectPath tablePath)
            throws TableNotExistException, CatalogException {

        if (!tableExists(tablePath)) {
            throw new TableNotExistException(getName(), tablePath);
        }

        String databaseName = tablePath.getDatabaseName();
        try (Connection conn = DriverManager.getConnection(defaultUrl, username, pwd)) {
            DatabaseMetaData metaData = conn.getMetaData();
            Optional<UniqueConstraint> primaryKey =
                    getPrimaryKey(
                            metaData,
                            databaseName,
                            getSchemaName(tablePath),
                            getTableName(tablePath));

            PreparedStatement ps =
                    conn.prepareStatement(
                            String.format("SELECT * FROM %s", getSchemaTableName(tablePath)));

            ResultSetMetaData resultSetMetaData = ps.getMetaData();

            String[] columnNames = new String[resultSetMetaData.getColumnCount()];
            DataType[] types = new DataType[resultSetMetaData.getColumnCount()];

            for (int i = 1; i <= resultSetMetaData.getColumnCount(); i++) {
                columnNames[i - 1] = resultSetMetaData.getColumnName(i);
                types[i - 1] = fromJDBCType(tablePath, resultSetMetaData, i);
                if (resultSetMetaData.isNullable(i) == ResultSetMetaData.columnNoNulls) {
                    types[i - 1] = types[i - 1].notNull();
                }
            }

            Schema.Builder schemaBuilder = Schema.newBuilder().fromFields(columnNames, types);
            primaryKey.ifPresent(
                    pk -> schemaBuilder.primaryKeyNamed(pk.getName(), pk.getColumns()));
            Schema tableSchema = schemaBuilder.build();

            String dbPeer = StringUtils.substringBetween(defaultUrl, "@//", "/");
            String[] items = StringUtils.split(dbPeer, ":");

            Map<String, String> props = new HashMap<>();
            props.put(CONNECTOR.key(), IDENTIFIER);
            props.put(URL.key(), defaultUrl);
            props.put(HOSTNAME.key(), items[0]);
            props.put(OracleSourceOptions.PORT.key(), items[1]);
            props.put(JdbcConnectorOptions.USERNAME.key(), username);
            props.put(JdbcConnectorOptions.PASSWORD.key(), pwd);
            props.put(DATABASE_NAME.key(), getDefaultDatabase());
            props.put(SCHEMA_NAME.key(), getSchemaName(tablePath));
            props.put(TABLE_NAME.key(), getSchemaTableName(tablePath));
            return CatalogTable.of(tableSchema, null, Lists.newArrayList(), props);
        } catch (Exception e) {
            throw new CatalogException(
                    String.format("Failed getting table %s", tablePath.getFullName()), e);
        }
    }

    @Override
    public boolean tableExists(ObjectPath tablePath) throws CatalogException {
        return !extractColumnValuesBySQL(
                defaultUrl,
                "SELECT table_name FROM sys.all_tables where OWNER = ? and table_name = ?",
                1,
                null,
                tablePath.getDatabaseName(),
                tablePath.getObjectName())
                .isEmpty();
    }

    private String getDatabaseVersion() {
        try (TemporaryClassLoaderContext ignored =
                     TemporaryClassLoaderContext.of(userClassLoader)) {
            try (Connection conn = DriverManager.getConnection(defaultUrl, username, pwd)) {
                return conn.getMetaData().getDatabaseProductVersion();
            } catch (Exception e) {
                throw new CatalogException(
                        String.format("Failed in getting MySQL version by %s.", defaultUrl), e);
            }
        }
    }

    private String getDriverVersion() {
        try (TemporaryClassLoaderContext ignored =
                     TemporaryClassLoaderContext.of(userClassLoader)) {
            try (Connection conn = DriverManager.getConnection(defaultUrl, username, pwd)) {
                String driverVersion = conn.getMetaData().getDriverVersion();
                Pattern regexp = Pattern.compile("\\d+?\\.\\d+?\\.\\d+");
                Matcher matcher = regexp.matcher(driverVersion);
                return matcher.find() ? matcher.group(0) : null;
            } catch (Exception e) {
                throw new CatalogException(
                        String.format("Failed in getting MySQL driver version by %s.", defaultUrl),
                        e);
            }
        }
    }

    /**
     * Converts MySQL type to Flink {@link DataType}.
     */
    @Override
    protected DataType fromJDBCType(ObjectPath tablePath, ResultSetMetaData metadata, int colIndex)
            throws SQLException {
        return dialectTypeMapper.mapping(tablePath, metadata, colIndex);
    }

    @Override
    protected String getTableName(ObjectPath tablePath) {
        return tablePath.getObjectName();
    }

    @Override
    protected String getSchemaName(ObjectPath tablePath) {
        return tablePath.getDatabaseName();
    }

    @Override
    protected String getSchemaTableName(ObjectPath tablePath) {
        return tablePath.getObjectName();
    }
}
