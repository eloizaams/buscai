# Teste Manual do Backend — Guia Prático

## 📋 Resumo do que foi implementado

- ✅ Pipeline de ingestão: PDF → Extração → Limpeza → Chunking → Embeddings (Voyage AI) → Postgres/pgvector
- ✅ CLI via `IngestCommand` (ativada com profile `ingest`)
- ✅ Validação de chunks e tratamento robusto de erros
- ✅ Idempotência e reindexação (ADR-0008)
- ✅ Processamento em lotes para otimizar memória

---

## 🔧 Como testar o backend

### 1️⃣ **Preparar o Postgres com pgvector**

```bash
# Subir Postgres com Docker (se não tiver local)
docker run --name buscai-postgres \
  -e POSTGRES_DB=buscai \
  -e POSTGRES_USER=buscai \
  -e POSTGRES_PASSWORD=buscai \
  -p 5432:5432 \
  -d pgvector/pgvector:pg16

# Esperar ~5s para o container iniciar
sleep 5
```

### 2️⃣ **Preparar arquivo de teste**

Você precisa de um PDF para testar. Se não tiver um, crie um dummy:

```bash
# Gerar um PDF mínimo com conteúdo
echo "Este é um livro de teste. Página 1." > /tmp/test.txt
echo "Aqui está a página 2 do livro." >> /tmp/test.txt
echo "Terceira página com mais conteúdo." >> /tmp/test.txt

# Converter para PDF (requer 'enscript' ou 'wkhtmltopdf')
# Alternativa: usar um PDF real que você tenha
```

### 3️⃣ **Executar a ingestão via CLI**

```bash
cd backend

# Definir as variáveis de ambiente
export DATABASE_URL="jdbc:postgresql://localhost:5432/buscai"
export DATABASE_USERNAME="buscai"
export DATABASE_PASSWORD="buscai"
export ANTHROPIC_API_KEY="sk-ant-..."  # sua chave real (opcional para este teste)
export VOYAGE_API_KEY="pa-..."         # sua chave real (opcional para este teste)
export BUSCAI_API_KEY="changeme"       # chave estática do app

# Rodar a ingestão (profile 'ingest' ativa a CLI)
SPRING_PROFILES_ACTIVE=ingest ./gradlew bootRun \
  --args="--book-id=dom-casmurro --file=/caminho/seu-livro.pdf --title='Dom Casmurro'"
```

### 4️⃣ **Verificar dados no banco**

```bash
# Conectar ao Postgres
psql -h localhost -U buscai -d buscai

# Consultas para validar:
SELECT * FROM book;                           -- Ver livros ingeridos
SELECT * FROM book_version;                   -- Ver versões (status READY = sucesso)
SELECT id, page, text FROM chunk LIMIT 5;     -- Ver chunks extraídos
SELECT count(*) FROM chunk;                   -- Contar total de chunks
```

### 5️⃣ **Verificar logs da ingestão**

Os logs mostram:
- `[INFO] Ingestão iniciada...`
- `[INFO] ... chunks processados (embedding + persistência)`
- `[WARN] Ingestão falhou` (se houver erro)

---

## ⚠️ Problemas comuns

| Erro | Solução |
|------|---------|
| `java.sql.SQLNonTransientConnectionException` | Postgres não está rodando. Rode o `docker run` acima. |
| `org.springframework.boot.web.server.WebServerException` | Porta 8080 ocupada (normal). Deixe rodar ou kill a porta anterior. |
| `PDF parece não ter camada de texto extraível` | PDF é escaneado (OCR). Use um PDF nativo com texto. |
| `error ao gerar embeddings` | Chave Voyage AI inválida ou limite de cota. Use `--reindex` se quiser tentar depois. |

---

## 📊 Próximas etapas

Depois que a ingestão estiver funcionando, as próximas tasks provavelmente serão:
1. **Chat REST endpoint** — app Android vai chamar `/chat` (streaming SSE)
2. **Retrieval + RAG generation** — buscar chunks relevantes e gerar resposta com Claude
