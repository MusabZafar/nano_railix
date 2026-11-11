# Examples And Creator Validation

## Human Review

Status: **Partially implemented; field UI and suite policy remain planned.** Updated on
2026-11-03 with the stateless check boundary, separate Example owner and saved-check boundary
implemented and locally verified. The broader direction was recorded
on 2026-09-19, with real-I/O and output-fake clarification on 2026-09-21 and scenario needs
raised on 2026-10-18 by the user.
The user explicitly accepted implementing file separation together with saved expectations on
2026-11-03. The storage and migration contract below now governs that work.

Outcome: use actual Example execution to build and connect Flows, discover fields and demonstrate
which Steps execute. Optional assertions add checks of expected results; they are not what makes
an Example useful or required for authoring.

Owns: Example input/expectation ownership, validation meaning and acceptance criteria.
Does not own: Step execution, visual groups, production instrumentation or a new
assertion-library dependency. See [observation](observation.md) for current
execution boundaries and [ADR 0022](../adr/0022-example-validation-and-release.md)
for the decision and its consequences.

### Accepted Decisions

| Decision | Acceptance source |
| --- | --- |
| Phase 1 succeeds when the Example finishes without an unexpected error. | User's documentation-restructuring request. |
| Phase 2 adds optional visual expectations on each Example's Step outputs. | Same request: AssertJ-inspired authoring, with Creator validating and the binary executing. |
| Versioned `railix.examples.json` owns Example inputs and expectations together, linked to Trigger and Step IDs. | Explicit answer accepting the separate-file recommendation. |
| An expectation on a Step that the Example does not reach fails validation. | Explicit answer: "Fail: the expected Step must be reached". |
| Examples provide execution/coverage evidence and actual fields for mapping and connecting Steps, including fields created earlier in a Flow. | User's subsequent clarification distinguishing Examples from passing tests. |
| Unreachable Steps should be omitted unless explicitly kept. | Same clarification; the meaning of unreachable and retention behavior remain blocked in the system-model decision below. |
| Examples may perform real I/O; users may deliberately bypass it in a test Flow. | User's 2026-09-21 clarification. |
| A future Example may supply fake outputs for a selected Step. | Same clarification; selection, output and persistence details remain open. |
| Examples supply Trigger output and start after the Trigger; the development app independently starts its real external inputs. | User's lifecycle clarification on 2026-09-21. |

The initial field-check interaction, its check boundary and the accepted file separation are
specified below. A production-build gate remains unapproved.
The latest clarification supersedes the roadmap's blanket "every Example passes tests before
production compilation" wording. It does not remove optional expectation failures or authorize
silently pruning a branch merely because a demonstration did not traverse it.

### Current Versus Target

| Concern | Current implementation | Accepted target |
| --- | --- | --- |
| Executable behavior | `railix.project.json`: flat nodes, links and Step inputs. Embedded Examples are imported when no Example file exists. | Project file owns executable behavior. |
| Example authoring | `railix.examples.json` owns cases with stable IDs, Trigger references and optional expectations. Storage is locally verified. | Inputs and optional expectations stay together. |
| Presentation | Optional `railix.creator.json`, ignored by compiler/runtime execution. | Still presentation only; no expectations or validation policy. |
| Execution | Generated development app runs compiled Examples once through normal routes and owns their results. | Same owner and execution path. |
| Validation | Authenticated Creator checks compare a selected Step's recorded context. Saved checks use the same document owner. The field UI and suite/release policy are not implemented yet. | Creator validates current application-returned evidence. |
| Production | Artifacts without Examples omit their manifest and value tracing, but retain shared operational metrics. | Example inputs, fakes and expectation evaluation remain omitted; the [observation contract](observation.md) owns common telemetry and the protected local connection. |

Separating the authoring file does not move Example execution into Creator or make
the development app load mutable authoring files at request time. Changes to Example
inputs reach the matching generated artifact through the existing compiler input. Changing only
expectations does not rebuild the application. Starter Example values in Step definitions remain templates,
not an alternative owner of authored project cases.

## Behavior

`MUST` below describes the accepted target, not completed implementation.

