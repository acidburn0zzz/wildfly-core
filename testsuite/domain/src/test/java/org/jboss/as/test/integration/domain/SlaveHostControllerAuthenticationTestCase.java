/*
 * Copyright 2017 JBoss by Red Hat.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.as.test.integration.domain;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CORE_SERVICE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HOST;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.MANAGEMENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REMOVE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SECRET;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SECURITY_REALM;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER_IDENTITY;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.security.auth.callback.CallbackHandler;

import org.apache.commons.lang3.StringUtils;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.OperationBuilder;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.test.integration.domain.management.util.Authentication;
import org.jboss.as.test.integration.domain.management.util.DomainLifecycleUtil;
import org.jboss.as.test.integration.domain.management.util.DomainTestSupport;
import org.jboss.as.test.integration.domain.management.util.WildFlyManagedConfiguration;
import org.jboss.dmr.ModelNode;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test a slave HC connecting to the domain using all 3 valid ways of configuring the slave HC's credential:
 * Base64 encoded password, system-property-backed expression.
 *
 * @author Brian Stansberry (c) 2013 Red Hat Inc.
 */
public class SlaveHostControllerAuthenticationTestCase extends AbstractSlaveHCAuthenticationTestCase {

    private static final String DEFAULT_SECRET = "c2xhdmVfdXMzcl9wYXNzd29yZA==";
    private static final String RIGHT_PASSWORD = DomainLifecycleUtil.SLAVE_HOST_PASSWORD;
    private static final String CREDENTIAL_STORE_NAME = "SlaveHostControllerAuthenticationTestCase";
    private static final String SECRET_KEY_CREDENTIAL_STORE_NAME = "SlaveHostControllerAuthenticationTestCase-secretkey";
    private static final Path CREDNETIAL_STORE_STORAGE_FILE = Paths.get("target1/", CREDENTIAL_STORE_NAME + ".jceks");
    private static final Path SECRET_KEY_CREDNETIAL_STORE_STORAGE_FILE = Paths.get("target/", SECRET_KEY_CREDENTIAL_STORE_NAME + ".cs");
    private static final String ALIAS_NAME = "aliasName";

    private static ModelControllerClient domainMasterClient;
    private static ModelControllerClient domainSlaveClient;
    private static DomainTestSupport testSupport;

    @BeforeClass
    public static void setupDomain() throws Exception {

        // Set up a domain with a master that doesn't support local auth so slaves have to use configured credentials
        testSupport = DomainTestSupport.create(
                DomainTestSupport.Configuration.create(SlaveHostControllerAuthenticationTestCase.class.getSimpleName(),
                        "domain-configs/domain-standard.xml",
                        "host-configs/host-master-no-local.xml", "host-configs/host-secrets-elytron-subsystem.xml"));

        // Tweak the callback handler so the master test driver client can authenticate
        // To keep setup simple it uses the same credentials as the slave host
        WildFlyManagedConfiguration masterConfig = testSupport.getDomainMasterConfiguration();
        CallbackHandler callbackHandler = Authentication.getCallbackHandler("slave", RIGHT_PASSWORD, "ManagementRealm");
        masterConfig.setCallbackHandler(callbackHandler);

        testSupport.start();

        domainMasterClient = testSupport.getDomainMasterLifecycleUtil().getDomainClient();
        domainSlaveClient = testSupport.getDomainSlaveLifecycleUtil().getDomainClient();

    }

    @AfterClass
    public static void tearDownDomain() throws Exception {
        testSupport.close();
        testSupport = null;
        domainMasterClient = null;
        domainSlaveClient = null;
    }

    @Test
    public void testSlaveRegistration() throws Exception {
        slaveWithBase64PasswordTest();
        slaveWithSystemPropertyPasswordTest();
    }

    @Test
    public void testSlaveRegistrationCredentialReference() throws Exception {
        try {
            createCredentialStore(CREDENTIAL_STORE_NAME, CREDNETIAL_STORE_STORAGE_FILE);
            addAliasOperation(getCredentialStoreAddress(CREDENTIAL_STORE_NAME), ALIAS_NAME, RIGHT_PASSWORD);

            slaveWithCredentialReferenceClearTextTest();
            slaveWithCredentialReferenceStoreAliasTest(CREDENTIAL_STORE_NAME, ALIAS_NAME);
        } finally {
            slaveWithDefaultSecretValue();
            removeCredentialStore(CREDENTIAL_STORE_NAME, CREDNETIAL_STORE_STORAGE_FILE);
        }
    }

