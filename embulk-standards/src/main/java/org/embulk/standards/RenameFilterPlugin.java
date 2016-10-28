package org.embulk.standards;

import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigException;
import org.embulk.config.ConfigSource;
import org.embulk.config.Task;
import org.embulk.config.TaskSource;
import org.embulk.spi.Column;
import org.embulk.spi.FilterPlugin;
import org.embulk.spi.PageOutput;
import org.embulk.spi.Schema;

import com.google.common.base.CaseFormat;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import java.util.List;
import java.util.Locale;
import java.util.Map;


public class RenameFilterPlugin
        implements FilterPlugin
{
    public interface PluginTask
            extends Task
    {
        @Config("columns")
        @ConfigDefault("{}")
        Map<String, String> getRenameMap();

        @Config("rules")
        @ConfigDefault("[]")
        List<ConfigSource> getRulesList();
    }

    @Override
    public void transaction(ConfigSource config, Schema inputSchema,
                            FilterPlugin.Control control)
    {
        PluginTask task = config.loadConfig(PluginTask.class);
        Map<String, String> renameMap = task.getRenameMap();
        List<ConfigSource> rulesList = task.getRulesList();

        // Check if the given column in "columns" exists or not.
        for (String columnName : renameMap.keySet()) {
            inputSchema.lookupColumn(columnName); // throws SchemaConfigException
        }

        // Rename by "columns": to be applied before "rules".
        Schema.Builder builder = Schema.builder();
        for (Column column : inputSchema.getColumns()) {
            String name = column.getName();
            if (renameMap.containsKey(name)) {
                name = renameMap.get(name);
            }
            builder.add(name, column.getType());
        }
        Schema intermediateSchema = builder.build();

        // Rename by "rules".
        Schema outputSchema = intermediateSchema;
        for (ConfigSource rule : rulesList) {
            outputSchema = applyRule(rule, intermediateSchema);
            intermediateSchema = outputSchema;
        }

        control.run(task.dump(), outputSchema);
    }

    @Override
    public PageOutput open(TaskSource taskSource, Schema inputSchema,
                           Schema outputSchema, PageOutput output)
    {
        return output;
    }


    // Extending Task is required to be deserialized with ConfigSource.loadConfig()
    // although this Rule is not really a Task.
    // TODO(dmikurube): Revisit this to consider how not to extend Task for this.
    private interface Rule
            extends Task
    {
        @Config("rule")
        String getRule();
    }

    private interface SegmentRule extends Rule {
        @Config("detect_upper_case_acronyms")
        @ConfigDefault("false")
        boolean getDetectUpperCaseAcronyms();
    }

    private Schema applyRule(ConfigSource ruleConfig, Schema inputSchema) throws ConfigException
    {
        Rule rule = ruleConfig.loadConfig(Rule.class);
        switch (rule.getRule()) {
        case "segment":
            return applySegmentRule(inputSchema, ruleConfig.loadConfig(SegmentRule.class));
        default:
            throw new ConfigException("Renaming rule \"" +rule+ "\" is unknown");
        }
    }

    private Schema applySegmentRule(Schema inputSchema, SegmentRule rule) {
        final boolean detectUpperCaseAcronyms = rule.getDetectUpperCaseAcronyms();
        Schema.Builder builder = Schema.builder();
        for (Column column : inputSchema.getColumns()) {
            String from = column.getName();

            if (detectUpperCaseAcronyms) {
                StringBuilder fromBuilder = new StringBuilder(from);
                int upperStarts = -1;
                for (int i = 0; i < fromBuilder.length(); ++i) {
                    char c = fromBuilder.charAt(i);
                    if (Character.isUpperCase(c) && upperStarts < 0) {
                        upperStarts = i;
                    }
                    if (!Character.isUpperCase(c) && upperStarts >= 0) {
                        fromBuilder.replace(upperStarts, i-1,
                                            fromBuilder.substring(upperStarts+1, i-1).toLowerCase(Locale.ENGLISH));
                        upperStarts = -1;
                    }
                }
                from = fromBuilder.toString();
            }

            //builder.add(CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, from), column.getType());
            builder.add(from, column.getType());
        }
        return builder.build();
    }
}
