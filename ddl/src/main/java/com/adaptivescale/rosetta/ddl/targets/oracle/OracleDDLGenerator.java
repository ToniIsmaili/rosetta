package com.adaptivescale.rosetta.ddl.targets.oracle;

import com.adaptivescale.rosetta.common.annotations.RosettaModule;
import com.adaptivescale.rosetta.common.models.Column;
import com.adaptivescale.rosetta.common.models.Database;
import com.adaptivescale.rosetta.common.models.ForeignKey;
import com.adaptivescale.rosetta.common.models.Table;
import com.adaptivescale.rosetta.common.types.RosettaModuleTypes;
import com.adaptivescale.rosetta.ddl.DDL;
import com.adaptivescale.rosetta.ddl.change.model.ColumnChange;
import com.adaptivescale.rosetta.ddl.change.model.ForeignKeyChange;
import com.adaptivescale.rosetta.ddl.targets.ColumnSQLDecoratorFactory;
import com.adaptivescale.rosetta.ddl.utils.TemplateEngine;
import lombok.extern.slf4j.Slf4j;

import java.sql.DatabaseMetaData;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RosettaModule(
        name = "oracle",
        type = RosettaModuleTypes.DDL_GENERATOR
)
public class OracleDDLGenerator implements DDL {

    private final static String TABLE_CREATE_TEMPLATE = "oracle/table/create";

    private final static String TABLE_ALTER_TEMPLATE = "oracle/table/alter";

    private final static String TABLE_ALTER_DROP_PRIMARY_KEY_TEMPLATE = "oracle/table/alter_drop_primary_key";

    private final static String TABLE_ALTER_ADD_PRIMARY_KEY_TEMPLATE = "oracle/table/alter_add_primary_key";

    private final static String TABLE_DROP_TEMPLATE = "oracle/table/drop";

    private final static String SCHEMA_CREATE_TEMPLATE = "oracle/schema/create";

    private final static String FOREIGN_KEY_CREATE_TEMPLATE = "oracle/foreignkey/create";

    private final static String FOREIGN_KEY_DROP_TEMPLATE = "oracle/foreignkey/drop";

    private final static String COLUMN_ADD_TEMPLATE = "oracle/column/add";

    private final static String COLUMN_ALTER_TYPE_TEMPLATE = "oracle/column/alter_column_type";

    private final static String COLUMN_ALTER_NULL_TEMPLATE = "oracle/column/alter_column_null";

    private final static String COLUMN_DROP_TEMPLATE = "oracle/column/drop";

    private final ColumnSQLDecoratorFactory columnSQLDecoratorFactory = new OracleColumnDecoratorFactory();

    @Override
    public String createColumn(Column column) {
        return columnSQLDecoratorFactory.decoratorFor(column).expressSQl();
    }

    @Override
    public String createTable(Table table, boolean dropTableIfExists) {
        Map<String, Object> createParams = new HashMap<>();

        List<String> definitions = table.getColumns().stream().map(this::createColumn).collect(Collectors.toList());

        Optional<String> primaryKeysForTable = createPrimaryKeysForTable(table);
        primaryKeysForTable.ifPresent(definitions::add);
        String definitionAsString = String.join(", ", definitions);

        StringBuilder stringBuilder = new StringBuilder();
        if (dropTableIfExists) {
            stringBuilder.append(dropTable(table));
        }

        createParams.put("schemaName", table.getSchema());
        createParams.put("tableName", table.getName());
        createParams.put("tableCode", definitionAsString);
        stringBuilder.append(TemplateEngine.process(TABLE_CREATE_TEMPLATE, createParams));

        return stringBuilder.toString();
    }