    @Test
    public void testSlaveRegistrationExpressionResolver() throws Exception {
        try {
            createSecretKeyCredentialStore(SECRET_KEY_CREDENTIAL_STORE_NAME, SECRET_KEY_CREDNETIAL_STORE_STORAGE_FILE);
            createExpressionResolver(SECRET_KEY_CREDENTIAL_STORE_NAME);

            slaveWithExpressionResolverTest();
        } finally {
            slaveWithDefaultSecretValue();
            removeSecretKeyCredentialStore(SECRET_KEY_CREDENTIAL_STORE_NAME, SECRET_KEY_CREDNETIAL_STORE_STORAGE_FILE);
        }
    }

    private void slaveWithBase64PasswordTest() throws Exception {
        // Simply check that the initial startup produced a registered slave
        readHostControllerStatus(domainMasterClient);
    }

    private void slaveWithSystemPropertyPasswordTest() throws Exception {

        // Set the slave secret to a system-property-backed expression
        setSlaveSecret("${slave.secret:" + RIGHT_PASSWORD + "}");

        reloadSlave();
        testSupport.getDomainSlaveLifecycleUtil().awaitHostController(System.currentTimeMillis());
        // Validate that it joined the master
        readHostControllerStatus(domainMasterClient);
    }

    private void removeServerIdentity() throws IOException {
        ModelNode op = new ModelNode();
        op.get(OP).set(REMOVE);
        op.get(OP_ADDR).add(HOST, "slave").add(CORE_SERVICE, MANAGEMENT).add(SECURITY_REALM, "ManagementRealm")
                .add(SERVER_IDENTITY, SECRET);
        getDomainSlaveClient().execute(new OperationBuilder(op).build());
    }

    private void slaveWithDefaultSecretValue() throws Exception {
        removeServerIdentity();

        ModelNode op = new ModelNode();
        op.get(OP).set(ModelDescriptionConstants.ADD);
        op.get(OP_ADDR).add(HOST, "slave").add(CORE_SERVICE, MANAGEMENT).add(SECURITY_REALM, "ManagementRealm")
                .add(SERVER_IDENTITY, SECRET);
        op.get("value").set(DEFAULT_SECRET);
        getDomainSlaveClient().execute(new OperationBuilder(op).build());

        reloadSlave();
        testSupport.getDomainSlaveLifecycleUtil().awaitHostController(System.currentTimeMillis());
        // Validate that it joined the master
        readHostControllerStatus(domainMasterClient);
    }

    private void slaveWithCredentialReferenceClearTextTest() throws Exception {
        removeServerIdentity();

        ModelNode op = new ModelNode();
        op.get(OP).set(ModelDescriptionConstants.ADD);
        op.get(OP_ADDR).add(HOST, "slave").add(CORE_SERVICE, MANAGEMENT).add(SECURITY_REALM, "ManagementRealm")
                .add(SERVER_IDENTITY, SECRET);
        op.get("credential-reference").set(prepareCredentialReference(RIGHT_PASSWORD));
        getDomainSlaveClient().execute(new OperationBuilder(op).build());

        reloadSlave();
        testSupport.getDomainSlaveLifecycleUtil().awaitHostController(System.currentTimeMillis());
        // Validate that it joined the master
        readHostControllerStatus(domainMasterClient);
    }

    private void slaveWithCredentialReferenceStoreAliasTest(String storeName, String aliasName) throws Exception {
        removeServerIdentity();

        ModelNode op = new ModelNode();
        op.get(OP).set(ModelDescriptionConstants.ADD);
        op.get(OP_ADDR).add(HOST, "slave").add(CORE_SERVICE, MANAGEMENT).add(SECURITY_REALM, "ManagementRealm")
                .add(SERVER_IDENTITY, SECRET);
        op.get("credential-reference").set(prepareCredentialReference(storeName, aliasName));
        getDomainSlaveClient().execute(new OperationBuilder(op).build());

        reloadSlave();
        testSupport.getDomainSlaveLifecycleUtil().awaitHostController(System.currentTimeMillis());
        // Validate that it joined the master
        readHostControllerStatus(domainMasterClient);
    }

    private void slaveWithExpressionResolverTest() throws Exception {
        ModelNode op = new ModelNode();
        op.get(OP).set("create-expression");
        op.get(OP_ADDR).add(HOST, "slave").add(SUBSYSTEM, "elytron").add("expression", "encryption");
        op.get("clear-text").set(RIGHT_PASSWORD);
        ModelNode encryptedExpressionResult = getDomainSlaveClient().execute(new OperationBuilder(op).build());
        String encryptedExpression = encryptedExpressionResult.get("result").get("expression").asString();

        setSlaveSecret(encryptedExpression);

        reloadSlave();
        testSupport.getDomainSlaveLifecycleUtil().awaitHostController(System.currentTimeMillis());
        // Validate that it joined the master
        readHostControllerStatus(domainMasterClient);
    }

