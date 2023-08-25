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

package org.wso2.carbon.security.keystore.dao.impl;

import org.wso2.carbon.database.utils.jdbc.NamedPreparedStatement;
import org.wso2.carbon.identity.core.util.IdentityDatabaseUtil;
import org.wso2.carbon.security.keystore.KeyStoreManagementException;
import org.wso2.carbon.security.keystore.dao.PubCertDAO;
import org.wso2.carbon.security.keystore.dao.constants.PubCertDAOConstants;
import org.wso2.carbon.security.keystore.dao.constants.PubCertDAOConstants.PubCertTableColumns;
import org.wso2.carbon.security.keystore.model.PubCertModel;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * This class provides the implementation of the PubCertDAO interface.
 */
public class PubCertDAOImpl implements PubCertDAO {

    private static final String DB_CONN_RETRIEVAL_ERROR_MSG = "Error while getting the DB connection.";

    /**
     * {@inheritDoc}
     */
    @Override
    public String addPubCert(PubCertModel pubCertModel) throws KeyStoreManagementException {

        try (Connection connection = IdentityDatabaseUtil.getDBConnection(true)) {
            try {
                String uuid = processAddPubCert(connection, pubCertModel);
                IdentityDatabaseUtil.commitTransaction(connection);
                return uuid;
            } catch (SQLException e) {
                IdentityDatabaseUtil.rollbackTransaction(connection);
                throw new KeyStoreManagementException(PubCertDAOConstants.ErrorMessages.ERROR_MESSAGE_ADDING_PUB_CERT,
                        e);
            }
        } catch (SQLException e) {
            throw new KeyStoreManagementException(PubCertDAOConstants.ErrorMessages.DB_CONN_RETRIEVAL_ERROR_MSG, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<PubCertModel> getPubCert(String uuid) throws KeyStoreManagementException {

        PubCertModel pubCertModel = null;

        try (Connection connection = IdentityDatabaseUtil.getDBConnection(false)) {
            try (NamedPreparedStatement statement = new NamedPreparedStatement(connection,
                    PubCertDAOConstants.SQLQueries.GET_PUB_CERT)) {
                statement.setString(PubCertTableColumns.ID, uuid);
                statement.setMaxRows(1);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        pubCertModel = new PubCertModel();
                        pubCertModel.setFileNameAppender(resultSet.getString(PubCertTableColumns.FILE_NAME_APPENDER));
                        pubCertModel.setContent(resultSet.getBytes(PubCertTableColumns.CONTENT));
                    }
                }
            } catch (SQLException e) {
                throw new KeyStoreManagementException(PubCertDAOConstants.ErrorMessages
                        .ERROR_MESSAGE_RETRIEVING_PUB_CERT, e);
            }
        } catch (SQLException e) {
            throw new KeyStoreManagementException(PubCertDAOConstants.ErrorMessages.DB_CONN_RETRIEVAL_ERROR_MSG, e);
        }
        return Optional.ofNullable(pubCertModel);
    }

    private String processAddPubCert(Connection connection, PubCertModel pubCertModel)
            throws SQLException {

        String id = UUID.randomUUID().toString();

        try (NamedPreparedStatement statement = new NamedPreparedStatement(connection,
                PubCertDAOConstants.SQLQueries.ADD_PUB_CERT)) {
            statement.setString(PubCertTableColumns.ID, id);
            statement.setString(PubCertTableColumns.FILE_NAME_APPENDER, pubCertModel.getFileNameAppender());
            statement.setBytes(3, pubCertModel.getContent());
            statement.executeUpdate();
        }
        return id;
    }
}
