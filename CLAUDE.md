# Teleprompter — working agreements

SPEC.md is the source of truth for product decisions and milestone history; update it as
decisions are made. `CLAUDE.md` is for *how we work*, not what we build.

## Research before building (standing rule, 2026-08-02)

**Never invent a bespoke solution for a problem others have already solved well.**
Before designing any non-trivial feature or algorithm:

1. **Research prior art first** — commercial products, patents, academic literature, and
   especially open-source implementations. Write the findings into `docs/` (see
   `docs/voice-follow-research.md` for the model: per-product findings, algorithms,
   sources, then a synthesis). The follow-voice feature shipped months of avoidable
   heuristic-patching because this step was skipped at M3.
2. **Reuse good projects when the license allows it.** Check the license *before*
   reading the code closely:
   - MIT/Apache/BSD: reuse freely, keep the attribution notice, cite the source repo in
     a code comment at the port site.
   - GPL family: incompatible with bundling in this MIT app — ideas only.
   - **No license file = all rights reserved = do not copy code.** Ideas and published
     algorithm descriptions are fine (e.g. laanlabs/VoicePrompter is unlicensed: its
     TextMatcher *design* informed our matcher, but no code was ported).
3. Patents describe algorithms publicly — reading them is the fastest way to learn how a
   market leader works (PromptSmart's voice tracking is US 9,953,646 B2). For this
   personal open-source tool the practical infringement risk is negligible (SPEC §9),
   but avoid literal claim-by-claim implementations anyway.

Known-good reference repos for this project are listed in SPEC §10.

## Conventions

- `follow/` stays pure Kotlin (no Android imports) and replay-tested; build the test
  with the feature, not after (SPEC §3.2).
- Version-bump every behavior change (`app/build.gradle.kts`); releases ship a fresh,
  hash-verified APK via `gh release`.
- Run `gradlew :app:testDebugUnitTest` before calling any follow/matcher change done.
