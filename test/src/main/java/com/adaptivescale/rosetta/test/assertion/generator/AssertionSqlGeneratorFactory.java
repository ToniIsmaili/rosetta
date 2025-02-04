package com.adaptivescale.rosetta.test.assertion.generator;

import com.adaptivescale.rosetta.common.models.input.Connection;
import com.adaptivescale.rosetta.test.assertion.AssertionSqlGenerator;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AssertionSqlGeneratorFactory {

    public static AssertionSqlGenerator generatorFor(Connection connection) {
        if ("bigquery".equals(connection.getDbType())) {
            return new BigQueryAssertionSqlGenerator();
        } else if ("mysql".equals(connection.getDbType())) {
            return new DefaultAssertionSqlGenerator();
        } else if ("snowflake".equals(connection.getDbType())) {
            return new SnowflakeAssertionSqlGenerator();
        } else if ("postgres".equals(connection.getDbType())) {
            return new DefaultAssertionSqlGenerator();
        } else if ("kinetica".equals(connection.getDbType())) {
            return new DefaultAssertionSqlGenerator();
        } else if ("spanner".equals(connection.getDbType())) {
            return new SpannerAssertionSqlGenerator();
        } else if ("sqlserver".equals(connection.getDbType())) {
            return new DefaultAssertionSqlGenerator();
        } else if ("oracle".equals(connection.getDbType())) {
            return new DefaultAssertionSqlGenerator();
        } else if ("db2".equals(connection.getDbType())) {
            return new DefaultAssertionSqlGenerator();
        } else if ("redshift".equals(connection.getDbType())) {
            return new RedshiftAssertionSqlGenerator();
        }
        String msg = String.format("Database type '%s' not supported for assertion testing.", connection.getDbType());
        log.error(msg);
        throw new RuntimeException(msg);
    }
}
