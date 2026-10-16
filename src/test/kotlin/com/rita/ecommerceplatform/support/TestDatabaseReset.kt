package com.rita.ecommerceplatform.support

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * Wipes the test database and re-runs every migration once per test run, so each run starts
 * from a known state (no customers, roles seeded, seed admin in MUST_CHANGE).
 * Refuses to touch any database whose name does not end in _test, so a wrong TEST_DB_URL
 * stops the tests instead of wiping ecommerce_dev or another product's database.
 */
@TestConfiguration
class TestDatabaseReset {

    @Bean
    fun cleanThenMigrate(): FlywayMigrationStrategy = FlywayMigrationStrategy { flyway ->
        val databaseName = flyway.configuration.dataSource.connection.use { it.catalog }
        check(databaseName.endsWith("_test")) {
            "Refusing to wipe database '$databaseName': tests only run against a database whose name ends in _test (check TEST_DB_URL)"
        }
        flyway.clean()
        flyway.migrate()
    }
}
