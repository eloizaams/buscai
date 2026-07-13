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

// T11 (`specs/ingestao-pdf/tasks.md`, CA2 `specs/ingestao-pdf/spec.md`): teste de aceite de volume
// (`IngestionServiceVolumeTest`, PDF sintético de ~700 páginas) roda numa JVM de teste isolada, com
// heap restrito. Excluído da task `test` normal (que roda com o heap default da JVM) para não
// reduzir o heap do restante da suíte só por causa deste teste; em vez disso, `check` depende de
// `volumeTest`, então `./gradlew build`/`check` (o comando do CI, ver `.github/workflows/ci.yml`)
// sempre roda os dois — este teste nunca fica marcado `@Tag("slow")`/excluído do CI, só isolado em
// processo próprio.
val volumeTestClassName = "com.buscai.backend.ingestion.IngestionServiceVolumeTest"

tasks.named<Test>("test") {
    filter {
        excludeTestsMatching(volumeTestClassName)
    }
}

val volumeTest =
    tasks.register<Test>("volumeTest") {
        description = "Teste de aceite de volume (T11, CA2) — livro sintético de ~700 páginas, heap restrito."
        group = "verification"
        useJUnitPlatform()
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        filter {
            includeTestsMatching(volumeTestClassName)
        }
        // 256m — como e por que este valor foi escolhido (medido localmente, Docker disponível,
        // reproduzir com `./gradlew volumeTest`):
        //
        // 1. Bisseção manual mostrou que o pipeline ATUAL (batches de `PAGE_EXTRACTION_BATCH_SIZE`
        //    páginas + lotes de chunks) sobe o contexto Spring inteiro (Hibernate, Flyway,
        //    Testcontainers/JDBC) e ingere o livro de 700 páginas com sucesso a partir de ~64m de
        //    heap (48m já estoura `OutOfMemoryError` só no boot do contexto — piso do framework,
        //    nada a ver com o tamanho do livro). 256m dá ~4x de folga sobre esse piso local, para
        //    absorver variação de ambiente (runner do CI, GC, JIT) sem ficar frágil/instável.
        // 2. Reversão deliberada e temporária de `PAGE_EXTRACTION_BATCH_SIZE` (T11 experimentou
        //    isso, não fica no código final) para um valor gigante — simulando a regressão real
        //    (extrair o livro inteiro numa única chamada, sem lotes) — NÃO estourou OOM neste heap,
        //    em nenhum valor testado até 64m: o texto de um livro sintético de 700 páginas (mesmo
        //    sem lotes) é da ordem de poucos MB, muito abaixo do piso fixo de ~50-60MB só para
        //    subir Spring+Hibernate+Testcontainers nesta JVM. Fazer esse valor de fato estourar
        //    exigiria inflar o texto por página a um tamanho pouco realista (dezenas de MB por
        //    livro), o que contraria a orientação da própria T11 de manter o texto no mínimo
        //    necessário para não pesar o tempo de CI.
        // 3. Por isso a prova determinística e independente do tamanho do livro contra essa
        //    regressão específica é a asserção (b) do teste (via `PageBatchSizeRecorder`): o pico de
        //    páginas extraídas por chamada é sempre `PAGE_EXTRACTION_BATCH_SIZE`, não o tamanho do
        //    livro. O heap restrito aqui é uma defesa complementar (canário): garante que o
        //    footprint atual do pipeline continua pequeno e estável, e pegaria uma regressão mais
        //    grave (ex.: manter várias cópias completas do livro/chunks/embeddings em memória ao
        //    mesmo tempo, ou aumentar muito a dimensão do embedding) mesmo sem depender do
        //    instrumento do item 3.
        maxHeapSize = "256m"
    }

tasks.named("check") {
    dependsOn(volumeTest)
}
