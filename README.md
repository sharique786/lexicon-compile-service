# Lexicon Compile Service
### Spring Boot 4.0.6 · JDK 21 · Intel Hyperscan (com.gliwka.hyperscan 5.4.0-2.0.0) · GCP Cloud Run

**Base package:** `com.db.macs3.ecomms.spectre`

---

## What This Service Does

Accepts lexicon rule definitions (as JSON or CSV), translates each term description into a Hyperscan-compatible PCRE pattern, validates it via `Database.compile()`, and returns per-term compilation results. Used by compliance surveillance systems before scanning millions of communication messages at runtime.

---

## Supported Term Description Features

| Feature | Syntax | PCRE output |
|---|---|---|
| **OR** | `price OR spread OR stock` | `(?:price\|spread\|stock)` |
| **AND** | `A AND B AND C` | `(?=.*A)(?=.*B)(?=.*C).*` + DOTALL |
| **AND NOT** | `insider AND NOT disclaimer` | `(?=.*insider)(?!.*disclaimer).*` |
| **NOT / !** | `NOT spam` or `!spam` | `(?!.*spam).*` |
| **NEAR{n}** | `manipulate NEAR{5} price` | Bidirectional: `(?:A{gap}B\|B{gap}A)` |
| **FOLLOWEDBY{n}** | `don't FOLLOWEDBY{3} compliance` | Directional: `A{gap}B` only |
| **Wildcard trailing** | `manipulate*` | `manipulate\S*` |
| **Wildcard leading** | `*running` | `\S*running` |
| **Quoted phrase** | `"please don't forward"` | PCRE-escaped literal |
| **CSV double-quote** | `""please don't forward""` | Unescaped → `"please don't forward"` |
| **Apostrophe** | `don't`, `can't` | Literal (not escaped — safe in PCRE) |
| **Exclamation** | `"stop!"` | Literal (or `!` as NOT prefix outside quotes) |
| **Emoji** | `💰 OR 🤫` | `(?:\x{1F4B0}\|\x{1F92B})` + UTF8+UCP |
| **Korean** | `비밀 OR 내부자 거래` | Literal + UTF8+UCP flags |
| **Japanese** | `株価操作 OR インサイダー` | Literal + UTF8+UCP flags |
| **Chinese/Mandarin** | `内幕交易 OR 操纵市场` | Literal + UTF8+UCP flags |
| **Arabic** | `مخالفة OR استثمار داخلي` | Literal + UTF8+UCP flags |
| **Hebrew** | `מסחר פנים OR מניפולציה` | Literal + UTF8+UCP flags |
| **German** | `Übernahme OR Insiderhandel` | Literal + UTF8+UCP flags |
| **Turkish** | `içeriden bilgi` | Literal + UTF8+UCP flags |
| **Leet-speak** | `1ns1d3r* OR fr0nt*` | Literal (intentional — matches obfuscated text) |
| **Mixed language** | `insider OR 내부자 OR 💰` | OR group + UTF8+UCP |

---

## Hyperscan Flag Bitmask

| Flag | Value | Applied when |
|---|---|---|
| `HS_FLAG_CASELESS` | 1 | Always |
| `HS_FLAG_DOTALL` | 2 | AND, AND NOT, NOT operators (`.` crosses newlines) |
| `HS_FLAG_UTF8` | 32 | Any non-ASCII character present |
| `HS_FLAG_UCP` | 64 | Alongside UTF8 for Unicode property support |

Example: Korean term with AND → flags = 1 + 2 + 32 + 64 = 99

---

## REST API

### `POST /api/lexicon/compile`

**Content-Type:** `application/json`  
**Content-Encoding:** `gzip` *(recommended)*  
**Accept-Encoding:** `gzip` *(recommended)*

#### Request
```json
{
  "lexiconRuleName": "lexicon_research_1",
  "terms": [
    {
      "termId": "lexicon_research_1::1",
      "termDescription": "(manipulate) NEAR{5} ((price) OR (spread) OR (stock))",
      "riskDriverName": "Research Based Front Running"
    },
    {
      "termId": "lexicon_research_1::2",
      "termDescription": "don't FOLLOWEDBY{3} compliance",
      "riskDriverName": "Evasion of Surveillance"
    }
  ]
}
```

#### Response
```json
{
  "lexiconRuleName": "lexicon_research_1",
  "totalTerms": 2,
  "passCount": 2,
  "failedCount": 0,
  "hasFailures": false,
  "engineMode": "HYPERSCAN_NATIVE",
  "hyperscanVersion": "5.4.0-2.0.0",
  "compiledAt": "2024-01-15T10:23:45.123Z",
  "processingTimeMs": 12,
  "results": [
    {
      "termId": "lexicon_research_1::1",
      "termDescription": "(manipulate) NEAR{5} ((price) OR (spread) OR (stock))",
      "riskDriverName": "Research Based Front Running",
      "compilationStatus": "PASS",
      "translatedPattern": "(?:manipulate(?:\\s+\\S+){0,5}\\s+(?:price|spread|stock)|(?:price|spread|stock)(?:\\s+\\S+){0,5}\\s+manipulate)",
      "hyperscanFlags": 1,
      "requiresAndPostFilter": false,
      "compiledAt": "2024-01-15T10:23:45.124Z"
    },
    {
      "termId": "lexicon_research_1::2",
      "compilationStatus": "PASS",
      "translatedPattern": "don't(?:\\s+\\S+){0,3}\\s+compliance",
      "hyperscanFlags": 1,
      "requiresAndPostFilter": false,
      "compiledAt": "2024-01-15T10:23:45.125Z"
    }
  ]
}
```

