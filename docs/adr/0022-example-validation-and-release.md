# ADR 0022: Example Ownership And Creator Validation

## Status

Accepted direction on 2026-09-19 through the user's documentation-planning request
and two explicit decisions: separate Example inputs/expectations together, and fail
an expectation whose Step was not reached. At that point detailed schema, validation operators,
migration and release policy were open. The same workshop later clarified Examples as
execution/coverage and field-mapping evidence, distinct from optional tests.

Amended on 2026-11-03: the user accepted selecting a displayed JSON field in Data to add
equality or absence checks, inspired by AssertJ. The authenticated, stateless Creator check
boundary is implemented and verified. The user then accepted implementing file separation and
saved expectations together. Their implementation is locally verified. Visual authoring and
suite/release policy remain unimplemented. The specification owns the concrete boundaries.

## Context

The original Trigger format embedded Example inputs. The generated application executes
compiled cases and exposes real results. Creator displays those results and checks explicit
expectations without becoming a second Step executor. The separate saved-case owner is implemented
and locally verified in the 2026-11-03 amendment. Visual authoring is still planned.
Visual groups cannot own correctness because they
are optional presentation. Users need simple execution validation first and optional
visual output checks later without adding a test engine to production applications.

## Decision

Keep executable behavior in `railix.project.json`, presentation in
`railix.creator.json`, and place authored Example inputs plus optional expectations
together in one versioned `railix.examples.json`. References use Trigger and Step
identities, not names, geometry or group occurrences.

The generated application continues to execute; Creator validates its returned
evidence without running Steps again. Phase 1 accepts completion without an unexpected
error. Phase 2 adds optional visual expectations. An expected Step that was not
reached fails validation rather than being silently skipped.

Examples also expose actual fields and executed routes so users can author downstream mappings.
Coverage, successful execution and optional assertion results remain separate. The user's later
instruction to omit unreachable Steps unless explicitly kept belongs to the
[compilation-selection contract](../specs/system-model.md#planned-compilation-selection); its
reachability rule is not selected here. A missing Example is not, by itself, static proof that a
connected branch cannot execute. The earlier roadmap's blanket test-pass build gate is superseded.

[Examples and validation](../specs/examples-and-validation.md) owns the requirements,
acceptance and unresolved choices. This ADR does not approve a concrete file schema
or build-gate implementation. Its amended first check boundary is defined in that specification.

## Consequences

- A project can review and evolve Example inputs and expected results together,
  independently of visual metadata and executable Step configuration.
- The separate owner requires lossless import and complete-action recovery, not merely
  a new filename. Creator imports embedded cases when no Example file exists and rejects
  competing owners instead of silently selecting one.
- Missing, stale or truncated observations cannot establish passing assertions.
  Validation must use bounded evidence tied to the checked case and artifact.
- An accidentally changed branch cannot stay green by skipping the expected Step.
- Successful execution alone does not prove business correctness or exhaustive
  coverage; the UI must distinguish it from satisfied output expectations.
- Creator validation adds no assertion evaluator or dependency to the generated
  application. Omission applies to Example execution/value capture and assertions, not to the
  shared operational metrics clarified on 2026-11-03 in ADR 0020.

## Alternatives Not Chosen

- Keeping test inputs in the executable file while storing expectations elsewhere
  splits one authored case across owners.
- Putting expectations in Creator presentation makes correctness depend on optional
  visual metadata.
- Re-executing Steps in Creator creates a second execution path and can repeat side effects.
- Treating an unreached expectation as skipped can hide a routing regression.

AssertJ-inspired field controls are accepted direction, not a decision to embed AssertJ or
support arbitrary executable assertion code. Expected-error cases and repeated visits
need later explicit semantics rather than hidden defaults.

## Relationship To Earlier Decisions

This amends ADR 0020's target ownership of authored Examples, not its single-graph,
single-execution-path or presentation-isolation decisions. The old embedded format
remains importable, not a second writable Example owner. The [roadmap](../roadmap.md#current-sequence) records
the dependent planning and delivery slices.