| ID | Phase | Observable requirement |
| --- | --- | --- |
| EX-001 | 1 | Creator MUST regard a completed Example without an unexpected error as successful without requiring output expectations. This proves execution, not every possible business result. |
| EX-002 | File separation | Example inputs and optional expectations MUST share one versioned `railix.examples.json`, linked to Trigger/Step IDs rather than labels or visual groups. Executable behavior and presentation retain their separate owners. |
| EX-003 | Both | The application MUST execute the Example through its normal generated path. Creator MUST validate returned evidence without re-executing Steps or compiling assertion logic into the application. |
| EX-004 | 2 | Users MUST be able to author optional expectations visually for a particular Example and Step output. A reached output that violates an expectation MUST fail that Example's validation. |
| EX-005 | 2 | When an expected Step is not reached, Creator MUST fail validation and identify that Step, rather than skip the expectation or report success. |
| EX-006 | Both | Creator MUST NOT report validation success using incomplete evidence or evidence from a different application/Example revision. Execution completion and expectation results MUST remain distinguishable. |
| EX-007 | Authoring | Creator MUST expose the selected Example's actual Step inputs/outputs and created context/payload fields for downstream mapping and connection, with Example/Step provenance. Observed values MUST NOT be substituted for the Step's declared contract. |
| EX-008 | Coverage | Creator MUST show which Flows and Steps the current Example set executed, and distinguish uncovered Steps, unsuccessful/incomplete execution and failed optional expectations. Reaching a Step MUST NOT by itself report its successful completion or prove all of its possible outcomes. |
| EX-009 | I/O | Examples MAY execute real Step side effects. Creator MUST NOT describe Example execution as sandboxed or imply that test mode prevents external writes. |
| EX-010 | Output fakes | Users MUST be able to select fake Step outputs per Example. Evidence MUST distinguish injected outputs from executed handler results; fake configuration MUST NOT become production behavior. The replacement boundary and output/outcome selection remain to be agreed. |
| EX-011 | Trigger output | An Example MUST supply the configured Trigger output and execute its continuation without invoking Trigger PROCESS or making a real ingress request. Running an Example MUST NOT open, close or restart a listener. The normal development-app lifecycle MUST start configured external inputs independently. |

EX-006 follows from checking the selected case, not an older or unobserved execution.
Its concrete pending/unavailable/stale status vocabulary is still open. A missing
output is not a null output, and neither is evidence that a Step was unreached.
Current trace retention/size limits can make output evidence unavailable; do not
silently increase them or turn absence into a passing comparison.

### Examples, Coverage And Inclusion

The authoring goal is to demonstrate every Flow and Step intended for execution, not to demand
assertions on each value. A field created by one Step becomes visible for mapping after that
Example reaches the relevant point. If another Example omits it or gives it another supported
shape, Creator retains that provenance instead of inventing one always-present merged payload.
Examples show concrete behavior; they do not prove a dynamic field exists for every future input.

