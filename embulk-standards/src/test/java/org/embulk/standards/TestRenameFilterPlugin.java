package org.embulk.standards;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.embulk.EmbulkTestRuntime;
import org.embulk.config.ConfigException;
import org.embulk.config.ConfigSource;
import org.embulk.config.TaskSource;
import org.embulk.spi.Column;
import org.embulk.spi.FilterPlugin;
import org.embulk.spi.Exec;
import org.embulk.spi.Schema;
import org.embulk.spi.SchemaConfigException;
import org.embulk.standards.RenameFilterPlugin.PluginTask;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.HashMap;

import static org.embulk.spi.type.Types.STRING;
import static org.embulk.spi.type.Types.TIMESTAMP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestRenameFilterPlugin
{
    @Rule
    public EmbulkTestRuntime runtime = new EmbulkTestRuntime();

    private final Schema SCHEMA = Schema.builder()
            .add("_c0", STRING)
            .add("_c1", TIMESTAMP)
            .build();

    private RenameFilterPlugin filter;

    @Before
    public void createFilter()
    {
        filter = new RenameFilterPlugin();
    }

    @Test
    public void checkDefaultValues()
    {
        PluginTask task = Exec.newConfigSource().loadConfig(PluginTask.class);
        assertTrue(task.getRenameMap().isEmpty());
    }

    @Test
    public void throwSchemaConfigExceptionIfColumnNotFound()
    {
        ConfigSource pluginConfig = Exec.newConfigSource()
                .set("columns", ImmutableMap.of("not_found", "any_name"));

        try {
            filter.transaction(pluginConfig, SCHEMA, new FilterPlugin.Control() {
                public void run(TaskSource task, Schema schema) { }
            });
            fail();
        } catch (Throwable t) {
            assertTrue(t instanceof SchemaConfigException);
        }
    }

    @Test
    public void checkRenaming()
    {
        ConfigSource pluginConfig = Exec.newConfigSource()
                .set("columns", ImmutableMap.of("_c0", "_c0_new"));

        filter.transaction(pluginConfig, SCHEMA, new FilterPlugin.Control() {
            @Override
            public void run(TaskSource task, Schema newSchema)
            {
                // _c0 -> _c0_new
                Column old0 = SCHEMA.getColumn(0);
                Column new0 = newSchema.getColumn(0);
                assertEquals("_c0_new", new0.getName());
                assertEquals(old0.getType(), new0.getType());

                // _c1 is not changed
                Column old1 = SCHEMA.getColumn(1);
                Column new1 = newSchema.getColumn(1);
                assertEquals("_c1", new1.getName());
                assertEquals(old1.getType(), new1.getType());
            }
        });
    }

    @Test
    public void checkConfigExceptionIfUnknownStringTypeOfRenamingOperator()
    {
        // A simple string shouldn't come as a renaming rule.
        ConfigSource pluginConfig = Exec.newConfigSource()
                .set("rules", ImmutableList.of("string_rule"));

        try {
            filter.transaction(pluginConfig, SCHEMA, new FilterPlugin.Control() {
                public void run(TaskSource task, Schema schema) { }
            });
            fail();
        } catch (Throwable t) {
            assertTrue(t instanceof ConfigException);
        }
    }

    @Test
    public void checkConfigExceptionIfUnknownListTypeOfRenamingOperator()
    {
        // A list [] shouldn't come as a renaming rule.
        ConfigSource pluginConfig = Exec.newConfigSource()
                .set("rules", ImmutableList.of(ImmutableList.of("listed_operator1", "listed_operator2")));

        try {
            filter.transaction(pluginConfig, SCHEMA, new FilterPlugin.Control() {
                public void run(TaskSource task, Schema schema) { }
            });
            fail();
        } catch (Throwable t) {
            assertTrue(t instanceof ConfigException);
        }
    }

    @Test
    public void checkConfigExceptionIfUnknownRenamingOperatorName()
    {
        ConfigSource pluginConfig = Exec.newConfigSource()
                .set("rules", ImmutableList.of(ImmutableMap.of("rule", "some_unknown_renaming_operator")));

        try {
            filter.transaction(pluginConfig, SCHEMA, new FilterPlugin.Control() {
                public void run(TaskSource task, Schema schema) { }
            });
            fail();
        } catch (Throwable t) {
            assertTrue(t instanceof ConfigException);
        }
    }

    @Test
    public void checkSegmentRule1()
    {
        final String original[] = { "CamelCase",  "camelCase",  "all", "UPSCase"    };
        final String expected[] = { "camel_case", "camel_case", "all", "u_p_s_case" };
        checkSegmentRuleInternal(original, expected, false);
    }

    @Test
    public void checkSegmentRule2()
    {
        final String original[] = { "ProductUPCBarcode",   "ABCDEF", "ProductBarcode"  };
        final String expected[] = { "product_upc_barcode", "abcdef", "product_barcode" };
        checkSegmentRuleInternal(original, expected, true);
    }

    private void checkSegmentRuleInternal(
            final String original[],
            final String expected[],
            final boolean detectUpperCaseAcronyms)
    {
        HashMap<String, Object> parameters = new HashMap<>();
        parameters.put("rule", "segment");
        parameters.put("detect_upper_case_acronyms", detectUpperCaseAcronyms);
        ConfigSource config = Exec.newConfigSource().set("rules",
                ImmutableList.of(ImmutableMap.copyOf(parameters)));

        renameAndCheckSchema(config, original, expected);
    }

    private Schema makeSchema(final String columnNames[])
    {
        Schema.Builder builder = new Schema.Builder();
        for (String columnName : columnNames) {
            builder.add(columnName, STRING);
        }
        return builder.build();
    }

    private void renameAndCheckSchema(ConfigSource config,
                                      final String original[],
                                      final String expected[])
    {
        final Schema originalSchema = makeSchema(original);
        filter.transaction(config, originalSchema, new FilterPlugin.Control() {
            @Override
            public void run(TaskSource task, Schema renamedSchema)
            {
                assertEquals(originalSchema.getColumnCount(), renamedSchema.getColumnCount());
                assertEquals(expected.length, renamedSchema.getColumnCount());
                for (int i = 0; i < renamedSchema.getColumnCount(); ++i) {
                    assertEquals(originalSchema.getColumnType(i), renamedSchema.getColumnType(i));
                    assertEquals(expected[i], renamedSchema.getColumnName(i));
                }
            }
        });
    }
}
