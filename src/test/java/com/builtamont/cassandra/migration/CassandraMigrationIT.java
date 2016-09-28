/**
 * File     : CassandraMigrationIT.java
 * License  :
 *   Original   - Copyright (c) 2015 - 2016 Contrast Security
 *   Derivative - Copyright (c) 2016 Citadel Technology Solutions Pte Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.builtamont.cassandra.migration;

import com.builtamont.cassandra.migration.api.*;
import com.builtamont.cassandra.migration.internal.dbsupport.SchemaVersionDAO;
import com.builtamont.cassandra.migration.internal.info.MigrationInfoDumper;
import com.builtamont.cassandra.migration.internal.metadatatable.AppliedMigration;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.querybuilder.Select;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import static com.datastax.driver.core.querybuilder.QueryBuilder.eq;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class CassandraMigrationIT extends BaseIT {

    @Test
    public void runApiTest() {
        String[] scriptsLocations = { "migration/integ", "migration/integ/java" };
        CassandraMigration cm = new CassandraMigration();
        cm.setLocations(scriptsLocations);
        cm.setKeyspaceConfig(getKeyspace());
        cm.migrate();

        MigrationInfoService infoService = cm.info();
        System.out.println("Initial migration");
        System.out.println(MigrationInfoDumper.INSTANCE.dumpToAsciiTable(infoService.all()));
        assertThat(infoService.all().length, is(5));
        for (MigrationInfo info : infoService.all()) {
            assertThat(info.getVersion().getVersion(), anyOf(is("1.0.0"), is("2.0.0"), is("3.0"), is("3.0.1"), is("4.0.0")));
            if (info.getVersion().equals("3.0.1")) {
                assertThat(info.getDescription(), is("Three point zero one"));
                assertThat(info.getType().name(), Matchers.is(MigrationType.JAVA_DRIVER.name()));
                assertThat(info.getScript().contains(".java"), is(true));

                Select select = QueryBuilder.select().column("value").from("test1");
                select.where(eq("space", "web")).and(eq("key", "facebook"));
                ResultSet result = getSession().execute(select);
                assertThat(result.one().getString("value"), is("facebook.com"));
            } else if (info.getVersion().equals("3.0")) {
                assertThat(info.getDescription(), is("Third"));
                assertThat(info.getType().name(), Matchers.is(MigrationType.JAVA_DRIVER.name()));
                assertThat(info.getScript().contains(".java"), is(true));

                Select select = QueryBuilder.select().column("value").from("test1");
                select.where(eq("space", "web")).and(eq("key", "google"));
                ResultSet result = getSession().execute(select);
                assertThat(result.one().getString("value"), is("google.com"));
            } else if (info.getVersion().equals("2.0.0")) {
                assertThat(info.getDescription(), is("Second"));
                assertThat(info.getType().name(), Matchers.is(MigrationType.CQL.name()));
                assertThat(info.getScript().contains(".cql"), is(true));

                Select select = QueryBuilder.select().column("title").column("message").from("contents");
                select.where(eq("id", 1));
                Row row = getSession().execute(select).one();
                assertThat(row.getString("title"), is("foo"));
                assertThat(row.getString("message"), is("bar"));
            } else if (info.getVersion().equals("1.0.0")) {
                assertThat(info.getDescription(), is("First"));
                assertThat(info.getType().name(), Matchers.is(MigrationType.CQL.name()));
                assertThat(info.getScript().contains(".cql"), is(true));

                Select select = QueryBuilder.select().column("value").from("test1");
                select.where(eq("space", "foo")).and(eq("key", "bar"));
                ResultSet result = getSession().execute(select);
                assertThat(result.one().getString("value"), is("profit!"));
            }

            assertThat(info.getState().isApplied(), is(true));
            assertThat(info.getInstalledOn(), notNullValue());
        }

        // test out of order when out of order is not allowed
        String[] outOfOrderScriptsLocations = { "migration/integ_outoforder", "migration/integ/java" };
        cm = new CassandraMigration();
        cm.setLocations(outOfOrderScriptsLocations);
        cm.setKeyspaceConfig(getKeyspace());
        cm.migrate();

        infoService = cm.info();
        System.out.println("Out of order migration with out-of-order ignored");
        System.out.println(MigrationInfoDumper.INSTANCE.dumpToAsciiTable(infoService.all()));
        assertThat(infoService.all().length, is(6));
        for (MigrationInfo info : infoService.all()) {
            assertThat(info.getVersion().getVersion(),
                    anyOf(is("1.0.0"), is("2.0.0"), is("3.0"), is("3.0.1"), is("4.0.0"), is("1.1.1")));
            if (info.getVersion().equals("1.1.1")) {
                assertThat(info.getDescription(), is("Late arrival"));
                assertThat(info.getType().name(), Matchers.is(MigrationType.CQL.name()));
                assertThat(info.getScript().contains(".cql"), is(true));
                assertThat(info.getState().isApplied(), is(false));
                assertThat(info.getInstalledOn(), nullValue());
            }
        }

        // test out of order when out of order is allowed
        String[] outOfOrder2ScriptsLocations = { "migration/integ_outoforder2", "migration/integ/java" };
        cm = new CassandraMigration();
        cm.setLocations(outOfOrder2ScriptsLocations);
        cm.setAllowOutOfOrder(true);
        cm.setKeyspaceConfig(getKeyspace());
        cm.migrate();

        infoService = cm.info();
        System.out.println("Out of order migration with out-of-order allowed");
        System.out.println(MigrationInfoDumper.INSTANCE.dumpToAsciiTable(infoService.all()));
        assertThat(infoService.all().length, is(7));
        for (MigrationInfo info : infoService.all()) {
            assertThat(info.getVersion().getVersion(),
                    anyOf(is("1.0.0"), is("2.0.0"), is("3.0"), is("3.0.1"), is("4.0.0"), is("1.1.1"), is("1.1.2")));
            if (info.getVersion().equals("1.1.2")) {
                assertThat(info.getDescription(), is("Late arrival2"));
                assertThat(info.getType().name(), Matchers.is(MigrationType.CQL.name()));
                assertThat(info.getScript().contains(".cql"), is(true));
                assertThat(info.getState().isApplied(), is(true));
                assertThat(info.getInstalledOn(), notNullValue());
            }
        }

        // test out of order when out of order is allowed again
        String[] outOfOrder3ScriptsLocations = { "migration/integ_outoforder3", "migration/integ/java" };
        cm = new CassandraMigration();
        cm.setLocations(outOfOrder3ScriptsLocations);
        cm.setAllowOutOfOrder(true);
        cm.setKeyspaceConfig(getKeyspace());
        cm.migrate();

        infoService = cm.info();
        System.out.println("Out of order migration with out-of-order allowed");
        System.out.println(MigrationInfoDumper.INSTANCE.dumpToAsciiTable(infoService.all()));
        assertThat(infoService.all().length, is(8));
        for (MigrationInfo info : infoService.all()) {
            assertThat(info.getVersion().getVersion(),
                    anyOf(is("1.0.0"), is("2.0.0"), is("3.0"), is("3.0.1"), is("4.0.0"), is("1.1.1"), is("1.1.2"), is("1.1.3")));
            if (info.getVersion().equals("1.1.3")) {
                assertThat(info.getDescription(), is("Late arrival3"));
                assertThat(info.getType().name(), Matchers.is(MigrationType.CQL.name()));
                assertThat(info.getScript().contains(".cql"), is(true));
                assertThat(info.getState().isApplied(), is(true));
                assertThat(info.getInstalledOn(), notNullValue());
            }
        }
    }

    @Test
    public void testValidate() {
        // apply migration scripts
        String[] scriptsLocations = { "migration/integ", "migration/integ/java" };
        CassandraMigration cm = new CassandraMigration();
        cm.setLocations(scriptsLocations);
        cm.setKeyspaceConfig(getKeyspace());
        cm.migrate();

        MigrationInfoService infoService = cm.info();
        String validationError = infoService.validate();
        Assert.assertNull(validationError);

        cm = new CassandraMigration();
        cm.setLocations(scriptsLocations);
        cm.setKeyspaceConfig(getKeyspace());

        cm.validate();

        cm = new CassandraMigration();
        cm.setLocations(new String[] { "migration/integ/java" });
        cm.setKeyspaceConfig(getKeyspace());

        try {
            cm.validate();
            Assert.fail("The expected CassandraMigrationException was not raised");
        } catch (CassandraMigrationException e) {
            Assert.assertTrue("expected CassandraMigrationException", true);
        }
    }

    @Test
    public void testValidateWithSession() {
        // apply migration scripts
        String[] scriptsLocations = { "migration/integ", "migration/integ/java" };
        Session session = getSession();
        CassandraMigration cm = new CassandraMigration();
        cm.setLocations(scriptsLocations);
        cm.setKeyspaceConfig(getKeyspace());
        cm.migrate(session);

        MigrationInfoService infoService = cm.info(session);
        String validationError = infoService.validate();
        Assert.assertNull(validationError);

        cm = new CassandraMigration();
        cm.setLocations(scriptsLocations);
        cm.setKeyspaceConfig(getKeyspace());

        cm.validate(session);

        cm = new CassandraMigration();
        cm.setLocations(new String[] { "migration/integ/java" });
        cm.setKeyspaceConfig(getKeyspace());

        try {
            cm.validate(session);
            Assert.fail("The expected CassandraMigrationException was not raised");
        } catch (CassandraMigrationException e) {
        }

        Assert.assertFalse(session.isClosed());
    }

    @Test
    public void testBaseLine() {
        String[] scriptsLocations = {"migration/integ", "migration/integ/java"};
        CassandraMigration cm = new CassandraMigration();
        cm.setLocations(scriptsLocations);
        cm.setKeyspaceConfig(getKeyspace());
        cm.baseline();

        SchemaVersionDAO schemaVersionDAO = new SchemaVersionDAO(getSession(), getKeyspace(), MigrationVersion.Companion.getCURRENT().getTable());
        AppliedMigration baselineMarker = schemaVersionDAO.getBaselineMarker();
        assertThat(baselineMarker.getVersion(), is(MigrationVersion.Companion.fromVersion("1")));
    }

    @Test
    public void testBaseLineWithSession() {
        String[] scriptsLocations = {"migration/integ", "migration/integ/java"};
        Session session = getSession();
        CassandraMigration cm = new CassandraMigration();
        cm.setLocations(scriptsLocations);
        cm.setKeyspaceConfig(getKeyspace());
        cm.baseline(session);

        SchemaVersionDAO schemaVersionDAO = new SchemaVersionDAO(getSession(), getKeyspace(), MigrationVersion.Companion.getCURRENT().getTable());
        AppliedMigration baselineMarker = schemaVersionDAO.getBaselineMarker();
        assertThat(baselineMarker.getVersion(), is(MigrationVersion.Companion.fromVersion("1")));
    }

    @Test(expected = CassandraMigrationException.class)
    public void testBaseLineWithMigrations() {
        String[] scriptsLocations = { "migration/integ", "migration/integ/java" };
        CassandraMigration cm = new CassandraMigration();
        cm.setLocations(scriptsLocations);
        cm.setKeyspaceConfig(getKeyspace());
        cm.migrate();

        cm = new CassandraMigration();
        cm.setLocations(scriptsLocations);
        cm.setKeyspaceConfig(getKeyspace());
        cm.baseline();
    }

    @Test(expected = CassandraMigrationException.class)
    public void testBaseLineWithMigrationsWithSession() {
        String[] scriptsLocations = { "migration/integ", "migration/integ/java" };
        Session session = getSession();
        CassandraMigration cm = new CassandraMigration();
        cm.setLocations(scriptsLocations);
        cm.setKeyspaceConfig(getKeyspace());
        cm.migrate(session);

        cm = new CassandraMigration();
        cm.setLocations(scriptsLocations);
        cm.setKeyspaceConfig(getKeyspace());
        cm.baseline(session);
    }

    static boolean runCmdTestCompleted = false;
    static boolean runCmdTestSuccess = false;

    @Test
    public void runCmdTest() throws IOException, InterruptedException {
        String shell = "java -jar"
                + " -Dcassandra.migration.scripts.locations=filesystem:target/test-classes/migration/integ"
                + " -Dcassandra.migration.cluster.contactpoints=" + CASSANDRA_CONTACT_POINT
                + " -Dcassandra.migration.cluster.port=" + CASSANDRA_PORT
                + " -Dcassandra.migration.cluster.username=" + CASSANDRA_USERNAME
                + " -Dcassandra.migration.cluster.password=" + CASSANDRA_PASSWORD
                + " -Dcassandra.migration.keyspace.name=" + CASSANDRA_KEYSPACE
                + " target/*-jar-with-dependencies.jar" + " migrate";
        ProcessBuilder builder;
        if (isWindows()) {
            throw new IllegalStateException();
        } else {
            builder = new ProcessBuilder("bash", "-c", shell);
        }
        builder.redirectErrorStream(true);
        final Process process = builder.start();

        watch(process);

        while (!runCmdTestCompleted)
            Thread.sleep(1000L);

        assertThat(runCmdTestSuccess, is(true));
    }

    private static void watch(final Process process) {
        new Thread(new Runnable() {
            public void run() {
                BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                try {
                    while ((line = input.readLine()) != null) {
                        if (line.contains("Successfully applied 3 migration(s)"))
                            runCmdTestSuccess = true;
                        System.out.println(line);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                runCmdTestCompleted = true;
            }
        }).start();
    }

    private boolean isWindows() {
        return (System.getProperty("os.name").toLowerCase()).contains("win");
    }
}
