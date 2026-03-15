# rag-qa


<!-- long-task-agent -->
## Long-Task Agent

This project uses a multi-session agent workflow with 12 skills loaded on-demand.
The `using-long-task` skill is injected at session start and routes to the correct phase.
Flow: Requirements (SRS) → UCD (UI projects) → Design → Init → Worker cycles → System Testing.
Incremental development: place `increment-request.json` → Increment skill updates SRS/Design/UCD in place → new features appended → Worker cycles → ST.

Key files: `docs/plans/*-srs.md` (SRS), `docs/plans/*-ucd.md` (UCD style guide), `docs/plans/*-design.md` (design), `feature-list.json` (task inventory), `task-progress.md` (session log), `RELEASE_NOTES.md` (changelog), `docs/test-cases/feature-*.md` (per-feature ST test cases), `docs/plans/*-st-report.md` (ST report), `increment-request.json` (increment signal).
<!-- /long-task-agent -->
