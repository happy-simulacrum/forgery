---
name: bug-fix
description: Sequentially fix bugs from a triage list (typically .opencode/plans/bugs.md), spawning subagents for each fix and a separate subagent for emulator verification, asking questions in a single batch after exhausting no-question fixes, and finishing with a regression smoke test. Use when the user says "fix the bugs", "walk through the bug list", "fix R3-N", "execute the plan", or hands over a numbered bug list.
---

# Bug Fix Workflow

Apply a strict, sequential, subagent-driven workflow against a pre-triaged bug list. Each bug is fixed in isolation, verified on an emulator, and only then does the workflow move on.

## Inputs

- **Bug list.** Default source: `.opencode/plans/bugs.md`. Items are expected to be pre-triaged with severity, `file:line`, and a one-line root cause. Each item should be tagged either **R** (fix this session) or **B** (backlog — skip unless user explicitly opts in).
- **Build/test commands** from `AGENTS.md` (`./build_debug`, `./build_release`, `./deploy`, `./gradlew :app:compileGooglePlayDebugKotlin` for compile-only checks). Use these — do not hand-roll gradle.

If no bug list exists, stop and ask the user for one. Do not invent bugs.

## Workflow

Execute the steps in order. Do not skip ahead.

### 1. Read the bug list and group items

- Open `.opencode/plans/bugs.md` (or whichever file the user named).
- Build two ordered groups:
  - **No-question group**: items where the fix is mechanical and unambiguous (rename, add null check, swap function, fix order of operations, etc.). Fix without asking.
  - **Needs-decision group**: items where multiple reasonable fixes exist (UX choice, API contract change, data migration, breaking refactor). List the specific decision needed for each.
- Within each group, process items in severity order: critical → high → medium → low.
- Track progress with TodoWrite. One todo per bug fix + one for smoke test.

### 2. Fix the no-question group sequentially

For each item in the no-question group:

#### 2a. Fix subagent
Spawn a `general` subagent with:
- The single bug item (severity, file:line, root cause)
- AGENTS.md build conventions
- Instruction to make the **minimal correct change**, not a refactor
- Instruction to verify the change compiles (`./gradlew :app:compileGooglePlayDebugKotlin` for compile-only, or `./build_debug` for full APK)
- Instruction to report back: file:line of the change, what was done, and the compile result

Wait for the subagent to finish before proceeding.

#### 2b. Verify subagent
Spawn a second `general` subagent with:
- The same bug item + the fix subagent's report
- Instruction to verify on an Android emulator: build + install (`./deploy googlePlay debug`), reproduce the original scenario, confirm the bug is gone, capture logcat/snackbar/screenshot evidence
- Instruction to report either **VERIFIED** with evidence, or **NOT FIXED** with what was observed

If the verify subagent reports NOT FIXED:
- Read its report carefully. Do not assume the fix was wrong — it may be a different root cause or a related bug.
- Decide whether to (a) refine the fix (spawn a new fix subagent with the verify subagent's report), or (b) escalate to the user as a question.
- Repeat until the bug is verified or you hit 2 failed cycles on the same item — at which point move the item to the needs-decision group with the verify subagent's report attached.

#### 2c. Mark item done
Update TodoWrite. Do not move on to the next item until the current one is verified.

### 3. Collect and ask all decision questions in one batch

After the no-question group is exhausted, present **all** remaining items in the needs-decision group as a single `question` tool call (one batch, multiple questions). For each:
- The bug item ID and one-line description
- The specific decision needed (e.g. "rename `SOFTPOS` → `NFC_OR_QR`? rename `salesSkipped` to `saleItemsSkipped`? drop the field?")
- 2–4 concrete options, with the recommended option first

Wait for the user's answers before continuing. Do not fix any item from the needs-decision group until the user responds.

### 4. Fix the needs-decision group

Apply the same subagent-driven fix-and-verify loop (step 2) to each answered item. If an answer reveals a question that wasn't asked, add it to the list and ask before fixing.

### 5. Regression smoke test

When every bug in scope is verified, run a smoke test:
- Spawn a `general` subagent with the build/install command and a checklist of the **most-touched areas** during this round (the file paths that appeared in ≥2 fixes, plus the app's primary user flows: POS → cart → checkout, inventory → create/edit/delete product, settings → backup).
- Instruct it to walk through each flow on the emulator, look for crashes, blank screens, broken navigation, missing strings, lost-state issues.
- Report either **CLEAN** with what was exercised, or **REGRESSIONS FOUND** with file:line + repro steps.

If regressions are found, treat them as new bug items: re-enter step 1 with the regressions as a fresh (no-question) group, unless they require decisions.

### 6. Final report

When the smoke test is clean:
- Summarize what changed (file count, severity breakdown).
- List items the user might want to commit or follow up on.
- Point at any items the user should review before merging.

## Rules

- **One bug at a time.** Never batch fixes in a single edit. Each bug gets its own fix subagent.
- **Never guess.** If you don't know the right behavior, add the item to the needs-decision group. The cost of asking is lower than the cost of a wrong fix.
- **One question batch per group.** Don't drip questions one at a time during the fix loop.
- **Subagents do work, you coordinate.** You don't write code yourself for fixes — you write the brief and read the report. Exception: trivial one-line fixes where a subagent is overkill.
- **Don't touch items tagged B** unless the user explicitly opts them in. The plan file already triaged them.
- **Don't refactor.** Fix the bug, not the file. If a refactor would prevent a class of bugs, mention it as a follow-up but don't do it.
- **Compile after every fix.** A green compile is a sanity check, not proof of correctness — but a red compile is always a stop.
- **If the build script fails or the emulator is unavailable**, stop and tell the user. Don't paper over it.