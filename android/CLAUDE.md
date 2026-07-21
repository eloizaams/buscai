## android/ — app Android

Stack: Kotlin, Jetpack Compose, Hilt, minSdk 26, AGP 9 (Kotlin embutido — **não** aplicar o plugin
`org.jetbrains.kotlin.android`, ver `android/gradle/libs.versions.toml`), Gradle version catalog.

Comandos (rodar dentro de `android/`):
```
./gradlew ktlintFormat              # formata antes de considerar qualquer tarefa concluída
./gradlew testDebugUnitTest         # testes unitários do módulo :app
./gradlew lint                      # Android lint
./gradlew :app:assembleDebug        # build (exige Android SDK instalado)
```

Convenções:
- Sem lógica de negócio em `@Composable` — Composables só leem estado de um `ViewModel`/`UiState`.
- MVVM: `ViewModel` expõe `StateFlow<UiState>`, nunca `LiveData`.
- Toda chamada de rede passa pelo cliente HTTP do backend (nunca chamar Claude/Voyage direto do
  app — a key nunca existe no cliente, ver ADR-0004/ADR-0005).
- `BACKEND_BASE_URL`/`BACKEND_API_KEY` vêm de `BuildConfig` (hoje com placeholder em
  `app/build.gradle.kts` — sobrescrever localmente, nunca commitar valor real).
