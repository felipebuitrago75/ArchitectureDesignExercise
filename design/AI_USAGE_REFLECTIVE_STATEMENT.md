# AI Usage & Engineering Judgment Statement

**Candidate:** Andrés Felipe Buitrago Feria  
**Role:** Senior / Staff Backend & Cloud Architect  
**Exercise:** Architecture Design Exercise — Real-Time Template Updates Platform  
**Date:** October 2026  

---

## 1. Where AI Helped Me

During the architectural design and code development of this exercise, AI (Large Language Models) was leveraged as an accelerated force multiplier across three primary dimensions:

1. **Arithmetic Verification & Scenario Modeling**:
   - Rapidly computing token budgets, AWS Aurora ACU costs, and rehydration time boundaries across the skewed dataset (4,000 firms, 800,000 engagements, 1-minute downstream penalty).
   - Validating throughput formulas for fair tenant scheduling (Deficit Round-Robin vs. Token Bucket) to ensure the largest 40,000-engagement firm could not monopolize worker queues.
2. **Drafting Boilerplate & Java Concurrency Primitives**:
   - Scaffolding Java 17 interfaces, record definitions, domain events, and JUnit 5 test harnesses with Mockito and AssertJ.
   - Accelerating the boilerplate for semaphore-based concurrency control, exponential backoff with jitter, and mock verification.
3. **Regulatory Audit Taxonomy Brainstorming**:
   - Synthesizing common audit and assurance standard taxonomy terms (ISA 315, IFRS 15/18, PCAOB risk checklists) to formulate structured JSON schema output templates for non-technical accounting users.

---

## 2. Where I Corrected or Ignored AI Output

Staff-level engineering requires knowing when generative AI proposes naive, textbook, or dangerous patterns in distributed production environments. Specific instances where AI output was corrected or rejected include:

### Case A: The Naive Eager Fan-Out Anti-Pattern
- **Initial AI Proposal**: The LLM initially suggested a standard distributed worker pattern that, upon receiving `TemplatePublishedEvent`, queried all 800,000 engagements (or 20,000 per product) and immediately launched concurrent asynchronous HTTP calls or Lambdas against the downstream rehydration service.
- **My Correction**: I immediately identified this as a catastrophic failure mode. The downstream service takes ~1 minute per file and has limited capacity. 20,000 files $\times$ 1 minute is over 333 worker-hours! Flooding the downstream service would cause thread pool starvation, memory exhaustion in the pods, and cascading failure across live user audit sessions. I corrected this by **challenging the real-time eager requirement** and designing the **Engagement Metadata Index (EMI)** and a rate-limited, token-bucket fair queue.

### Case B: Dynamic LLM Generation for Audit Defense (Forensic Hallucination Risk)
- **Initial AI Proposal**: The LLM suggested querying an LLM agent on demand whenever an auditor or regulator opened the change summary dialog, passing the version diff to get a fresh summary.
- **My Correction**: This is completely unacceptable in regulated financial audit environments. An audit partner declining a template update in March must be able to legally defend that specific decision during a November PCAOB regulatory inspection. Re-running an LLM introduces non-determinism, model drift, temperature variance, and hallucination risks. I corrected the design to enforce **WORM (Write Once Read Many) immutable snapshots**, SHA-256 cryptographic hashes, and linking the partner's decision directly to the static digest.

### Case C: Lack of Tenant Fairness (Starvation of Median Firms)
- **Initial AI Proposal**: A generic FIFO queue processing engagement IDs sequentially.
- **My Correction**: The problem statement explicitly noted extreme data skew: median firms have ~40 engagements, while the largest has ~40,000. In a naive FIFO queue, if a template update hits the largest firm's product, 40,000 long-running tasks occupy the queue for days, completely starving hundreds of smaller firms. I replaced the naive queue with **Partitioned Fair Queuing / Tenant Round-Robin**, capping per-tenant concurrent slices.

---

## 3. How I Would Guide Other Engineers Using AI on This System

As a Staff Engineer mentoring backend teams building enterprise audit software, I enforce the following four guiding principles for AI-assisted engineering:

1. **Verify Invariants Before Implementation**:
   - Never accept an AI-generated architecture without stress-testing its numbers (Little’s Law, backpressure limits, storage scale, network saturation, and failure cascades).
   - If an AI claims a solution is "real-time", calculate the wall-clock arithmetic against the slowest bottleneck in the dependency chain.
2. **Treat AI Code as an Untrusted Third-Party Pull Request**:
   - AI outputs must undergo the exact same rigorous review as junior engineer PRs: check thread safety, memory leak potential (e.g. unbounded thread pools or static collections), error classification (transient vs permanent), and timeout configurations.
3. **Enforce Deterministic Domain Validation (No Trusting LLM Parsers)**:
   - When using LLMs to transform structured JSON diffs into human summaries, always build deterministic code validators that verify entity IDs against the source diff. Do not rely on prompt engineering alone to guarantee truthfulness.
4. **Guard Data Sovereignty and Tenant Boundaries**:
   - Ensure engineers never pipe client-confidential audit workpapers into external LLM endpoints. All template summarization must operate solely on generic product templates, preserving strict regional isolation (GDPR / PIPEDA).

---

## 4. Where AI Should NOT Be Trusted in This Domain

In mission-critical audit, assurance, and compliance platforms, AI must **never** be granted autonomous authority in the following areas:

1. **Applying or Merging Template Updates into Live Workpapers**:
   - Modifying active financial trial balances, journal entries, or materiality thresholds based on probabilistic LLM reasoning is unacceptable. Auditors bear legal and regulatory liability; only an authorized practitioner can make and document the accept/decline decision.
2. **Forensic Audit Provenance & Compliance Records**:
   - An LLM cannot be trusted to "remember" or "reconstruct" past decisions. Audit trails must be backed by immutable cryptographic hashes, digital signatures, and database ledgers.
3. **Evaluating Complex Accounting Standards & Regulatory Scope**:
   - While LLMs can summarize language changes, deciding whether an audit engagement is subject to a specific jurisdiction's legal update (e.g., EU CSRD vs. US SEC climate disclosure) requires human partner judgment.
4. **Capacity & Rate-Limit Management**:
   - AI code generators frequently omit edge-case timeout handling, backpressure mechanisms, and circuit breakers. Concurrency limits against sensitive downstream services must be guarded by strict code contracts and deterministic rate limiters, not speculative logic.