### `POST /api/lexicon/compile/csv`

**Content-Type:** `multipart/form-data`  
Fields: `file` (CSV), `ruleName` (optional)

### `GET /api/lexicon/health`

Returns engine mode, version, supported operators and languages.

### `GET /actuator/health`

Spring Boot Actuator health — includes Hyperscan component.

---

## Project Structure

```
lexicon-compile-service/
├── pom.xml                              Spring Boot 4, JDK 21, Hyperscan, Checkstyle, JaCoCo
├── Dockerfile                           2-stage JDK 21 build; no native compile step
│
├── src/main/java/com/db/macs3/ecomms/spectre/
│   ├── LexiconCompileServiceApp.java    @SpringBootApplication
│   │
│   ├── translator/
│   │   ├── TermSyntaxTranslator.java    Recursive-descent parser → PCRE patterns
│   │   ├── TranslationResult.java       Sealed interface: Success | Error (JDK 21)
│   │   ├── ParseContext.java            Mutable flag accumulator during parsing
│   │   └── ProximityMatch.java          Data record for NEAR/FOLLOWEDBY tokens
│   │
│   ├── hyperscan/
│   │   └── HyperscanCompiler.java       com.gliwka.hyperscan wrapper; @PostConstruct self-test
│   │
│   ├── model/
│   │   └── Models.java                  CompileRequest, TermInput record, TermCompilationResult
│   │                                    record, CompileResponse record, CompilationStatus enum
│   ├── service/
│   │   ├── LexiconCompileService.java   Translate → validate → build response
│   │   └── CsvCompileService.java       CSV parsing + BOM stripping; delegates to above
│   │
│   ├── controller/
│   │   ├── LexiconCompileController.java  POST /compile, POST /compile/csv, GET /health
│   │   └── GlobalExceptionHandler.java    @RestControllerAdvice — 400/413/500 responses
│   │
│   └── config/
│       ├── GzipRequestFilter.java       jakarta.servlet.Filter — decompresses GZIP requests
│       ├── LexiconProperties.java       @ConfigurationProperties prefix: lexicon
│       └── LexiconCompileConfig.java    CORS, Hyperscan health indicator
│
├── src/main/resources/
│   ├── application.yml                  local / test / cloud-run profiles (no dup spring: keys)
│   └── checkstyle/
│       ├── checkstyle.xml               Google Java Style, 120-char limit, SLF4J enforcement
│       └── suppressions.xml
│
└── src/test/java/com/db/macs3/ecomms/spectre/
    ├── translator/TermSyntaxTranslatorTest.java    All operators, all languages, edge cases
    ├── hyperscan/HyperscanCompilerTest.java        Live Hyperscan compile validation + thread safety
    ├── service/LexiconCompileServiceTest.java      End-to-end translate→compile pipeline
    ├── service/CsvCompileServiceTest.java          CSV parsing: header, BOM, quotes, multi-lang
    ├── controller/LexiconCompileControllerTest.java  MockMvc: JSON, GZIP, CSV, validation, health
    └── integration/MultiLanguageIntegrationTest.java  Real messages in 8 languages + emoji
```

---

## curl Examples

### Plain JSON request
```bash
curl -X POST http://localhost:8080/api/lexicon/compile \
  -H "Content-Type: application/json" \
  -H "Accept-Encoding: gzip" \
  -d @src/test/resources/sample-request.json \
  --compressed | python3 -m json.tool
```

### GZIP request + response
```bash
gzip -c src/test/resources/sample-request.json | \
curl -X POST http://localhost:8080/api/lexicon/compile \
  -H "Content-Type: application/json" \
  -H "Content-Encoding: gzip" \
  -H "Accept-Encoding: gzip" \
  --data-binary @- \
  --compressed | python3 -m json.tool
```

### CSV upload
```bash
curl -X POST http://localhost:8080/api/lexicon/compile/csv \
  -F "file=@src/test/resources/sample-lexicon.csv" \
  -F "ruleName=lexicon_research_1" \
  -H "Accept-Encoding: gzip" \
  --compressed | python3 -m json.tool
```

---

## Running Tests

```bash
# All tests (Hyperscan native lib auto-extracted from JAR)
mvn test

# Specific test class
mvn test -Dtest=TermSyntaxTranslatorTest
mvn test -Dtest=MultiLanguageIntegrationTest
mvn test -Dtest=LexiconCompileControllerTest

# Coverage report (target/site/jacoco/index.html)
mvn verify
```

---

## Cloud Run Deployment

```bash
export PROJECT_ID=my-gcp-project
export REGION=us-central1
export IMAGE=gcr.io/${PROJECT_ID}/lexicon-compile-service

gcloud builds submit --tag ${IMAGE} .

gcloud run deploy lexicon-compile-service \
  --image=${IMAGE} \
  --region=${REGION} \
  --memory=2Gi \
  --cpu=2 \
  --min-instances=1 \
  --max-instances=10 \
  --concurrency=80 \
  --set-env-vars="SPRING_PROFILES_ACTIVE=cloud-run" \
  --allow-unauthenticated
```
