package com.buscai.backend.book

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource
import kotlin.test.assertTrue

// Confirma que a migration V1 (backend/src/main/resources/db/migration/V1__book_bookversion_chunk.sql)
// aplica sem erro contra um Postgres real com a extensão pgvector — o contextLoads com H2
// (BackendApplicationTests) não roda essa migration (H2 não suporta pgvector/HNSW, ver
// src/test/resources/application.yml).
@Testcontainers
@ActiveProfiles("testcontainers")
@SpringBootTest
class FlywayMigrationIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16"))

        @DynamicPropertySource
        @JvmStatic
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired
    lateinit var dataSource: DataSource

    @Test
    fun `migration V1 cria as tabelas book, book_version e chunk com a extensao vector e o indice HNSW`() {
        dataSource.connection.use { connection ->
            val tableNames = mutableSetOf<String>()
            connection.metaData.getTables(null, "public", "%", arrayOf("TABLE")).use { resultSet ->
                while (resultSet.next()) {
                    tableNames += resultSet.getString("TABLE_NAME")
                }
            }
            assertTrue(
                tableNames.containsAll(setOf("book", "book_version", "chunk")),
                "esperava as tabelas book, book_version e chunk, encontrou: $tableNames",
            )

            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "select extname from pg_extension where extname = 'vector'",
                    ).use { resultSet ->
                        assertTrue(resultSet.next(), "extensão vector deveria estar instalada")
                    }

                statement
                    .executeQuery(
                        "select indexdef from pg_indexes where tablename = 'chunk' and indexname = 'idx_chunk_embedding_hnsw'",
                    ).use { resultSet ->
                        assertTrue(resultSet.next(), "índice HNSW em chunk.embedding deveria existir")
                        assertTrue(resultSet.getString("indexdef").contains("hnsw", ignoreCase = true))
                    }
            }
        }
    }
}
