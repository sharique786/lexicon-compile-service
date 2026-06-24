# Lexicon Scan Engine — Architecture & Design

## 1. System Overview

The Lexicon Scan Engine is an Apache Spark Dataproc job that performs lexicon-based alerting on
millions of communication messages (email and chat). It uses Intel Hyperscan pre-compiled pattern
databases to match lexicon terms against message bodies and attachments at high speed.

```
Airflow DAG
  │  Runtime Args: PROCESS_ID, PIPELINE_EXEC_ID, POLICY_ENGINE_ID, TRIGGER_TYPE, RUN_DATE
  │
  ▼
Lexicon Scan Engine (Dataproc / Spark 3.3.1)
  │
  ├── [1] Create BQ View: spectre-audit.language-feature-decision ⋈ spectre-audit.feature-master
  │         Filter: feature_type IN ('lexicon', 'Composite')
  │         Flatten: sub_features where sub_feature.type = 'lexicon'
  │
  ├── [2] Load .hdb files from GCS   →  Broadcast<Map<featureName, byte[]>>
  │
  ├── [3] Read AVRO messages from GCS (partitioned by RUN_DATE/PIPELINE_EXEC_ID)
  │         Filter: message_id IN (view.message_id)
  │
  ├── [4] Join messages with feature decisions
  │
  ├── [5] mapPartitions → Noise Reduction Decision → Standard Lexicon Match
  │
  └── [6] Write to 4 BQ output tables
```

---

## 2. Module Structure

```
lexicon-scan-engine/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/db/macs3/ecomms/spectre/
    │   │   ├── LexiconScanEngineApplication.java     ← main()
    │   │   ├── config/
    │   │   │   ├── AppConfig.java                    ← Spring @Configuration, beans
    │   │   │   └── ScanEngineProperties.java         ← @ConfigurationProperties
    │   │   ├── model/
    │   │   │   ├── ScanEngineArgs.java               ← parsed runtime args
    │   │   │   ├── FeatureDecisionRow.java           ← view output row (1 per msg+feature)
    │   │   │   ├── MessageRecord.java                ← AVRO message + attachments
    │   │   │   ├── TermMatch.java                    ← single Hyperscan match result
    │   │   │   ├── FeatureScanResult.java            ← results for one feature
    │   │   │   └── ScanOutputRow.java                ← BigQuery output row
    │   │   ├── reader/
    │   │   │   ├── BigQueryViewReader.java           ← creates BQ view, reads as Dataset
    │   │   │   ├── AvroMessageReader.java            ← reads AVRO from GCS
    │   │   │   └── GcsHyperscanDatabaseLoader.java  ← loads .hdb bytes from GCS
    │   │   ├── engine/
    │   │   │   ├── LexiconScanEngine.java            ← Spark orchestrator (run() method)
    │   │   │   ├── LexiconScanPartitionFunction.java ← MapPartitionsFunction (executor)
    │   │   │   ├── NoiseReductionEvaluator.java      ← noise reduction decision logic
    │   │   │   └── HyperscanMatcher.java             ← wraps Hyperscan scan() call
    │   │   └── writer/
    │   │       └── BigQueryOutputWriter.java         ← writes Dataset to BQ tables
    │   └── resources/
    │       └── application.yml
    └── test/
        └── java/com/db/macs3/ecomms/spectre/
            ├── engine/
            │   ├── LexiconScanEngineTest.java
            │   ├── NoiseReductionEvaluatorTest.java
            │   └── HyperscanMatcherTest.java
            └── reader/
                └── BigQueryViewReaderTest.java
```

---

## 3. BigQuery View SQL