    private void createCredentialStore(String storeName, Path storageFile) throws IOException {
        ModelNode op = new ModelNode();
        op.get(OP).set(ADD);
        op.get(OP_ADDR).add(HOST, "slave").add(SUBSYSTEM, "elytron").add("credential-store", storeName);

        op.get("location").set(storageFile.toAbsolutePath().toString());
        op.get("create").set(true);

        ModelNode credentialRefParams = new ModelNode();
        credentialRefParams.get("clear-text").set("password123");
        op.get("credential-reference").set(credentialRefParams);
        getDomainSlaveClient().execute(new OperationBuilder(op).build());
    }

    private void createSecretKeyCredentialStore(String storeName, Path storageFile) throws IOException {
        ModelNode op = new ModelNode();
        op.get(OP).set(ADD);
        op.get(OP_ADDR).add(HOST, "slave").add(SUBSYSTEM, "elytron").add("secret-key-credential-store", storeName);
        op.get("path").set(storageFile.toAbsolutePath().toString());
        op.get("create").set(true);
        getDomainSlaveClient().execute(new OperationBuilder(op).build());
    }

    private void createExpressionResolver(String storeName) throws IOException {
        ModelNode resolvers = new ModelNode().setEmptyList();
        ModelNode defaultResolver = new ModelNode();
        defaultResolver.get("name").set("default");
        defaultResolver.get("secret-key").set("key");
        defaultResolver.get("credential-store").set(storeName);
        resolvers.add(defaultResolver);

        ModelNode op = new ModelNode();
        op.get(OP).set(ADD);
        op.get(OP_ADDR).add(HOST, "slave").add(SUBSYSTEM, "elytron").add("expression", "encryption");
        op.get("default-resolver").set("default");
        op.get("resolvers").set(resolvers);
        getDomainSlaveClient().execute(new OperationBuilder(op).build());
    }

    private void removeCredentialStore(String storeName, Path storageFile) throws Exception {
        ModelNode op = new ModelNode();
        op.get(OP).set(REMOVE);
        op.get(OP_ADDR).add(HOST, "slave").add(SUBSYSTEM, "elytron").add("credential-store", storeName);
        getDomainSlaveClient().execute(new OperationBuilder(op).build());
        Files.deleteIfExists(storageFile);
        reloadSlave();
        testSupport.getDomainSlaveLifecycleUtil().awaitHostController(System.currentTimeMillis());
    }

    private void removeSecretKeyCredentialStore(String storeName, Path storageFile) throws Exception {
        ModelNode op = new ModelNode();
        op.get(OP).set(REMOVE);
        op.get(OP_ADDR).add(HOST, "slave").add(SUBSYSTEM, "elytron").add("secret-key-credential-store", storeName);
        getDomainSlaveClient().execute(new OperationBuilder(op).build());
        reloadSlave();
        testSupport.getDomainSlaveLifecycleUtil().awaitHostController(System.currentTimeMillis());
        Files.deleteIfExists(storageFile);
    }

    private ModelNode getCredentialStoreAddress(String storeName) {
        return Operations.createAddress(HOST, "slave", SUBSYSTEM, "elytron", "credential-store", storeName);
    }

    protected void addAliasOperation(ModelNode credentialStoreAddress, String aliasName, String secretValue)
        throws IOException {
        ModelNode op = Operations.createOperation("add-alias", credentialStoreAddress);
        op.get("secret-value").set(secretValue);
        op.get("alias").set(aliasName);
        getDomainSlaveClient().execute(new OperationBuilder(op).build());
    }

    private ModelNode prepareCredentialReference(String clearText) {
        return prepareCredentialReference(clearText, null, null);
    }

    private ModelNode prepareCredentialReference(String store, String alias) {
        return prepareCredentialReference(null, store, alias);
    }

    private ModelNode prepareCredentialReference(String clearText, String store, String alias) {
        ModelNode credentialRefParams = new ModelNode();
        if (StringUtils.isNotBlank(clearText)) {
            credentialRefParams.get("clear-text").set(clearText);
        }
        if (StringUtils.isNotBlank(alias)) {
            credentialRefParams.get("alias").set(alias);
        }
        if (StringUtils.isNotBlank(store)) {
            credentialRefParams.get("store").set(store);
        }
        return credentialRefParams;
    }

    @Override
    protected ModelControllerClient getDomainMasterClient() {
        return domainMasterClient;
    }

    @Override
    protected ModelControllerClient getDomainSlaveClient() {
        return domainSlaveClient;
    }
}
