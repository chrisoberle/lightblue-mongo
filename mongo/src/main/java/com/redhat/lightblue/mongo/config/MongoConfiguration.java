/*
 Copyright 2013 Red Hat, Inc. and/or its affiliates.

 This file is part of lightblue.

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.redhat.lightblue.mongo.config;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import java.security.cert.X509Certificate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.mongodb.DB;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoCredential;
import com.mongodb.ReadPreference;
import com.mongodb.ServerAddress;
import com.mongodb.WriteConcern;
import com.redhat.lightblue.config.DataSourceConfiguration;
import com.redhat.lightblue.mongo.metadata.MongoDataStoreParser;
import com.redhat.lightblue.metadata.parser.DataStoreParser;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

/**
 * Mongo client makes a distinction between constructing using a list of
 * ServerAddress objects, and a single ServerAddress object. If you construct
 * with a List, it wants access to all the nodes in the replica set. If you
 * construct with a single ServerAddress, it only talks to that server. So, we
 * make a distinction between array of server addresses and a single server
 * address.
 *
 *
 * @author bserdar
 * @author nmalik
 */
public class MongoConfiguration implements DataSourceConfiguration {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LoggerFactory.getLogger(MongoConfiguration.class);

    private final transient List<ServerAddress> servers = new ArrayList<>();
    private transient ServerAddress theServer = null;

    private Integer connectionsPerHost;
    private String database;
    private transient List<MongoCredential> credentials = new ArrayList<>();
    private boolean ssl = Boolean.FALSE;
    private boolean noCertValidation = Boolean.FALSE;
    private Class metadataDataStoreParser = MongoDataStoreParser.class;
    private ReadPreference readPreference = null;
    private WriteConcern writeConcern = WriteConcern.FSYNCED;
    private int maxResultSetSize=10000;

    public void addServerAddress(String hostname, int port) throws UnknownHostException {
        this.servers.add(new ServerAddress(hostname, port));
    }

    public void addServerAddress(String hostname) throws UnknownHostException {
        this.servers.add(new ServerAddress(hostname));
    }

    public void setServer(String hostname, int port) throws UnknownHostException {
        theServer = new ServerAddress(hostname, port);
    }

    public void setServer(String hostname) throws UnknownHostException {
        theServer = new ServerAddress(hostname);
    }

    /**
     * @return the servers
     */
    public Iterator<ServerAddress> getServerAddresses() {
        return servers.iterator();
    }

    public void clearServerAddresses() {
        this.servers.clear();
    }

    public ServerAddress getServer() {
        return theServer;
    }

    public int getMaxResultSetSize() {
        return maxResultSetSize;
    }

    public void setMaxResultSetSize(int size) {
        maxResultSetSize=size;
    }

    @Override
    public Class<DataStoreParser> getMetadataDataStoreParser() {
        return metadataDataStoreParser;
    }

    public void setMetadataDataStoreParser(Class<DataStoreParser> clazz) {
        metadataDataStoreParser = clazz;
    }

    public List<MongoCredential> getCredentials() {
        return credentials;
    }

    public void setCredentials(List<MongoCredential> l) {
        credentials = l;
    }

    /**
     * @return the connectionsPerHost
     */
    public Integer getConnectionsPerHost() {
        return connectionsPerHost;
    }

    /**
     * @param connectionsPerHost the connectionsPerHost to set
     */
    public void setConnectionsPerHost(Integer connectionsPerHost) {
        this.connectionsPerHost = connectionsPerHost;
    }

    /**
     * @return the ssl
     */
    public boolean isSsl() {
        return ssl;
    }

    /**
     * @param ssl the ssl to set
     */
    public void setSsl(boolean ssl) {
        this.ssl = ssl;
    }

    /**
     * If true, ssl certs are not validated
     */
    public boolean isNoCertValidation() {
        return noCertValidation;
    }

    /**
     * If true, ssl certs are not validated
     */
    public void setNoCertValidation(boolean b) {
        noCertValidation = b;
    }

    /**
     * The database name
     */
    public String getDatabase() {
        return database;
    }

