# AI Usage Statement

**Candidate:** Andrés Felipe Buitrago Feria · **Date:** October 2026

## 1. Where AI helped

- **Checking numbers:** quick calculations for downstream time, LLM token cost and AWS cost across the given scale (4,000 firms, 800,000 engagements, 1 minute per file).
- **Boilerplate:** Java 17 records, interfaces, and JUnit 5 + AssertJ tests with in-memory fakes, plus the first version of the semaphore, retry and backoff code.
- **Audit vocabulary:** naming change categories (disclosures, procedures, materiality) in terms non-technical users understand.

## 2. Where I corrected or ignored AI

**A. Checking every engagement on publish.** The first approach the AI laid out was to call the downstream service for each of the ~20,000 engagements of a product as soon as a template was published. I asked whether the downstream would survive that, and the numbers settled it: 20,000 × 1 min ÷ 15 ≈ 22 hours per publish. We switched to a regional index of engagement versions, so the dashboard doesn't need to open any file.

**B. Regenerating summaries on demand.** The AI suggested calling the LLM every time someone opens a summary. In an audit that isn't acceptable: a decision made in March must be explainable in November with the exact text the user saw. Summaries are stored once in S3 Object Lock with a hash, and the decision records that hash.

**C. A single FIFO queue.** One queue for everything would let a firm with 40,000 engagements block firms with 40 for days. I replaced it with one queue per firm, taking a few from each in turn.

## 3. How I would guide other engineers

1. **Check the numbers.** Before accepting an AI design, work out the time and load against the slowest dependency.
2. **Review AI code like any pull request.** Look at thread safety, timeouts, unbounded queues, and how errors are classified.
3. **Validate LLM output with code.** Check that everything a summary mentions exists in the source diff. Don't rely on the prompt alone.
4. **Keep customer data out of prompts.** Only template diffs go to the LLM, and customer data stays in its region.

## 4. Where AI should not be trusted here

1. **Applying updates to live workpapers.** Auditors are legally responsible; the accept/decline decision is theirs.
2. **Audit records.** An LLM can't "remember" past decisions. Records must be stored, hashed and immutable.
3. **Deciding which standards apply.** Whether a regulatory change applies to an engagement needs professional judgment.
4. **Limits and timeouts.** AI-generated code often misses backpressure and timeout edge cases. Those need explicit code and tests.
