/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.plugin.memory;

import com.google.common.collect.ImmutableSet;
import io.prestosql.Session;
import io.prestosql.execution.QueryStats;
import io.prestosql.spi.connector.QualifiedObjectName;
import io.prestosql.sql.analyzer.FeaturesConfig;
import io.prestosql.testing.MaterializedResult;
import io.prestosql.testing.MaterializedRow;
import io.prestosql.tests.AbstractTestQueryFramework;
import io.prestosql.tests.DistributedQueryRunner;
import io.prestosql.tests.ResultWithQueryId;
import org.intellij.lang.annotations.Language;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.prestosql.SystemSessionProperties.ENABLE_DYNAMIC_FILTERING;
import static io.prestosql.SystemSessionProperties.JOIN_DISTRIBUTION_TYPE;
import static io.prestosql.testing.assertions.Assert.assertEquals;
import static java.lang.String.format;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class TestMemorySmoke
        extends AbstractTestQueryFramework
{
    public TestMemorySmoke()
    {
        super(MemoryQueryRunner::createQueryRunner);
    }

    @Test
    public void testCreateAndDropTable()
    {
        int tablesBeforeCreate = listMemoryTables().size();
        assertUpdate("CREATE TABLE test AS SELECT * FROM tpch.tiny.nation", "SELECT count(*) FROM nation");
        assertEquals(listMemoryTables().size(), tablesBeforeCreate + 1);

        assertUpdate("DROP TABLE test");
        assertEquals(listMemoryTables().size(), tablesBeforeCreate);
    }

    // it has to be RuntimeException as FailureInfo$FailureException is private
    @Test(expectedExceptions = RuntimeException.class, expectedExceptionsMessageRegExp = "line 1:1: Destination table 'memory.default.nation' already exists")
    public void testCreateTableWhenTableIsAlreadyCreated()
    {
        @Language("SQL") String createTableSql = "CREATE TABLE nation AS SELECT * FROM tpch.tiny.nation";
        assertUpdate(createTableSql);
    }

    @Test
    public void testSelect()
    {
        assertUpdate("CREATE TABLE test_select AS SELECT * FROM tpch.tiny.nation", "SELECT count(*) FROM nation");

        assertQuery("SELECT * FROM test_select ORDER BY nationkey", "SELECT * FROM nation ORDER BY nationkey");

        assertQueryResult("INSERT INTO test_select SELECT * FROM tpch.tiny.nation", 25L);

        assertQueryResult("INSERT INTO test_select SELECT * FROM tpch.tiny.nation", 25L);

        assertQueryResult("SELECT count(*) FROM test_select", 75L);
    }

    @Test
    public void testJoinDynamicFilteringNone()
    {
        final long buildSideRowsCount = 15_000L;
        assertQueryResult("SELECT COUNT() FROM orders", buildSideRowsCount);
        assertQueryResult("SELECT COUNT() FROM orders WHERE totalprice < 0", 0L);

        Session session = Session.builder(getSession())
                .setSystemProperty(ENABLE_DYNAMIC_FILTERING, "true")
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, FeaturesConfig.JoinDistributionType.BROADCAST.name())
                .build();
        DistributedQueryRunner runner = (DistributedQueryRunner) getQueryRunner();
        ResultWithQueryId<MaterializedResult> result = runner.executeWithQueryId(session, "SELECT * FROM lineitem JOIN orders " +
                "ON lineitem.orderkey = orders.orderkey AND orders.totalprice < 0");
        assertEquals(result.getResult().getRowCount(), 0);

        // Probe-side is not scanned at all, due to dynamic filtering:
        // Note that because of the Global Dynamic Filter not being applied in the Memory Connector, we have changed this test
        QueryStats stats = runner.getCoordinator().getQueryManager().getFullQueryInfo(result.getQueryId()).getQueryStats();
        Set rowsRead = stats.getOperatorSummaries()
                .stream()
                .filter(summary -> summary.getOperatorType().equals("ScanFilterAndProjectOperator"))
                .map(summary -> summary.getInputPositions())
                .collect(toImmutableSet());
        assertEquals(rowsRead, ImmutableSet.of(0L, buildSideRowsCount));
    }

    @Test
    public void testJoinDynamicFilteringSingleValue()
    {
        final long buildSideRowsCount = 15_000L;

        assertQueryResult("SELECT COUNT() FROM orders",
                buildSideRowsCount);
        assertQueryResult("SELECT COUNT() FROM orders WHERE comment = 'nstructions sleep furiously among '",
                1L);
        assertQueryResult("SELECT orderkey FROM orders WHERE comment = 'nstructions sleep furiously among '",
                1L);
        assertQueryResult("SELECT COUNT() FROM lineitem WHERE orderkey = 1",
                6L);

        Session session = Session.builder(getSession())
                .setSystemProperty(ENABLE_DYNAMIC_FILTERING, "true")
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, FeaturesConfig.JoinDistributionType.BROADCAST.name())
                .build();
        DistributedQueryRunner runner = (DistributedQueryRunner) getQueryRunner();
        ResultWithQueryId<MaterializedResult> result = runner.executeWithQueryId(session, "SELECT * FROM lineitem JOIN orders " +
                "ON lineitem.orderkey = orders.orderkey AND orders.comment = 'nstructions sleep furiously among '");
        assertEquals(result.getResult().getRowCount(), 6);

        // Probe-side is dynamically filtered:
        // Note: because the global dynamic filter is not applied in the memory connector, we have changed the assert value for this test
        QueryStats stats = runner.getCoordinator().getQueryManager().getFullQueryInfo(result.getQueryId()).getQueryStats();
        Set rowsRead = stats.getOperatorSummaries()
                .stream()
                .filter(summary -> summary.getOperatorType().equals("ScanFilterAndProjectOperator"))
                .map(summary -> summary.getInputPositions())
                .collect(toImmutableSet());
        assertEquals(rowsRead, ImmutableSet.of(6L, buildSideRowsCount));
    }

    @Test
    public void testSemiJoinDynamicFilteringNone()
    {
        final long buildSideRowsCount = 15_000L;

        Session session = Session.builder(getSession())
                .setSystemProperty(ENABLE_DYNAMIC_FILTERING, "true")
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, FeaturesConfig.JoinDistributionType.BROADCAST.name())
                .build();
        DistributedQueryRunner runner = (DistributedQueryRunner) getQueryRunner();
        ResultWithQueryId<MaterializedResult> result = runner.executeWithQueryId(session,
                "SELECT * FROM lineitem WHERE orderkey IN (SELECT orderkey from orders WHERE totalprice < 0)");
        assertEquals(result.getResult().getRowCount(), 0);

        QueryStats stats = runner.getCoordinator().getQueryManager().getFullQueryInfo(result.getQueryId()).getQueryStats();
        Set rowsRead = stats.getOperatorSummaries()
                .stream()
                .filter(summary -> summary.getOperatorType().equals("ScanFilterAndProjectOperator"))
                .map(summary -> summary.getInputPositions())
                .collect(toImmutableSet());
        assertEquals(rowsRead, ImmutableSet.of(0L, buildSideRowsCount));
    }

    @Test
    public void testSemiJoinDynamicFilteringSingleValue()
    {
        final long buildSideRowsCount = 15_000L;

        Session session = Session.builder(getSession())
                .setSystemProperty(ENABLE_DYNAMIC_FILTERING, "true")
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, FeaturesConfig.JoinDistributionType.BROADCAST.name())
                .build();
        DistributedQueryRunner runner = (DistributedQueryRunner) getQueryRunner();
        ResultWithQueryId<MaterializedResult> result = runner.executeWithQueryId(session,
                "SELECT * FROM lineitem WHERE orderkey IN (SELECT orderkey from orders WHERE orders.comment = 'nstructions sleep furiously among ')");
        assertEquals(result.getResult().getRowCount(), 6);

        QueryStats stats = runner.getCoordinator().getQueryManager().getFullQueryInfo(result.getQueryId()).getQueryStats();
        Set rowsRead = stats.getOperatorSummaries()
                .stream()
                .filter(summary -> summary.getOperatorType().equals("ScanFilterAndProjectOperator"))
                .map(summary -> summary.getInputPositions())
                .collect(toImmutableSet());
        assertEquals(rowsRead, ImmutableSet.of(6L, buildSideRowsCount));
    }

    @Test
    public void testCreateTableWithNoData()
    {
        assertUpdate("CREATE TABLE test_empty (a BIGINT)");
        assertQueryResult("SELECT count(*) FROM test_empty", 0L);
        assertQueryResult("INSERT INTO test_empty SELECT nationkey FROM tpch.tiny.nation", 25L);
        assertQueryResult("SELECT count(*) FROM test_empty", 25L);
    }

    @Test
    public void testCreateFilteredOutTable()
    {
        assertUpdate("CREATE TABLE filtered_out AS SELECT nationkey FROM tpch.tiny.nation WHERE nationkey < 0", "SELECT count(nationkey) FROM nation WHERE nationkey < 0");
        assertQueryResult("SELECT count(*) FROM filtered_out", 0L);
        assertQueryResult("INSERT INTO filtered_out SELECT nationkey FROM tpch.tiny.nation", 25L);
        assertQueryResult("SELECT count(*) FROM filtered_out", 25L);
    }

    @Test
    public void testSelectFromEmptyTable()
    {
        assertUpdate("CREATE TABLE test_select_empty AS SELECT * FROM tpch.tiny.nation WHERE nationkey > 1000", "SELECT count(*) FROM nation WHERE nationkey > 1000");

        assertQueryResult("SELECT count(*) FROM test_select_empty", 0L);
    }

    @Test
    public void testSelectSingleRow()
    {
        assertQuery("SELECT * FROM tpch.tiny.nation WHERE nationkey = 1", "SELECT * FROM nation WHERE nationkey = 1");
    }

    @Test
    public void testSelectColumnsSubset()
    {
        assertQuery("SELECT nationkey, regionkey FROM tpch.tiny.nation ORDER BY nationkey", "SELECT nationkey, regionkey FROM nation ORDER BY nationkey");
    }

    @Test
    public void testCreateSchema()
    {
        assertQueryFails("DROP SCHEMA schema1", "line 1:1: Schema 'memory.schema1' does not exist");
        assertUpdate("CREATE SCHEMA schema1");
        assertQueryFails("CREATE SCHEMA schema1", "line 1:1: Schema 'memory.schema1' already exists");
        assertUpdate("CREATE TABLE schema1.x(t int)");
        assertQueryFails("DROP SCHEMA schema1", "Schema not empty: schema1");
        assertUpdate("DROP TABLE schema1.x");
        assertUpdate("DROP SCHEMA schema1");
        assertQueryFails("DROP SCHEMA schema1", "line 1:1: Schema 'memory.schema1' does not exist");
        assertUpdate("DROP SCHEMA IF EXISTS schema1");
    }

    @Test
    public void testCreateTableInNonDefaultSchema()
    {
        assertUpdate("CREATE SCHEMA schema1");
        assertUpdate("CREATE SCHEMA schema2");

        assertQueryResult("SHOW SCHEMAS", "default", "information_schema", "schema1", "schema2");
        assertUpdate("CREATE TABLE schema1.nation AS SELECT * FROM tpch.tiny.nation WHERE nationkey % 2 = 0", "SELECT count(*) FROM nation WHERE MOD(nationkey, 2) = 0");
        assertUpdate("CREATE TABLE schema2.nation AS SELECT * FROM tpch.tiny.nation WHERE nationkey % 2 = 1", "SELECT count(*) FROM nation WHERE MOD(nationkey, 2) = 1");

        assertQueryResult("SELECT count(*) FROM schema1.nation", 13L);
        assertQueryResult("SELECT count(*) FROM schema2.nation", 12L);
    }

    @Test
    public void testCreateTableAndViewInNotExistSchema()
    {
        int tablesBeforeCreate = listMemoryTables().size();

        assertQueryFails("CREATE TABLE schema3.test_table3 (x date)", "Schema schema3 not found");
        assertQueryFails("CREATE VIEW schema4.test_view4 AS SELECT 123 x", "Schema schema4 not found");
        assertQueryFails("CREATE OR REPLACE VIEW schema5.test_view5 AS SELECT 123 x", "Schema schema5 not found");

        int tablesAfterCreate = listMemoryTables().size();
        assertEquals(tablesBeforeCreate, tablesAfterCreate);
    }

    @Test
    public void testRenameTable()
    {
        assertUpdate("CREATE TABLE test_table_to_be_renamed (a BIGINT)");
        assertQueryFails("ALTER TABLE test_table_to_be_renamed RENAME TO memory.test_schema_not_exist.test_table_renamed", "Schema test_schema_not_exist not found");
        assertUpdate("ALTER TABLE test_table_to_be_renamed RENAME TO test_table_renamed");
        assertQueryResult("SELECT count(*) FROM test_table_renamed", 0L);

        // TODO: rename to a different schema is no longer supported after switching to HetuMetastore
//        assertUpdate("CREATE SCHEMA test_different_schema");
//        assertUpdate("ALTER TABLE test_table_renamed RENAME TO test_different_schema.test_table_renamed");
//        assertQueryResult("SELECT count(*) FROM test_different_schema.test_table_renamed", 0L);
//
//        assertUpdate("DROP TABLE test_different_schema.test_table_renamed");
//        assertUpdate("DROP SCHEMA test_different_schema");
    }

    @Test
    public void testViews()
    {
        @Language("SQL") String query = "SELECT orderkey, orderstatus, totalprice / 2 half FROM orders";

        assertUpdate("CREATE VIEW test_view AS SELECT 123 x");
        assertUpdate("CREATE OR REPLACE VIEW test_view AS " + query);

        assertQueryFails("CREATE TABLE test_view (x date)", "Table \\[default.test_view] already exists");
        assertQueryFails("CREATE VIEW test_view AS SELECT 123 x", "View \\[default.test_view] already exists");

        assertQuery("SELECT * FROM test_view", query);

        assertTrue(computeActual("SHOW TABLES").getOnlyColumnAsSet().contains("test_view"));

        assertUpdate("DROP VIEW test_view");
        assertQueryFails("DROP VIEW test_view", "line 1:1: View 'memory.default.test_view' does not exist");
    }

    private List<QualifiedObjectName> listMemoryTables()
    {
        return getQueryRunner().listTables(getSession(), "memory", "default");
    }

    private void assertQueryResult(@Language("SQL") String sql, Object... expected)
    {
        MaterializedResult rows = computeActual(sql);
        assertEquals(rows.getRowCount(), expected.length);

        for (int i = 0; i < expected.length; i++) {
            MaterializedRow materializedRow = rows.getMaterializedRows().get(i);
            int fieldCount = materializedRow.getFieldCount();
            assertTrue(fieldCount == 1, format("Expected only one column, but got '%d'", fieldCount));
            Object value = materializedRow.getField(0);
            assertEquals(value, expected[i]);
            assertTrue(materializedRow.getFieldCount() == 1);
        }
    }
}