    /**
     * The database name
     */
    public void setDatabase(String s) {
        database = s;
    }

    private static final TrustManager[] trustAllCerts = new TrustManager[]{
        new X509TrustManager() {
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return null;
            }

            @Override
            public void checkClientTrusted(X509Certificate[] certs,
                                           String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] certs,
                                           String authType) {
            }
        }
    };

    private SocketFactory getSocketFactory() {
        try {
            if (noCertValidation) {
                LOGGER.warn("Certificate validation is off, don't use this in production");
                SSLContext sc = SSLContext.getInstance("SSL");
                sc.init(null, trustAllCerts, new java.security.SecureRandom());
                return sc.getSocketFactory();
            } else {
                return SSLSocketFactory.getDefault();
            }
        } catch (KeyManagementException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns an options object with defaults overriden where there is a valid
     * override.
     *
     * @return
     */
    public MongoClientOptions getMongoClientOptions() {
        MongoClientOptions.Builder builder = MongoClientOptions.builder();

        if (connectionsPerHost != null) {
            builder.connectionsPerHost(connectionsPerHost);
        }

        if (this.readPreference != null)
            builder.readPreference(readPreference);

        if (ssl) {
            // taken from MongoClientURI, written this way so we don't have to
            // construct a URI to connect
            builder.socketFactory(getSocketFactory());
        }
        builder.writeConcern(writeConcern);

        return builder.build();
    }

    public MongoClient getMongoClient() throws UnknownHostException {
        MongoClientOptions options = getMongoClientOptions();
        LOGGER.debug("getMongoClient with server: {}, servers:{} and options:{}", theServer, servers, options);
        if (theServer != null) {
            return new MongoClient(theServer, credentials, options);
        } else {
            return new MongoClient(servers, credentials, options);
        }
    }

    public DB getDB() throws UnknownHostException {
        return getMongoClient().getDB(database);
    }

    @Override
    public String toString() {
        StringBuilder bld = new StringBuilder();
        if (theServer != null) {
            bld.append("server").append(theServer).append('\n');
        } else {
            bld.append("servers:").append(servers).append('\n');
        }
        bld.append("connectionsPerHost:").append(connectionsPerHost).append('\n').
            append("database:").append(database).append('\n').
            append("ssl:").append(ssl).append('\n').
            append("writeConcern:").append(writeConcern).append('\n').
            append("noCertValidation:").append(noCertValidation).append('\n').
            append("maxResultSetSize:").append(maxResultSetSize);
        bld.append("credentials:");
        boolean first = true;
        for (MongoCredential c : credentials) {
            if (first) {
                first = false;
            } else {
                bld.append(',');
            }
            bld.append(toString(c));
        }
        return bld.toString();
    }

    public static MongoCredential credentialFromJson(ObjectNode node) {
        String userName = null;
        String password = null;
        String source = null;

        JsonNode xnode = node.get("mechanism");
        if (xnode == null) {
            throw new IllegalArgumentException("mechanism is required in credentials");
        }
        String mech = xnode.asText();
        xnode = node.get("userName");
        if (xnode != null) {
            userName = xnode.asText();
        }
        xnode = node.get("password");
        if (xnode != null) {
            password = xnode.asText();
        }
        xnode = node.get("source");
        if (xnode != null) {
            source = xnode.asText();
        }

        MongoCredential cr = null;
        if (null != mech) {
            switch (mech) {
                case "GSSAPI_MECHANISM":
                    cr = MongoCredential.createGSSAPICredential(userName);
                    break;
                case "MONGODB_CR_MECHANISM":
                    cr = MongoCredential.createMongoCRCredential(userName, source,
                            password == null ? null : password.toCharArray());
                    break;
                case "MONGODB_X509_MECHANISM":
                    cr = MongoCredential.createMongoX509Credential(userName);
                    break;
                case "PLAIN_MECHANISM":
                    cr = MongoCredential.createPlainCredential(userName, source,
                            password == null ? null : password.toCharArray());
                    break;
                default:
                    throw new IllegalArgumentException("invalid mechanism:" + mech + ", must be one of "
                            + "GSSAPI_MECHANISM, MONGODB_CR_MECHANISM, "
                            + "MONGODB_X5090_MECHANISM, or PLAIN_MECHANISM");
            }
        }
        return cr;
    }

    public static List<MongoCredential> credentialsFromJson(JsonNode node) {
        List<MongoCredential> list = new ArrayList<>();
        try {
            if (node instanceof ArrayNode) {
                for (Iterator<JsonNode> itr = node.elements(); itr.hasNext();) {
                    list.add(credentialFromJson((ObjectNode) itr.next()));
                }
            } else if (node != null) {
                list.add(credentialFromJson((ObjectNode) node));
            }
        } catch (ClassCastException e) {
            LOGGER.debug("Invalid credentials node: " + node);
            throw new IllegalArgumentException("Invalid credentials node, see debug log for details");
        }
        return list;
    }

    public static String toString(MongoCredential cr) {
        StringBuilder bld = new StringBuilder();
        bld.append("{mechanism:").append(cr.getMechanism());
        if (cr.getUserName() != null) {
            bld.append(" userName:").append(cr.getUserName());
        }
        if (cr.getPassword() != null) {
            bld.append(" password:").append(cr.getPassword());
        }
        if (cr.getSource() != null) {
            bld.append(" source:").append(cr.getSource());
        }
        bld.append('}');
        return bld.toString();
    }

    @Override
    public void initializeFromJson(JsonNode node) {
        if (node != null) {
            JsonNode x = node.get("connectionsPerHost");
            if (x != null) {
                connectionsPerHost = x.asInt();
            }
            x = node.get("ssl");
            if (x != null) {
                ssl = x.asBoolean();
            }
            x = node.get("noCertValidation");
            if (x != null) {
                noCertValidation = x.asBoolean();
            }
            credentials = credentialsFromJson(node.get("credentials"));
            x = node.get("metadataDataStoreParser");
            try {
                if (x != null) {
                    metadataDataStoreParser = Class.forName(x.asText());
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException(e);
            }
            x = node.get("database");
            if (x != null) {
                database = x.asText();
            }
            x = node.get("writeConcern");
            if(x != null){
                writeConcern = WriteConcern.valueOf(x.asText());
            }
            x = node.get("maxResultSetSize");
            if(x!=null) {
                maxResultSetSize=x.asInt();
            }
            JsonNode jsonNodeServers = node.get("servers");
            if (jsonNodeServers != null && jsonNodeServers.isArray()) {
                Iterator<JsonNode> elements = jsonNodeServers.elements();
                while (elements.hasNext()) {
                    JsonNode next = elements.next();
                    try {
                        String host;
                        x = next.get("host");
                        if (x != null) {
                            host = x.asText();
                        } else {
                            host = null;
                        }

                        x = next.get("port");
                        if (x != null) {
                            addServerAddress(host, x.asInt());
                        } else {
                            addServerAddress(host);
                        }
                    } catch (UnknownHostException e) {
                        throw new IllegalStateException(e);
                    }
                }

            } else {
                JsonNode server = node.get("server");
                if (server != null) {
                    try {
                        x = server.get("host");
                        if (x != null) {
                            String host = x.asText();
                            x = server.get("port");
                            if (x != null) {
                                setServer(host, x.asInt());
                            } else {
                                setServer(host);
                            }
                        } else {
                            throw new IllegalStateException("host is required in server");
                        }
                    } catch (IllegalStateException | UnknownHostException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }

            JsonNode jsonNodeOptions = node.get("driverOptions");
            if (jsonNodeOptions != null) {
                JsonNode readPreferenceOption = jsonNodeOptions.get("readPreference");
                if (readPreferenceOption != null)
                    this.readPreference = ReadPreference.valueOf(readPreferenceOption.asText());
            }
        }
    }

    public WriteConcern getWriteConcern() {
        return writeConcern;
    }

    public void setWriteConcern(WriteConcern writeConcern) {
        this.writeConcern = writeConcern;
    }

    public ReadPreference getReadPreference() {
        return readPreference;
    }

    public void setReadPreference(ReadPreference readPreference) {
        this.readPreference = readPreference;
    }
}
