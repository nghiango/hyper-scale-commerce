package com.hyperscale.commerce.config.datasource

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers
class ReadWriteRoutingIntegrationTest {
  companion object {
    @Container
    @JvmStatic
    val primary = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))

    @Container
    @JvmStatic
    val replica = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))

    private lateinit var primaryDataSource: HikariDataSource
    private lateinit var replicaDataSource: HikariDataSource
    private lateinit var lagTracker: ReplicationLagTracker
    private lateinit var transaction: TransactionTemplate
    private lateinit var jdbc: JdbcTemplate

    @JvmStatic
    @BeforeAll
    fun configureRouting() {
      primaryDataSource = dataSource(primary, "routing-primary")
      replicaDataSource = dataSource(replica, "routing-replica")
      JdbcTemplate(primaryDataSource).execute("CREATE TABLE routing_marker (value TEXT NOT NULL)")
      JdbcTemplate(primaryDataSource).update("INSERT INTO routing_marker VALUES ('PRIMARY')")
      JdbcTemplate(replicaDataSource).execute("CREATE TABLE routing_marker (value TEXT NOT NULL)")
      JdbcTemplate(replicaDataSource).update("INSERT INTO routing_marker VALUES ('REPLICA')")

      lagTracker = ReplicationLagTracker(maxAllowableLagMs = 100)
      val router =
          TransactionRoutingDataSource(lagTracker).apply {
            setTargetDataSources(
                mapOf(
                    DataSourceType.PRIMARY to primaryDataSource,
                    DataSourceType.REPLICA to replicaDataSource,
                ))
            setDefaultTargetDataSource(primaryDataSource)
            afterPropertiesSet()
          }
      val lazyDataSource = LazyConnectionDataSourceProxy(router)
      transaction = TransactionTemplate(DataSourceTransactionManager(lazyDataSource))
      jdbc = JdbcTemplate(lazyDataSource)
    }

    @JvmStatic
    @AfterAll
    fun closePools() {
      primaryDataSource.close()
      replicaDataSource.close()
    }

    private fun dataSource(container: PostgreSQLContainer<*>, poolName: String) =
        HikariDataSource(
            HikariConfig().apply {
              jdbcUrl = container.jdbcUrl
              username = container.username
              password = container.password
              this.poolName = poolName
              maximumPoolSize = 2
            })
  }

  @Test
  fun `write transactions use primary`() {
    transaction.isReadOnly = false

    assertThat(transaction.execute { marker() }).isEqualTo("PRIMARY")
  }

  @Test
  fun `read-only transactions use healthy replica`() {
    lagTracker.recordLag(10)
    transaction.isReadOnly = true

    assertThat(transaction.execute { marker() }).isEqualTo("REPLICA")
  }

  @Test
  fun `read-only transactions fall back to primary when replica lag is high`() {
    lagTracker.recordLag(101)
    transaction.isReadOnly = true

    assertThat(transaction.execute { marker() }).isEqualTo("PRIMARY")
  }

  private fun marker(): String =
      requireNotNull(jdbc.queryForObject("SELECT value FROM routing_marker", String::class.java))
}
