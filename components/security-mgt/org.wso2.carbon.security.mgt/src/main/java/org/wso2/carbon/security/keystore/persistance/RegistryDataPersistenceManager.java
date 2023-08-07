/*
 * Copyright (c) 2023, WSO2 LLC. (https://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.security.keystore.persistance;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.impl.builder.StAXOMBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.security.keystore.KeyStoreException;
import org.wso2.carbon.security.keystore.KeyStoreRuntimeException;
import org.wso2.carbon.utils.CarbonUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;

/**
 * This class is used to get the database connection for shared registry.
 */
public class RegistryDataPersistenceManager {

    private static Log log = LogFactory.getLog(RegistryDataPersistenceManager.class);

    private static volatile RegistryDataPersistenceManager instance;

    private static DataSource dataSource;

    public static final String DATA_SOURCE = "DataSource";
    public static final String NAME = "Name";
    private static final String PG_ACTIVE_SQL_TRANSACTION_STATE = "25001";
    private static final String POSTGRESQL_DATABASE = "PostgreSQL";
    public static final String DEFAULT_NAMESPACE = "http://wso2.org/projects/carbon/carbon.xml";
    public static final String IDENTITY_CONFIG = "identity.xml";

    /**
     * Private constructor which will not allow to create objects of this class from outside
     */
    private RegistryDataPersistenceManager() {

        initDataSource();
    }

    /**
     * Singleton method
     *
     * @return RegistryDataPersistenceManager
     */
    public static RegistryDataPersistenceManager getInstance() {

        if (instance == null) {
            synchronized (RegistryDataPersistenceManager.class) {
                if (instance == null) {
                    instance = new RegistryDataPersistenceManager();
                }
            }
        }
        return instance;
    }

    private OMElement getJDBCPersistenceManagerConfigElement() {

        OMElement rootElement;
        StAXOMBuilder builder = null;
        String identityConfigDirPath = CarbonUtils.getCarbonConfigDirPath() + File.separator + "identity";
        File identityConfigXml = new File(identityConfigDirPath, IDENTITY_CONFIG);

        if (identityConfigXml.exists()) {
            try (InputStream inStream = new FileInputStream(identityConfigXml)) {
                builder = new StAXOMBuilder(inStream);
                rootElement = builder.getDocumentElement();
                return rootElement.getFirstChildWithName(new QName(DEFAULT_NAMESPACE,
                        "RegistryDataPersistenceManager"));
            } catch (FileNotFoundException | XMLStreamException e) {
                try {
                    throw new KeyStoreException("Error while reading identity configuration file.", e);
                } catch (KeyStoreException ex) {
                    throw new KeyStoreRuntimeException(ex.getMessage());
                }
            } catch (IOException e) {
                throw new KeyStoreRuntimeException(e.getMessage());
            }
        }
        return null;
    }

    /**
     * Initialize the datasource
     */
    private void initDataSource() {

        OMElement persistenceManagerConfigElem = getJDBCPersistenceManagerConfigElement();
        try {
            if (persistenceManagerConfigElem == null) {
                String errorMsg = "Registry Data Persistence Manager configuration is not available in " +
                        "identity.xml file. Terminating the initialization. This may affect certain functionality.";
                throw KeyStoreRuntimeException.error(errorMsg);
            }

            OMElement dataSourceElem = persistenceManagerConfigElem.getFirstChildWithName(
                    new QName(DEFAULT_NAMESPACE, DATA_SOURCE));

            if (dataSourceElem == null) {
                String errorMsg = "DataSource Element is not available for Registry Data Persistence " +
                        "Manager in identity.xml file. Terminating the Registry Data Persistence Manager " +
                        "initialization. This might affect certain features.";
                throw KeyStoreRuntimeException.error(errorMsg);
            }

            OMElement dataSourceNameElem = dataSourceElem.getFirstChildWithName(
                    new QName(DEFAULT_NAMESPACE, NAME));

            if (dataSourceNameElem != null) {
                String dataSourceName = dataSourceNameElem.getText();
                Context ctx = new InitialContext();
                dataSource = (DataSource) ctx.lookup(dataSourceName);
            }
        } catch (NamingException e) {
            String errorMsg = "Error when looking up the Registry Data Source.";
            throw KeyStoreRuntimeException.error(errorMsg, e);
        }
    }

    /**
     * Returns a database connection for shared registry data source.
     *
     * @param shouldApplyTransaction apply transaction or not
     * @return Database connection.
     * @throws KeyStoreRuntimeException Exception occurred when getting the data source.
     */
    public Connection getDBConnection(boolean shouldApplyTransaction) throws KeyStoreRuntimeException {

        try {
            Connection dbConnection = dataSource.getConnection();
            if (shouldApplyTransaction) {
                dbConnection.setAutoCommit(false);
                try {
                    dbConnection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                } catch (SQLException e) {
                    // Handling startup error for postgresql
                    // Active SQL Transaction means that connection is not committed.
                    // Need to commit before setting isolation property.
                    if (dbConnection.getMetaData().getDriverName().contains(POSTGRESQL_DATABASE)
                            && PG_ACTIVE_SQL_TRANSACTION_STATE.equals(e.getSQLState())) {
                        dbConnection.commit();
                        dbConnection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                    }
                }
            }
            return dbConnection;
        } catch (SQLException e) {
            String errMsg = "Error when getting a database connection object from the Shared data source.";
            throw KeyStoreDBConnectionException.error(errMsg, e);
        }
    }

    public static void initializeDatabase() {

        DBInitializer dbInitializer = new DBInitializer(dataSource);
        dbInitializer.createIdentityDatabase();
    }

    /**
     * Get the registry datasource.
     *
     * @return DataSource.
     */
    public DataSource getDataSource() {

        return dataSource;
    }

    /**
     * Revoke the transaction when catch then sql transaction errors.
     *
     * @param dbConnection database connection.
     */
    public void rollbackTransaction(Connection dbConnection) {

        try {
            if (dbConnection != null) {
                dbConnection.rollback();
            }
        } catch (SQLException e1) {
            log.error("An error occurred while rolling back transactions. ", e1);
        }
    }

    /**
     * Commit the transaction.
     *
     * @param dbConnection database connection.
     */
    public void commitTransaction(Connection dbConnection) {

        try {
            if (dbConnection != null) {
                dbConnection.commit();
            }
        } catch (SQLException e1) {
            log.error("An error occurred while commit transactions. ", e1);
        }
    }
}
