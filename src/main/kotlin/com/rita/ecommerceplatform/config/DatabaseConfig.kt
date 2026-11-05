package com.rita.ecommerceplatform.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessException
import org.springframework.dao.support.PersistenceExceptionTranslator
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator
import java.sql.SQLException
import javax.sql.DataSource

@Configuration
@EnableConfigurationProperties(DatasourceProperties::class)
class DatabaseConfig(
    private val datasourceProperties: DatasourceProperties,
) {

    @Bean
    fun dataSource(): DataSource {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = datasourceProperties.url
            username = datasourceProperties.username
            password = datasourceProperties.password
            driverClassName = datasourceProperties.driverClassName
            isAutoCommit = datasourceProperties.defaultAutoCommit
            connectionTestQuery = datasourceProperties.hikari.connectionTestQuery
            transactionIsolation = datasourceProperties.hikari.transactionIsolation
            maximumPoolSize = datasourceProperties.hikari.maximumPoolSize
            minimumIdle = datasourceProperties.hikari.minimumIdle
            poolName = "ShoporahHikariPool"
        }
        return HikariDataSource(hikariConfig)
    }

    /**
     * Differs from the template on purpose. The proxy puts JDBI on the connection of the surrounding @Transactional,
     * so a failed checkout rolls back every statement. CheckoutRollbackTest fails without it.
     */
    @Bean
    fun jdbi(dataSource: DataSource): Jdbi =
        Jdbi.create(TransactionAwareDataSourceProxy(dataSource)).apply {
            installPlugin(KotlinPlugin())
        }

    /**
     * JDBI wraps a unique-constraint failure in its own exception. @Repository classes turn it back into Spring's
     * DuplicateKeyException here, which is what the services catch to answer a duplicate or a repeat request.
     */
    @Bean
    fun jdbiExceptionTranslator(dataSource: DataSource): PersistenceExceptionTranslator {
        val translator = SQLErrorCodeSQLExceptionTranslator(dataSource)
        return PersistenceExceptionTranslator { ex: RuntimeException ->
            (ex.cause as? SQLException)?.let { translator.translate("JDBI", null, it) as DataAccessException? }
        }
    }
}