```sql
CREATE OR REPLACE VIEW `spectre-audit.v_lexicon_scan_engine_input` AS

WITH
-- Step 1: Flatten the REPEATED features array from language-feature-decision
-- One row per (message_id, feature_name) pair
lfd_flat AS (
    SELECT
        lfd.process_id,
        lfd.message_id,
        lfd.run_date,
        lfd.sent_date,
        feat.type            AS feature_type,
        feat.id              AS feature_id,
        feat.name            AS feature_name,
        feat.feature_op      AS feature_operator,
        feat.is_noise_reduction AS is_noise_reduction,
        -- Sub-feature array for Composite parent lookup
        feat.sub_feature     AS sub_features
    FROM `spectre-audit.language-feature-decision` lfd,
    UNNEST(lfd.features) AS feat
),

-- Step 2a: Direct 'lexicon' type features from feature-master
fm_direct_lexicon AS (
    SELECT
        fm.policy_engine_id,
        fm.feature_name,
        fm.feature_definition AS resolved_feature_definition
    FROM `spectre-audit.feature-master` fm
    WHERE fm.feature_type = 'lexicon'
),

-- Step 2b: Composite features → unnest sub_features → only 'lexicon' sub-type
fm_composite_lexicon AS (
    SELECT
        fm.policy_engine_id,
        sf.name              AS feature_name,
        sf.definition        AS resolved_feature_definition
    FROM `spectre-audit.feature-master` fm,
    UNNEST(fm.sub_features) AS sf
    WHERE fm.feature_type = 'Composite'
    AND   sf.type = 'lexicon'
),

-- Step 2c: Combined feature definitions
fm_combined AS (
    SELECT * FROM fm_direct_lexicon
    UNION ALL
    SELECT * FROM fm_composite_lexicon
)

-- Step 3: Join to get the full view
-- All columns from LHS (lfd_flat) + resolved_feature_definition from RHS
SELECT
    lfd.process_id                     AS process_id,
    lfd.message_id                     AS message_id,
    lfd.run_date                       AS run_date,
    lfd.sent_date                      AS sent_date,
    lfd.feature_type                   AS feature_type,
    lfd.feature_id                     AS feature_id,
    lfd.feature_name                   AS feature_name,
    lfd.feature_operator               AS feature_operator,
    COALESCE(lfd.is_noise_reduction, FALSE) AS is_noise_reduction,
    fm.resolved_feature_definition     AS fm_feature_definition
FROM lfd_flat lfd
INNER JOIN fm_combined fm
    ON  lfd.process_id  = fm.policy_engine_id
    AND lfd.feature_name = fm.feature_name
```

---

## 4. Spark Processing Pipeline

```
SparkSession
    │
    ▼
[A] Read BQ view → featureDecisionDS  (Dataset<FeatureDecisionRow>)
    - Collect distinct feature_name → Set<String> featureNames
    - Collect distinct message_id   → Set<String> relevantMessageIds
    │
    ▼
[B] Load .hdb files from GCS for featureNames
    - GcsHyperscanDatabaseLoader.loadAsBytes(featureNames, gcsBucket, hdbPrefix)
    - Returns: Map<String, byte[]>  (featureName → raw bytes)
    - Broadcast: Broadcast<Map<String, byte[]>> broadcastHdb
    │
    ▼
[C] Read AVRO messages from GCS
    - Path: gs://<bucket>/messages/RUN_DATE=<date>/PIPELINE_EXEC_ID=<id>/**/*.avro
    - Filter: message_id IN (relevantMessageIds)
    - Explode attachments → one row per (message_id, attachment_index)
    - messageDS: Dataset<MessageRecord>
    │
    ▼
[D] Group features by message_id (collect_list)
    - featureGroupedDS: Dataset<Row>
    │
    ▼
[E] Join messages with grouped features
    - messageWithFeaturesDS: Dataset<Row>
    │
    ▼
[F] mapPartitions → LexiconScanPartitionFunction
    - Receives: Iterator<Row> (message + all its features)
    - Returns:  Iterator<ScanOutputRow>
    │
    ▼
[G] Write to BQ output tables
```

---

## 5. Critical Design: Hyperscan Database Loading on Executors

**The Problem**: `com.gliwka.hyperscan.wrapper.Database` is a JNI object — it is NOT
Java-serializable and CANNOT be placed in a Spark broadcast variable or closure.