    @Override
    public String createDatabase(Database database, boolean dropTableIfExists) {
        StringBuilder stringBuilder = new StringBuilder();

        Set<String> schemas = database.getTables().stream().map(Table::getSchema).filter(s -> s != null && !s.isEmpty()).collect(Collectors.toSet());
        if (!schemas.isEmpty()) {
            stringBuilder.append(
                    schemas
                            .stream()
                            .map(this::createSchema)
                            .collect(Collectors.joining())
            );
            stringBuilder.append("\r");
        }

        stringBuilder.append(database.getTables()
                .stream()
                .map(table -> createTable(table, dropTableIfExists))
                .collect(Collectors.joining("\r\r")));

        String foreignKeys = database
                .getTables()
                .stream()
                .map(this::foreignKeys)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.joining("\r"));

        if (!foreignKeys.isEmpty()) {
            stringBuilder.append("\r").append(foreignKeys).append("\r");
        }

        return stringBuilder.toString();
    }

    @Override
    public String createForeignKey(ForeignKey foreignKey) {
        Map<String, Object> params = new HashMap<>();
        params.put("schemaName", foreignKey.getSchema());
        params.put("tableName", foreignKey.getTableName());
        params.put("foreignkeyColumn", foreignKey.getColumnName());
        params.put("primaryTableSchema", foreignKey.getPrimaryTableSchema());
        params.put("primaryTableName", foreignKey.getPrimaryTableName());
        params.put("foreignKeyPrimaryColumnName", foreignKey.getPrimaryColumnName());
        params.put("foreignkeyName", foreignKey.getName());
        return TemplateEngine.process(FOREIGN_KEY_CREATE_TEMPLATE, params);
    }

    @Override
    public String alterColumn(ColumnChange change) {
        Column actual = change.getActual();
        Column expected = change.getExpected();
        Map<String, Object> params = new HashMap<>();
        params.put("schemaName", change.getTable().getSchema());
        params.put("tableName", change.getTable().getName());
        params.put("columnName", expected.getName());

        if (!Objects.equals(expected.getTypeName(), actual.getTypeName())) {
            params.put("dataType", expected.getTypeName());
            return TemplateEngine.process(COLUMN_ALTER_TYPE_TEMPLATE, params);
        }

        if (!Objects.equals(expected.isNullable(), actual.isNullable())) {
            if (expected.isNullable()) {
                params.put("nullDefinition", "DROP NOT NULL");
                return TemplateEngine.process(COLUMN_ALTER_NULL_TEMPLATE, params);
            } else {
                params.put("nullDefinition", "SET NOT NULL");
                return TemplateEngine.process(COLUMN_ALTER_NULL_TEMPLATE, params);
            }
        }

        log.info("No action taken for changes detected in column: {}.{}.{}", change.getTable().getSchema(),
                change.getTable().getName(),
                expected.getName());

        return "";
    }

    @Override
    public String dropColumn(ColumnChange change) {
        Table table = change.getTable();
        Column actual = change.getActual();

        Map<String, Object> params = new HashMap<>();
        params.put("schemaName", table.getSchema());
        params.put("tableName", table.getName());
        params.put("columnName", actual.getName());
        return TemplateEngine.process(COLUMN_DROP_TEMPLATE, params);
    }

    @Override
    public String addColumn(ColumnChange change) {
        Table table = change.getTable();
        Column expected = change.getExpected();

        Map<String, Object> params = new HashMap<>();
        params.put("schemaName", table.getSchema());
        params.put("tableName", table.getName());
        params.put("columnDefinition", columnSQLDecoratorFactory.decoratorFor(expected).expressSQl());
        return TemplateEngine.process(COLUMN_ADD_TEMPLATE, params);
    }

    @Override
    public String dropTable(Table actual) {
        Map<String, Object> params = new HashMap<>();
        params.put("schemaName", actual.getSchema());
        params.put("tableName", actual.getName());
        return TemplateEngine.process(TABLE_DROP_TEMPLATE, params);
    }

    @Override
    public String alterForeignKey(ForeignKeyChange change) {
        return dropForeignKey(change.getActual()) + "\r" + createForeignKey(change.getExpected());
    }

    @Override
    public String dropForeignKey(ForeignKey actual) {
        Map<String, Object> params = new HashMap<>();
        params.put("schemaName", actual.getSchema());
        params.put("tableName", actual.getTableName());
        params.put("foreignkeyName", actual.getName());
        return TemplateEngine.process(FOREIGN_KEY_DROP_TEMPLATE, params);
    }

    @Override
    public String alterTable(Table expected, Table actual) {
        boolean doesPKExist = actual.getColumns().stream().map(Column::isPrimaryKey).reduce((aBoolean, aBoolean2) -> aBoolean || aBoolean2).orElse(false);
        boolean doWeNeedToCreatePk = expected.getColumns().stream().map(Column::isPrimaryKey).reduce((aBoolean, aBoolean2) -> aBoolean || aBoolean2).orElse(false);

        Map<String, Object> params = new HashMap<>();
        StringBuilder stringBuilder = new StringBuilder();

        if (doesPKExist) {
            params.put("schemaName", expected.getSchema());
            params.put("tableName", tableNameWithSchema(expected));
            stringBuilder.append(
                    TemplateEngine.process(TABLE_ALTER_DROP_PRIMARY_KEY_TEMPLATE, params)
            );
        }

        if (doWeNeedToCreatePk) {
            Optional<String> primaryKeysForTable = createPrimaryKeysForTable(expected);
            if (primaryKeysForTable.isPresent()) {
                params.put("schemaName", expected.getSchema());
                params.put("tableName", tableNameWithSchema(expected));
                params.put("primaryKeyDefinition", primaryKeysForTable.get());
                stringBuilder.append(
                        TemplateEngine.process(TABLE_ALTER_ADD_PRIMARY_KEY_TEMPLATE, params)
                );
            }
        }

        return stringBuilder.toString();
    }

    private Optional<String> createPrimaryKeysForTable(Table table) {
        List<String> primaryKeys = table
                .getColumns()
                .stream()
                .filter(Column::isPrimaryKey)
                .sorted((o1, o2) -> o1.getPrimaryKeySequenceId() < o2.getPrimaryKeySequenceId() ? -1 : 1)
                .map(Column::getName)
                .map(it -> "\"" + it + "\"")
                .collect(Collectors.toList());

        if (primaryKeys.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of("PRIMARY KEY (" + String.join(", ", primaryKeys) + ")");
    }

    private Optional<String> foreignKeys(Table table) {
        String result = table.getColumns().stream()
                .filter(column -> column.getForeignKeys() != null && !column.getForeignKeys().isEmpty())
                .map(this::createForeignKeys).collect(Collectors.joining());

        return result.isEmpty() ? Optional.empty() : Optional.of(result);
    }

    private String createForeignKeys(Column column) {
        return column.getForeignKeys().stream().map(this::createForeignKey).collect(Collectors.joining());
    }

    protected String foreignKeyDeleteRule(ForeignKey foreignKey) {
        if (foreignKey.getDeleteRule() == null || foreignKey.getDeleteRule().isEmpty()) {
            return null;
        }
        switch (Integer.parseInt(foreignKey.getDeleteRule())) {
            case DatabaseMetaData.importedKeyCascade:
                return "ON DELETE CASCADE";
            case DatabaseMetaData.importedKeySetNull:
                return "ON DELETE SET NULL";
            case DatabaseMetaData.importedKeyNoAction:
                return "ON DELETE NO ACTION";
            case DatabaseMetaData.importedKeySetDefault:
            case DatabaseMetaData.importedKeyInitiallyDeferred:
            case DatabaseMetaData.importedKeyInitiallyImmediate:
            case DatabaseMetaData.importedKeyNotDeferrable:
            default:
                return null;
        }
    }

    private String tableNameWithSchema(Table table) {
        StringBuilder builder = new StringBuilder();
        builder.append(table.getName());
        return builder.toString();
    }

    private String createSchema(String schema) {
        Map<String, Object> params = new HashMap<>();
        params.put("schemaName", schema);
        return TemplateEngine.process(SCHEMA_CREATE_TEMPLATE, params);
    }

}
