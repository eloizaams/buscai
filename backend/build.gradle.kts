plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.jpa") version "2.3.21"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "com.buscai"
version = "0.0.1-SNAPSHOT"
description = "Backend RAG para Q&A sobre livros"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    runtimeOnly("org.postgresql:postgresql")
    implementation("com.pgvector:pgvector:0.1.6")
    // Mapeamento JPA/Hibernate da coluna `vector` do pgvector: a partir do Hibernate 6.4+ (aqui
    // 7.4.1.Final, versão gerenciada pelo BOM do Spring Boot 4.1.0 — confirmado em
    // https://github.com/pgvector/pgvector-java#hibernate) o módulo `hibernate-vector` substitui
    // o uso direto de `com.pgvector.PGvector` em entidades: habilita `@JdbcTypeCode(SqlTypes.VECTOR)`
    // + `@Array(length = n)` num campo `FloatArray`. `com.pgvector:pgvector` acima continua só como
    // possível dependência futura para JDBC cru (busca/chat, fora do escopo desta task).
    implementation("org.hibernate.orm:hibernate-vector")
    // Schema gerenciado por migration (ddl-auto: validate) — ver plan.md da feature de ingestão.
    // No Spring Boot 4 a autoconfiguração do Flyway saiu do spring-boot-autoconfigure e virou o
    // módulo spring-boot-flyway, só ativado via este starter (spring-boot-starter-flyway) — ele já
    // traz flyway-core transitivamente. flyway-database-postgresql continua explícito (dialeto).
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    // ADR-0002: extração de PDF via Apache PDFBox (variante JVM, não a -android).
    implementation("org.apache.pdfbox:pdfbox:3.0.7")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Só para o smoke test de contexto (contextLoads) subir sem depender do Postgres do
    // Neon (ADR-0006). Testes reais de repositório usam Testcontainers a partir da Fase 3.
    testRuntimeOnly("com.h2database:h2")
    // Testes que precisam de Postgres real com pgvector (migration V1 em diante). Nomes de
    // artefato do Testcontainers 2.x (renomeados com prefixo testcontainers-, ver BOM importado
    // por spring-boot-dependencies 4.1.0 — testcontainers.version=2.0.5).
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
