package com.buscai.backend

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

// Sobe com H2 em memória (autoconfigurado pelo Spring por não haver DATABASE_URL no ambiente
// de teste) só para validar o boot do contexto. Sem Postgres real (ADR-0006: Neon é externo),
// então não valida nada específico de pgvector — isso entra via Testcontainers na Fase 3.
@SpringBootTest
class BackendApplicationTests {
    @Test
    fun contextLoads() {
    }
}