**The Solution**: Broadcast raw bytes, deserialize on each executor JVM with static caching.

```
Driver                          Executor (mapPartitions)
──────────                      ──────────────────────────────────────────────────────
Load bytes from GCS             Receive Map<String, byte[]> via broadcast.value()
Map<String, byte[]>    ──────►  static ConcurrentHashMap<String, Database> CACHE
Broadcast<...>                      └─ computeIfAbsent(name, bytes ->
                                           Database.load(new ByteArrayInputStream(bytes)))
                                One Scanner per database, reused across all rows in partition
                                Scanners closed in finally block after partition is consumed
```

**Why static cache?**
- The cache lives in the Executor JVM (not the task) — shared across all tasks on the executor
- Avoids reloading multi-MB .hdb files for every Spark task (which share the JVM)
- Databases are read-only after load → thread-safe for concurrent scan() calls
- Scanner has scratch space allocated per database; we create one Scanner per database per partition

---

## 6. Decision Tree Logic

```
For each message_id:
  features = [all FeatureDecisionRow for this message]

  noiseReductionFeatures = features WHERE is_noise_reduction = true
  standardFeatures       = features WHERE is_noise_reduction = false

  IF noiseReductionFeatures is NOT empty:
    Scan each noise-reduction feature against message content + attachments
    Collect results

    allNoiseReducOp = first non-null feature_operator (all features share same op per message)

    IF allNoiseReducOp == "OR":
      skipStandard = anyNRFeatureMatched
    ELSE IF allNoiseReducOp == "AND":
      skipStandard = allNRFeaturesMatched

    IF skipStandard:
      WRITE noise_reduction hit records
      STOP (do not process standard features)
    ELSE:
      PROCEED to standard features

  IF standardFeatures is NOT empty AND NOT skipStandard:
    Scan each standard feature against message content + attachments
    WRITE standard hit records
```

| Feature Category | Operator | Condition              | Action                      |
|------------------|----------|------------------------|-----------------------------|
| Noise Reduction  | OR       | ANY match              | Skip standard features      |
| Noise Reduction  | OR       | NONE match             | Process standard features   |
| Noise Reduction  | AND      | ALL match              | Skip standard features      |
| Noise Reduction  | AND      | NONE/PARTIAL match     | Process standard features   |
| Standard only    | —        | —                      | Always process              |

---

## 7. Match Position & Delta

For each Hyperscan match:
- `start_index`: byte offset where the match begins
- `end_index`:   byte offset where the match ends
- `match_text`:  the actual matched substring
- `delta`:       when multiple terms match, the gap (end of previous match - start of this match)
                 useful for proximity analysis downstream

---

## 8. Output Table Schemas

### spectre-audit.lexicon-hit-summary (primary hit table)
| Column                | Type    | Description                                  |
|-----------------------|---------|----------------------------------------------|
| message_id            | STRING  | From AVRO                                    |
| run_date              | DATE    | Partition column                             |
| sent_date             | DATE    |                                              |
| policy_engine_id      | STRING  | From runtime args                            |
| pipeline_exec_id      | STRING  | From runtime args                            |
| feature_name          | STRING  | Matched feature / .hdb file name             |
| feature_type          | STRING  | lexicon or Composite                         |
| is_noise_reduction    | BOOLEAN |                                              |
| lexicon_terms         | STRING  | Comma-separated termIds that hit             |
| regex_pattern         | STRING  | The regex that matched (from .hdb metadata)  |
| total_count_of_terms  | INTEGER | Total terms in the .hdb                      |
| hit_count_of_terms    | INTEGER | Number of terms that hit                     |
| hit_details           | JSON    | See structure below                          |
| process_ts            | TIMESTAMP | Processing timestamp                       |

