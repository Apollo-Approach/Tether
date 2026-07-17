---
name: opus-method-procedures
description: "The Opus Method — structured problem-solving procedures. Use when starting any non-trivial task to select the right method. Contains six methods (Survey-Propose-Execute, Reconnaissance Spiral, Parallel Dispatch, Hypothesis-Test Cycle, Anchored Conversation, Precision Strike) with trigger conditions, step-by-step procedures, success criteria, and transition rules."
---

# The Opus Method — Procedures

Read this skill when beginning a non-trivial task to select and follow the appropriate method.

## Method Selection

Assess the task against these trigger conditions:

| If the task is... | Apply... |
|---|---|
| Non-trivial, with architectural impact or significant unknowns | **Survey → Propose → Execute** |
| In an unfamiliar domain, codebase, or system | **Reconnaissance Spiral** |
| Large and decomposable into independent sub-problems | **Parallel Dispatch** |
| A defect, failure, or unexpected behavior | **Hypothesis-Test Cycle** |
| Conceptual, ambiguous, or requiring design alignment | **Anchored Conversation** |
| Small, well-defined, and low-risk | **Precision Strike** |

Methods are not mutually exclusive. The selection table identifies the *entry point*, not the entire trajectory.

---

## Procedures

### Survey → Propose → Execute

**Trigger:** Non-trivial. Jumping straight to implementation risks wasted effort.

1. **Survey** — Inventory the current state: components, dependencies, conventions, constraints. Build a working model. Do not modify anything.
2. **Propose** — Written plan: what changes, why, risks, trade-offs, open questions, execution order. Submit for review. Flag uncertainty explicitly.
3. **Execute** — Only after explicit approval. Dependency order: foundations → integration → validation. Significant deviations return to step 2.
4. **Verify** — Confirm output matches plan. Run tests. Document deviations.

**Success:** Output matches plan. No surprises. Stakeholders aligned.
**Failure Mode:** Plan becomes the product, execution energy drops. *Corrective:* Time-box planning. If plan exceeds task complexity, downgrade to Precision Strike.

---

### Reconnaissance Spiral

**Trigger:** Unfamiliar territory. You don't know what you don't know.

1. **Pass 1 — Structure:** Map high-level layout. *What is this system shaped like?*
2. **Pass 2 — Conventions:** Identify patterns, idioms, norms. *How does this system think?*
3. **Pass 3 — Target:** Narrow to the specific area of change. You now have context to act consistently.

**Success:** Subsequent action is stylistically and architecturally consistent with the existing system.
**Failure Mode:** Unnecessary fourth/fifth passes. *Corrective:* Hard limit of three passes. If insufficient, the problem is missing documentation — escalate.

---

### Parallel Dispatch

**Trigger:** Large task, decomposable into independent sub-problems.

1. **Decompose** — Break into discrete units. Verify independence: "If Unit A changes approach, does Unit B need to know?" If yes, not independent.
2. **Assign** — Each unit gets a tightly scoped mandate: objective, constraints, integration point.
3. **Orchestrate** — Remain at coordination layer. Monitor and flag integration risks.
4. **Integrate** — Merge at defined integration points. Run cross-unit validation.

**Success:** All units integrate cleanly. Elapsed time < serial execution.
**Failure Mode:** Coupled sub-problems create conflicting assumptions. *Corrective:* Stress-test independence at step 1.

---

### Hypothesis-Test Cycle

**Trigger:** Something is broken or behaving unexpectedly.

1. **Observe** — Gather evidence: errors, logs, observations, recent changes. No fixes yet.
2. **Hypothesize** — Single, specific, falsifiable hypothesis. State it: "I believe [X] because [Y]."
3. **Test** — Minimal experiment. One variable at a time.
4. **Evaluate** — Confirmed → fix → step 5. Refuted → new hypothesis → step 2.
5. **Validate** — Fix resolves the *original* problem. No regressions.

**Success:** Root cause identified. Fix is targeted, not incidental.
**Failure Mode:** Degrades into Rapidfire Fix Mode after multiple failures. *Corrective:* Circuit Breaker CB1 fires after 3 consecutive failed attempts — see global-opus-method engram.

---

### Anchored Conversation

**Trigger:** Conceptual, exploratory, or requiring alignment before action.

1. **Ground** — Review all shared context: prior decisions, docs, history. No assumptions about what others remember.
2. **Explore** — Think out loud. Surface reasoning. Ask clarifying questions. Match pace to complexity.
3. **Converge** — Summarize agreement and open questions. Define next actions only when aligned.

**Success:** Shared understanding. Documented decisions. No surprise next steps.
**Failure Mode:** Over-exploration without convergence. *Corrective:* Periodically check: "Enough alignment to act?" If yes, converge.

---

### Precision Strike

**Trigger:** Small, well-defined, low-risk. Scope immediately clear.

1. Read.
2. Change.
3. Confirm.

No planning phase. No research. Match process weight to task weight.

**Success:** Completed quickly and correctly.
**Failure Mode:** Task was misclassified — hidden complexity emerges. *Corrective:* If effort exceeds 2x estimate, stop. Reclassify using the method selection table. Escalate the method, not the effort.

---

## Method Transitions

| From | To | When |
|---|---|---|
| Reconnaissance Spiral | Survey-Propose-Execute | Recon reveals a full plan is needed |
| Precision Strike | Hypothesis-Test | "Simple" fix reveals hidden complexity |
| Hypothesis-Test | Circuit Breaker CB1 | 3 consecutive failures |
| Survey-Propose-Execute | Parallel Dispatch | Execution phase has independent sub-tasks |
| Anchored Conversation | Survey-Propose-Execute | Alignment reached, ready for planning |
| Any method | Precision Strike | A sub-task is trivially simple |

**Recalibration signal:** If Precision Strike → Hypothesis-Test transitions happen frequently, default task classification is too optimistic. Start with Reconnaissance Spiral for unfamiliar work.
