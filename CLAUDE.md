# natural-person.etzhayyim.com

**nanoid**: `np02priv9` | **DID**: `did:web:natural-person.etzhayyim.com` | **sensitivity**: `restricted` (admin-only, T0 hidden)

## Architecture: Statistics-First -> Identity-Later

80-100 billion+ の自然人を統計ベースで管理。個別 PII は Phase 2 で後から紐付け。

### Phase 1: Demographic Statistics + Cohort Generation

| Phase | コマンド | 内容 |
|---|---|---|
| **1A** | `importCensusSource` | 統計ソース登録 (UN WPP, World Bank, WHO, ILO, UNESCO) |
| **1A** | `importDemographicStats` | 16 次元統計分布投入 |
| **1B** | `generateCohortProfiles` | 統計 cross-tabulate -> path-based DID cohort person (G() 使用、replay limit 注意) |
| **1B** | `generateCohortBatch` | 単一 cohort person 生成 (G() 不使用、batch-safe、本番推奨) |
| **1C** | `generateDeceasedCohorts` | 死亡者コホート (6 時代 x 死因 ICD-10) |
| **1D** | `registerBirth` | 出生登録 + 親 DID リンク + 出生統計 |
| **1E** | `generateFamilyRelations` | 家族関係グラフ (配偶者/親子/兄弟) |
| **1F** | `evalCompliance` | 法域別 privacy compliance 評価 (19 ヵ国対応) |

### Phase 1.5: Cross-App Person Identification + Web Enrichment

| コマンド | 内容 |
|---|---|
| `identifyPersonsBatch` | 他 app (hanrei 等) から受信した人物を DJB2 dedup で DID 登録 |
| `enrichPerson` | site.etzhayyim.com 経由で人物の web 情報を収集 (kyumei-koji pattern) |
| `enrichBatch` | 未 enrichment の identified persons を batch で web 調査 |
| `searchPersonGraph` | 人物 entity graph 検索 (IdentifiedPerson + PersonEnrichment 結合) |

**Data flow**: hanrei (判例人物抽出) → `identifyPersonsBatch` (DJB2 dedup → IdentifiedPerson record) → `enrichPerson` (site.etzhayyim.com crawl → LLM extract → PersonEnrichment record)

**Dedup**: `DJB2(name|country|role|organization)` で同一人物を同定。path-based DID `person_{hash}` で MERGE。

**Web enrichment (kyumei-koji)**: discover (search URLs 生成) → site.etzhayyim.com crawl_page → gather (WET/WAT 受信) → validate (LLM confidence check) → integrate (PersonEnrichment record)

**Graph labels**: `IdentifiedPerson`, `PersonEnrichment`, `EnrichmentJob`

### Phase 2: Individual Identity Resolution

| コマンド | 内容 |
|---|---|
| `registerPerson` | 実 identity -> `cohort_did` にリンク |
| `registerIdDocument` | 身分証明書 (Class A, 2 承認必須) |
| `registerRelationship` | 個人間関係 |
| `linkToEntity` | 法人との紐付け |
| `importEventAttendees` | イベント参加者バッチ DID 登録 (path-based DID + person record) |

### Cohort Person Properties (26 次元)

```
country, region, municipality, age, gender,
income_decile, education_isced, occupation_isco, employment_status,
marital_status, household_size, housing_tenure, urban_rural,
health_icd10 (comma-separated), disability_type, migration_status,
ethnicity, religion, language_primary,
entity_did, community_id,
vital_status (alive/deceased/newborn), birth_year, death_year,
death_cause_icd10, era (modern/industrial/medieval/ancient/prehistoric)
```

### DID Structure

```
did:web:natural-person.etzhayyim.com                                    # primary DID (coordinator)
did:web:natural-person.etzhayyim.com:{cohort-hash}                      # cohort person (DJB2 hash of 26 dimensions)
did:web:natural-person.etzhayyim.com:evt_{event-slug}_{name-hash}       # event attendee (DJB2 hash of name+company+event)
```

cohort-hash = DJB2(canonical string of all 26 dimensions)。同一 dimension の cohort は同一 hash -> MERGE で dedup。
event attendee DID = `evt_` prefix + slugified event name + DJB2(name|company|event_name)。

### Write Path

```
generate-cohort-batch -> WRecord("cohort-person", {...})
  -> TS host pendingWrites -> PDS XRPC createRecord
  1. graph SQL write path → RisingWave Hyperdrive INSERT
  -> return {rkey}

Persistence: Lance R2 append-only (sole persistence, no WAL)

DO SQLite: 不使用
```

### Privacy Compliance (eval-compliance)

19 ヵ国の死者個人情報保護法を内蔵:

| 分類 | data_classification |
|---|---|
| historical deceased (era != modern) | `public` |
| deceased + 法域が死者不保護 (JPN, GBR, IND 等) | `internal` |
| deceased + 保護期間超過 (仏 50 年, 伊 20 年) | `internal` |
| deceased + 無期限保護 (中国 民法典 994 条) | `confidential` |
| deceased + 医療データ拡張保護 (USA HIPAA 50 年) | `confidential` |
| statistical cohort (identity 未解決) | 1 段階緩和 |
| living person | `restricted` |

### Cost at Scale

| Scale | Records | 月額 |
|---|---|---|
| 1 億 (統計コホート) | 100M | ~$9/月 (B2 + IcebergWriter) |
| 100 億 | 10B | ~$150/月 (Batch Parquet -> B2) |
| 1 兆 | 1T | ~$1,570/月 (Batch Parquet -> B2, chunk page-in) |

### Access Control

全 Phase 2 コマンド (write + read) に `ctx.OrgID != orgID` ガードを実装。PDS ActorVisibilityGate に加えた二重防御。

| コマンド | 匿名アクセス |
|---|---|
| `import-event-attendees` | ❌ forbidden |
| `register-person` | ❌ forbidden |
| `register-id-document` | ❌ forbidden |
| `register-relationship` | ❌ forbidden |
| `link-to-entity` | ❌ forbidden |
| `get-person` | ❌ forbidden |
| `list-persons` | ❌ forbidden |
| `check-access` | ✅ public (visibility info のみ) |

### Event Attendee Import

`import-event-attendees` — イベント参加者をバッチで path-based DID 登録。

```json
{
  "event_name": "Black Hat USA 2025",
  "event_year": 2025,
  "attendees": [
    {"name": "...", "title": "...", "company": "...", "country": "...", "biography": "...", "person_id": "...", "booth": "..."}
  ]
}
```

DID path: `evt_{slugified_event}_{DJB2(name|company|event)}`。person record に `job_title`, `company`, `event_name`, `event_year`, `source_id`, `booth` を追加保存。