### hit_details JSON structure
```json
{
  "msg": {
    "matches": [
      { "term_id": "lexicon_research_1::1",
        "match_text": "bomb",
        "start_index": 23,
        "end_index": 26,
        "delta": 0 }
    ]
  },
  "attachment-0": {
    "matches": [
      { "term_id": "lexicon_research_1::1",
        "match_text": "bomb",
        "start_index": 45,
        "end_index": 48,
        "delta": 0 }
    ]
  }
}
```

### spectre-audit.pipeline_stage_audit
| Column           | Type      | Description                             |
|------------------|-----------|-----------------------------------------|
| pipeline_exec_id | STRING    |                                         |
| process_id       | STRING    |                                         |
| stage_name       | STRING    | e.g. "LEXICON_SCAN"                     |
| stage_status     | STRING    | SUCCESS / FAILED / PARTIAL              |
| records_read     | LONG      |                                         |
| records_written  | LONG      |                                         |
| start_ts         | TIMESTAMP |                                         |
| end_ts           | TIMESTAMP |                                         |
| error_message    | STRING    | null on success                         |

### spectre-audit.pipeline_record_audit
| Column           | Type   | Description                              |
|------------------|--------|------------------------------------------|
| pipeline_exec_id | STRING |                                          |
| message_id       | STRING |                                          |
| status           | STRING | PROCESSED / SKIPPED / FAILED             |
| features_applied | STRING | JSON array of feature names applied      |
| process_ts       | TIMESTAMP |                                       |

---

## 9. Performance & Memory Optimisation

### Spark Tuning
| Parameter                          | Recommendation                            |
|------------------------------------|-------------------------------------------|
| spark.executor.memory              | 8g (adjust based on cluster)             |
| spark.driver.memory                | 4g                                        |
| spark.executor.cores               | 4                                         |
| spark.sql.adaptive.enabled         | true (AQE for Spark 3.x)                 |
| spark.sql.adaptive.skewJoin.enabled| true                                      |
| spark.serializer                   | org.apache.spark.serializer.KryoSerializer|
| spark.sql.shuffle.partitions       | 200 (tune to message volume)             |
| spark.default.parallelism          | num_executors × cores × 3               |

### HDB File Management
- HDB files are loaded once per executor JVM (static cache), not once per task
- If a single .hdb file is very large (>500 MB), stream from GCS on executor rather than broadcast
- Close Scanner objects after each partition (mapPartitions finally block)
- Database objects remain open in the static cache for JVM lifetime (efficient)

### Message Reading
- Read AVRO with partition pruning on RUN_DATE and PIPELINE_EXEC_ID
- Use `filter()` with broadcast of relevant message_ids for push-down
- Explode attachments in SQL layer before join to avoid UDF overhead

### BQ Write
- Use BigQuery Storage Write API (via Spark connector `writeMethod=STORAGE_WRITE_API`)
- Partition output by run_date for efficient downstream querying

---

## 10. Technology Stack

| Component        | Technology                                          | Version   |
|------------------|-----------------------------------------------------|-----------|
| Language         | Java                                                | 11        |
| Framework        | Spring Boot (⚠ Spring Boot 3 needs Java 17)        | **2.7.18**|
| Distributed      | Apache Spark                                        | 3.3.1     |
| Pattern Matching | Intel Hyperscan via gliwka/hyperscan-java           | 5.4.0-2.0.0 |
| Message Format   | Apache Avro                                         | Spark bundled |
| BQ Integration   | spark-bigquery-with-dependencies                    | 0.32.2    |
| GCS Client       | google-cloud-storage                                | 2.27.1    |
| BQ Java Client   | google-cloud-bigquery (for view creation)           | 2.33.2    |
| Build            | Apache Maven                                        | 3.9       |
| Test             | JUnit 5 + Mockito                                   | 5.9 / 5.4 |
| Coverage         | JaCoCo                                              | 0.8.8     |

> ⚠ **Spring Boot version**: Spring Boot 3.x requires Java 17+ (Spring Framework 6).
> For Java 11 compatibility, this project uses Spring Boot **2.7.18** (Spring Framework 5.3.x).
> If upgrading to Java 17 is possible, migrate to Spring Boot 3.2.x.