For multi-input and linked-use editing, preserve provenance by Example, originating
invocation/route and selected occurrence, even if Creator draws one building. Do not mix observed
fields from separate arrivals into a fictional context. At an explicit Join, show the actual
combined context produced under [SYS-024](system-model.md#planned-merge-and-join), not a merge
performed by Creator. Repeated writes are not inherently errors. Observations can expose a
concrete conflict but cannot prove that every possible ordering is safe. Conflicting Join values
produce the non-blocking warning in [CR-014](creator.md#example-guidance-and-circuit-metaphors). The observed result
must show the actual last-arriving winner, not turn a warning into a failed execution. Authored
assertions still evaluate the resulting context and may fail independently.

Join/Limiter Examples will need explicit multi-arrival, timing and completion evidence under the
owning control acceptance criteria. Current single-Trigger cases are not a coordinated concurrent
scenario facility. Scenario inputs, bounds and controlled scheduling must be agreed before that
extension. No new Example file format or forced concurrent execution is selected here.

Keep four facts separate: connection/reachability in the graph, observed Example coverage,
execution completion and optional assertion results. Coverage is measured against the authored
graph/build revision, not made green by hiding omitted Steps from its denominator. Explicitly kept
but unobserved behavior stays identifiable. The [compilation-selection contract](system-model.md#planned-compilation-selection)
owns what is emitted; coverage transport stays in observation. The development build must exist
before it can produce that evidence, so compilation must not depend circularly on its own run.

### Real I/O And Output Fakes

Current Trigger Examples already describe output: the compiler places `payload` at the Trigger's
configured Example target in the supplied context and starts execution after the Trigger. They
are not HTTP requests and do not require the HTTP endpoint to deliver that output. This is not
the future general per-Step fake feature. The app's normal startup still owns real listeners;
Example execution must neither suppress them nor initialize them once per Example. See
[LC-7](system-model.md#accepted-lifecycle-decisions) for that separation.

Real I/O is already possible and explicitly permitted. `context.runtime.test` allows an
ordinary Choice to route an Example around a side-effecting Step. Standard Field Manipulation
already accepts a literal object and writes it to a target path, so constructing a replacement
value does not inherently require a new Step type. The branches can then converge under the
[ordinary convergence contract](system-model.md#planned-merge-and-join), without a mandatory Merger.
The convergence baseline is tracked separately from later coordinated branch execution.

This explicit Flow is a valid authoring technique, not the future output-fake API. Per-Example,
per-Step fakes are planned so users need not build bypass branches just for test data. Before
implementing them, agree stable Example identity, declared output/outcome selection, whether
replacement skips the whole handler, and how missing or partial fake data behaves. Do not
silently execute a real side effect when a user expects its Step to be faked.

A fake can demonstrate downstream behavior, not the replaced integration. Coverage and
validation must retain that distinction. The development application continues to own execution
through normal compiled routing; Creator does not become a second runtime. Automatically
managed test fixtures, reset/cleanup and production-write prevention are separate future
capabilities, not prerequisites that retroactively prohibit current real-I/O Examples.

### Visual Authoring Proposal

First interaction accepted by the user on 2026-11-03: select a displayed JSON field in the Data
view and add equality or absence expectations. AssertJ is an inspiration for clear, composable
checks and useful actual/expected feedback, not a request to execute Java. The storage boundary
below is implemented independently of the still-planned field controls.

- Select an Example once through the existing selector. In the Step Data view, attach an
  expectation to a displayed JSON field instead of introducing another selector or test panel.
- Offer equality and absence first. Keep JSON null distinct from a missing field. Compare exact
  canonical numbers on the Creator server, never through browser floating-point conversion.
- Put a small result mark on the field. Expand comparison/edit controls only on demand. Reuse
  the existing issue navigation for failures. Do not add permanent explanatory text to every row.
- Persist Example inputs and expectations together in the accepted `railix.examples.json` owner.
  Stable authored Example IDs must replace positional identity before expectations can survive
  deletion, reordering and renaming safely. Keep one writable owner, not synchronized copies.
- Editing only an expectation rechecks current retained evidence. It must not rebuild the app,
  repeat I/O, increment operational metrics or insert assertion branches into generated code.
- The exact field anchor and repeated-visit policy still need agreement. An immutable recorded
  Step snapshot can be checked, but Creator must not substitute a later shared/live context or
  select an arbitrary parallel occurrence. Expected-Step absence fails the expectation. Missing,
  stale, truncated or unavailable evidence must not pass.

The row-based controls in [Bruno assertions](https://docs.usebruno.com/testing/tests/assertions)
support this non-script direction. [Postman test scripts](https://learning.postman.com/docs/tests-and-scripts/write-scripts/test-scripts/)
illustrate the executable-script model not selected here. The
[WAI-ARIA disclosure pattern](https://www.w3.org/WAI/ARIA/apg/patterns/disclosure/) supports
keyboard-operated optional controls. These references inform interaction design, not Railix's
execution or persistence contract. First scope, selectors, statuses and migration remain the
decisions in this document, not conclusions supplied by another product.

The [AssertJ guide](https://assertj.github.io/doc/#assertj-core-assertions-guide) informs readable
value selection followed by a check, type-appropriate options and precise actual/expected
feedback. The first operators are `isEqualTo` and `doesNotExist`. Equality is structural JSON
value equality: object key order is irrelevant, array order matters, numbers compare exactly
without floating-point conversion or tolerance, and JSON null is not absence. An absent field
does not pass equality with null. Absence still requires the expected Step to have been reached.
No first implementation may silently select one of several visits to the same Step. Until a
visit-selection policy is agreed, repeated visits produce explicit unavailable validation.
The completed immutable Step snapshot is the source, not a later shared context. Checking
retained evidence does not execute a Step, change the project or increment operational counters.

### Stateless Check Boundary

Implemented on 2026-11-03, separately from saved/visual authoring:

- `GET /api/assertions` returns the supported checks and their expected-value requirements.
- `POST /api/assertions/check` accepts the current project `revision`, `application_pid`,
  compiled `example` ID, ordinary `step` ID and `checks`. Each check contains a `path` beginning
  with `context`, an `assertion` identifier and, for equality only, an `expected` JSON value.
- Creator reads the application's completed Example summary and the selected Step's immutable
  projection. It never accepts caller-supplied actual values or invokes the Step. All checks
  must pass. A later Step failing does not invalidate a complete earlier Step snapshot.
- Source/artifact/PID guards reject obsolete evidence. Truncated or unavailable recording does
  not prove unreachability. Multiple visits remain explicitly unavailable, not last-result-wins.
- Input and result use the existing 1 MiB boundary. Observation reads share one 30-second
  deadline and the existing four-response admission budget. Comparison observes cancellation
  and the same deadline. Oversized or unrepresentable results cannot become a passing result.
- Results distinguish `passed`, `failed` and `unavailable`. A failed check is not an application
  failure. Invalid checks return 422, stale identity or ambiguous visits 409, unavailable evidence
  503 and oversized results 413. No assertion state is stored by this endpoint.

These checks are a usable headless boundary, not proof of global suite validation, build gating
or the field editor. Saved expectations are covered by the separate storage boundary below.
The saved-result lifecycle still owns global validation and gating.

### Example Document And Saved Checks

`railix.examples.json` has `format: 1` and an `examples` array. Each case contains a stable `id`,
`trigger`, `name`, `payload`, optional `context` and optional `expectations`. Each expectation
contains an ordinary `step` ID and a nonempty `checks` array using the check grammar above.
There is at most one expectation entry for a Step within a case. Writers omit `expectations`
when there are no checks instead of creating an empty form.

Creator imports embedded cases only when the Example file is absent, preserving their initial
compiled IDs. New cases receive opaque IDs before saving. Renaming, reordering and editing inputs
retain an explicitly carried ID. Missing IDs on new authoring requests mean new cases, not a
name/content matching heuristic. The compiler rejects duplicate IDs across all Triggers.

After import, Examples are absent from the persisted Flow document. Creator and the build command
assemble one transient compiler/editor projection from the two files. That projection reuses the
existing validation and execution contracts, includes no expectations and is never written back
as an alternative Example owner. A flow-only edit retains cases only for unchanged Trigger
definitions. A new or changed Trigger still requires explicit cases.

If both files declare cases, startup rejects the conflict without choosing one. A missing project
cannot overwrite an existing Example file. Invalid Example files are not replaced with templates.
The existing editor journal covers Flow, presentation and Example files, including migration and
rollback. New journals record before and planned-after bytes so recovery can preserve later
external edits. Complete-action Undo/Redo also restores affected expectations. Empty check edits
do not create history or invalidate another editor's revision.

`GET /api/assertions/expectations/{example}/{step}` returns saved `checks`, their content-hash
`revision` and the current `source_revision`. Authenticated `POST` to the same path accepts those
revisions plus `checks`. An empty check list clears that Step's expectations. Stale revisions
return 409 and invalid checks return 422. Saving only expectations uses one atomic file replacement,
does not restart the application and participates in the existing Undo/Redo history.

Deleting a case deletes its expectations with that case. An expectation referencing a missing
Step remains an unresolved reference, not a passing or silently removed assertion. Step IDs are
identities, not labels. The normal Creator allocates new opaque IDs for new Steps. Deliberately
reusing an existing identity in an authored file reconnects its references. Case inputs and checks
survive ordinary Flow edits. Neither expected values nor comparison operators enter generated
application resources or handlers.

The build command refuses a known pending editor transaction rather than running files from an
interrupted edit. Opening Creator performs recovery. This is local file recovery, not authority to
manage an independently started application. Cross-process attachment remains separately scoped.

Illustration of the accepted field-check interaction, not the saved-file format:

```text
Example: valid customer
Step: Normalize customer
Output: value -> customer.name
Expectation: equals "Ada"
```

Offer compatible comparisons from the selected output shape, with actual versus
expected results shown next to a failure. AssertJ is an interaction inspiration,
not a Java dependency, scripting language or second execution engine. A small
declarative operator set is preferable to arbitrary executable assertion code.
Do not create operator registries or UI abstractions until the first agreed cases
need them. Outputs can be structured; field selection semantics need agreement.

## Acceptance

Checks below are **planned** unless existing evidence is explicitly identified. They must use
a real generated application and the public Creator boundary.

| Requirement | Given / When / Then | Planned evidence / gap |
| --- | --- | --- |
| EX-001, EX-003 | Given an Example with no expectations, when its generated Flow completes without an unexpected error, then Creator reports execution success without invoking any Step itself. | Creator integration/browser case; agree error classification first. |
| EX-001, EX-006 | Given a failing, cancelled or interrupted Example, when Creator receives its result, then it cannot present that execution as successfully completed. | Generated-child fault, cancellation and deadline scenarios; exact labels remain open. |
| EX-002 | Given a saved case with expectations, when the project is reopened, then the same case and referenced IDs are restored independently of presentation metadata. | Persistence/migration tests after schema and stable Example identity are agreed. |
| EX-003, EX-004 | Given a reached Step output and a matching expectation, when Creator checks returned evidence, then validation passes without a second Step invocation. | `CreatorAssertionsE2eTest` checks captured values, unchanged project data and unchanged Step counters through real HTTP. |
| EX-004 | Given a reached Step output that differs, when its expectation is checked, then the case fails and shows the affected Step and actual/expected values. | Exact value checks pass through public HTTP. Visual feedback remains unimplemented. |
| EX-005 | Given an expectation on branch A, when the Example follows branch B, then the case fails because the expected Step was not reached. | `CreatorAssertionsE2eTest.anUntakenBranchDoesNotPassAnAbsenceExpectation`. |
| EX-006 | Given truncated/unavailable output or a replaced child/artifact, when old evidence arrives, then no passing assertion is inferred from it. | `CreatorAssertionsE2eTest` covers truncated traces and application replacement. Saved-suite evidence lifecycle remains open. |
| EX-003 | Given a production build, when its artifact is inspected and run, then no test expectation evaluator, assertion library or Example file is required at runtime. | Extend existing production-omission artifact tests when implementation begins. |
| EX-007 | Execute an Example that creates a new nested field, map it downstream, then select a case where it is absent or different. | Planned public Creator journey; preserve per-case values and absence without type coercion. |
| EX-008 | Execute one branch successfully, reach another Step that fails, and leave a branch unvisited. | Planned; show coverage, completion and expectations separately without treating omitted or retained Steps as demonstrated. |
| EX-009 | Run an Example against an isolated local HTTP fixture, then route a test case around the call using `context.runtime.test` and a literal replacement. | Planned public Flow check; observe a real request only for the non-bypassed case. |
| EX-010 | Select a fake for one Example/Step, then run another case and a production artifact. | Planned after replacement semantics are agreed; verify isolation, truthful evidence and production omission. |
| EX-011 | Run a Trigger Example and inspect both its supplied payload and the downstream result; a Trigger PROCESS side effect must not occur. | `GeneratedApplicationExampleSuiteE2eTest.automaticExampleStartsAtTriggerOutputWithoutInvokingItsIngressHandler` and `GenericTriggerLifecycleE2eTest.developmentLaunchStartsSourcesAndExamplesBypassTriggerProcess` prove output injection alongside independent live ingress. |

Existing tests such as `GeneratedApplicationExampleSuiteE2eTest` and
`GeneratedApplicationVariantsE2eTest` establish execution ownership and production
omission. They do not prove these new expectation or storage behaviors.

## Open Decisions

These block only their dependent implementation slices, not this documentation work:

1. **Resolve now, before compilation-selection work:** agree the meaning of unreachable and
   the explicit keep override in the [owning contract](system-model.md#planned-compilation-selection).
   Demonstrated execution, optional assertions and emitted code are not one test-pass flag.
   Before a release gate is implemented, decide how failed/incomplete execution, coverage gaps,
   failed expectations and explicit retention affect release eligibility; no blanket gate is selected.
2. **File schema and migration:** the initial contract above is implemented and locally verified.
   Dependent scenarios, fixtures and fake-output schemas remain separate future decisions.
3. **Further assertion selection:** the first comparisons and recorded-context selector are
   defined above. Repeated visits already occur through Branch/convergence, but choosing or
   combining their results remains open. The first check boundary refuses that ambiguity.
   Loop behavior and additional AssertJ-inspired comparisons require their own agreed scope.
4. **Before saved validation/release delivery:** the stateless headless boundary is implemented.
   Agree the saved-suite evaluation lifecycle, invalidation and bounded access to all required
   evidence before adding global validation status or a release gate. Do not infer suite success
   from the currently inspected Step alone.
5. **Before dependent Example scenarios:** define how a later Trigger Example receives an ID or
   other result produced by an earlier Example, including across different Triggers. Decide what
   setup and cleanup the cases share, how a failed prerequisite affects its dependents, and which
   cases may run concurrently. Agree dependency, value-transfer and isolation rules before
   selecting a tree, graph, file format or scheduler.

The 2026-10-18 authoring need includes standalone cases, variations of one Trigger event such as
missing, JSON null, empty, blank and unusual characters, and ordered cases such as creating a
record before modifying it. Some cases may share deliberate setup; others must remain isolated.
These are distinct from parallel execution tests. Each case still supplies Trigger output rather
than injecting an Example at an intermediate Step. A repeated input does not imply identical
outcomes or permission to skip execution, especially with external state and side effects.
For example, a Create Example may produce an ID that a later Modify Example on another Trigger
uses in its own input. Creator should distinguish standalone cases from such dependent cases and
show where a generated value came from. A single-parent sequence may look like a tree, but that
does not select a tree-only execution model or automatic sharing of the entire context. The
binding mechanism, shared-state lifetime and failure behavior remain open.
Creator may eventually collapse identical observed section inputs for display, but must retain
each case's identity, route, output, expectations and failure. The exact comparison and display
contract are not selected here.

Expected-error assertions, managed fixtures/test services and protocol-specific test clients
require explicit scope. Real-I/O Examples do not wait for those features. A release-green claim
must never imply that all inputs or external failures have been proven safe.
[Data administration](database-and-data-access.md) permissions do not themselves authorize
automatic test execution against that data.

### Proposed I/O Isolation

The user suggested sequential execution and batching Examples with the same fake data to keep
artifact overhead low. Recommendation awaiting approval for independent cases: reuse expensive
fixture startup, not mutable case state. Each independent Example starts from its declared
baseline; reset or isolate writes,
fake-response cursors, clocks and resources between cases. Failed cleanup invalidates dependent
cases rather than allowing a misleading pass. Sequential execution alone is not isolation.
Sequential Example success also does not prove concurrent production safety; real resource and
concurrency checks remain separate acceptance evidence for the affected capability.

Keep normal routing and value processing identical to production. The accepted output-fake
direction above supersedes the earlier proposal to require a special external-I/O binding.
Its replacement contract must work for third-party Steps too. Production omits development
fixture data, fake dispatch and assertion evaluation; generated-source/artifact inspection and
measured overhead must prove this separation. User-authored test-mode branches remain valid.

Faked runs must be distinguishable from real isolated integration runs. A fake does not prove
protocol, driver, storage or failure behavior. Decide fixture identity, reset guarantees and
safe environment admission before managed fixtures are implemented; grouping by matching
fake payloads alone is insufficient evidence that cases can share a fixture.

The [roadmap](../roadmap.md#current-sequence) owns sequencing. This future scenario/fixture
design does not authorize its runtime implementation or publication. The separately accepted
initial Example-file migration is defined above.
