# Architectural Diagrams & Visual Reference Models

**System:** Real-Time Product Template Updates Platform  
**Author:** Andrés Felipe Buitrago Feria  
**Context:** Staff Architecture Design Exercise  

---

## 1. High-Level System Architecture & Tenant Boundaries

```mermaid
flowchart TB
    subgraph CentralAWS["Central AWS Infrastructure (Global / Non-Firm Specific)"]
        CT["Content Methodology Team"] -->|Author & Release| PTS["Product Template Service (PTS)"]
        PTS -->|Template Zip & Structured JSON| S3T["Amazon S3 (Template Bucket)"]
        PTS -->|Metadata & DAG State| PDB[("Aurora PostgreSQL (Template DB)")]
        
        PTS -->|Publish Event| EB["Amazon EventBridge (Global Bus)"]
        
        EB --> TSS["Template Summary Service (TSS)"]

        subgraph SummaryPipeline["Deterministic Summary Pipeline"]
            TSS
            TSS -->|Normalize JSON Diff| DN["Diff Normalizer"]
            DN -->|Deterministic Prompt Temp: 0.0| LLM["Bedrock / Claude 3.5 Sonnet"]
            LLM -->|Verify vs Source Diff| HF["Hallucination & Fact Checker"]
            HF -->|SHA-256 Digest| S3W["Amazon S3 Object Lock (WORM Store)"]
        end
    end

    subgraph RegionalCluster["Regional Tenant Infrastructure (e.g., EU: Frankfurt / CA: Montreal)"]
        EB -.->|Cross-Region EventBridge Route| SQS["Regional Fan-Out SQS FIFO"]
        
        subgraph FanOutSubsystem["Regional Fan-Out Engine"]
            SQS --> RFW["TemplatePublishFanOutWorker"]
            RFW <--> FTQ["FairTenantQueue (DRR Scheduling)"]
            RFW <--> DCT["DownstreamCapacityThrottler (Max Permits = 15)"]
            RFW <--> IDM[("Redis / DynamoDB Idempotency Store")]
            RFW -->|Permanent 4xx / Poison Pill| DLQ["Dead Letter Queue (SQS DLQ)"]
        end

        subgraph StorageLayer["Tenant Data Stores"]
            EMI[("Engagement Metadata Index (EMI)\nAurora Serverless v2 (< 200 MB/region)")]
            EMS["Downstream Engagement Pods (~1 min)\n(Hydrates In-Memory Volatile Models)"]
            TDB[("Customer Engagement Blob DBs\n(Client-Confidential Audit Material)")]
            AUDIT[("Append-Only Decision Ledger")]
        end

        RFW -->|Update Version Pointers| EMI
        RFW -.->|Controlled Backfill Rehydration| EMS
        EMS <--> TDB
        EMS -->|Post-Hydration Hook| EMI
    end

    subgraph ClientLayer["Auditor Web Application"]
        Auditor["CPA / Audit Senior"] -->|"1. Load dashboard"| API["Regional GraphQL / REST API"]
        API -->|"Indexed query"| EMI
        Auditor -->|2. Inspect Pending Summary| S3W
        Auditor -->|3. Accept or Decline| EMS
        EMS -->|Record SHA-256 Decision Snapshot| AUDIT
    end
```

---

## 2. Non-Linear Versioning DAG & Update Accumulation

```mermaid
gitGraph
   commit id: "v1.0" tag: "Initial Release"
   branch Market-UK
   checkout Market-UK
   commit id: "v1.1-UK (FRS 102 Update)"
   checkout main
   branch Market-US
   checkout Market-US
   commit id: "v1.1-US (AICPA Standard)"
   commit id: "v1.2-US (Declined by Firm A)"
   commit id: "v1.3-US (Withdrawn)" type: REVERSE
   commit id: "v1.4-US (Offered to Firm A with Cumulative Summary v1.1 to v1.4)"
```

### Cumulative Summary Flow for Skipped / Declined Updates

```mermaid
flowchart TD
    V11["Engagement on v1.1-US"] -->|User Declined| V12["Update v1.2-US (Declined)"]
    V12 -->|Content Team Withdraws| V13["Update v1.3-US (WITHDRAWN)"]
    V13 -->|New Release| V14["Update v1.4-US (LATEST HEAD)"]

    subgraph EvaluationLogic["DAG Traversal Engine"]
        E1["Detect Current Pinned: v1.1-US"]
        E2["Detect Target Head: v1.4-US"]
        E3["Filter Out Withdrawn Nodes: v1.3-US"]
        E4["Compute Cumulative Diff Delta: (v1.1-US -> v1.4-US)"]
        E5["Fetch Precomputed Composite Summary from WORM Store"]
    end

    V14 --> EvaluationLogic
    EvaluationLogic --> UserDialog["User Modal:\n'Update v1.4 incorporates changes from declined v1.2 plus new revisions.'"]
```

---

## 3. Zero-Downtime Migration & Non-Disruptive Backfill Flow

```mermaid
sequenceDiagram
    autonumber
    actor Auditor as Auditor User
    participant Pod as EMS Rehydration Pod (~1 min)
    participant EMI as Engagement Metadata Index (EMI)
    participant Worker as Fan-Out Worker (Phase 2 Crawl)
    participant Central as Central Template Store

    Note over Auditor,EMI: Phase 1: Dual-Write on Natural Daily Hydration
    Auditor->>Pod: Open Engagement Workpaper
    Pod->>Pod: Rehydrate into Memory (~1 min)
    Pod->>EMI: Async Hook: Upsert (firmId, engId, templateId, version)
    Pod-->>Auditor: Workpaper Ready

    Note over Worker,EMI: Phase 2: Controlled Background Crawl (Off-Peak Hours)
    Worker->>EMI: Query Un-Indexed Engagement IDs
    Worker->>Pod: Throttled Call (Rate limit: 15 concurrent)
    Pod-->>Worker: Return (templateId, version)
    Worker->>EMI: Persist Metadata

    Note over Auditor,Central: Phase 3: Dashboard reads from the index
    Auditor->>EMI: Query engagements with pending updates
    EMI-->>Auditor: List of pending updates
    Auditor->>Central: Fetch Immutable Summary by SHA-256 Hash
    Central-->>Auditor: Return Human-Readable Audit Summary
```

---

## 4. Fair Multi-Tenant Queue Dispatcher Pipeline

```mermaid
flowchart LR
    subgraph Inbound["Inbound Enqueue"]
        E1["Firm A (40,000 files)"]
        E2["Firm B (40 files)"]
        E3["Firm C (15 files)"]
    end

    subgraph Scheduler["FairTenantQueue (round-robin per firm)"]
        Q1["Queue: Firm A"]
        Q2["Queue: Firm B"]
        Q3["Queue: Firm C"]
        RR["Round-Robin Selector\n(Quantum = 3 files / turn)"]
    end

    subgraph Protection["Downstream Protection Layer"]
        TH["Capacity Throttler\n(Fair Semaphore = 15 permits)"]
        IDM["Idempotency Filter\n(Lease TTL = 5 mins)"]
    end

    subgraph Target["External Downstream Pods"]
        EMS["EMS Rehydration Service (~1 min/call)"]
    end

    E1 --> Q1
    E2 --> Q2
    E3 --> Q3
    Q1 & Q2 & Q3 --> RR
    RR -->|Fair Batch| IDM
    IDM -->|New Tasks| TH
    TH -->|Guarded Calls| EMS
```
