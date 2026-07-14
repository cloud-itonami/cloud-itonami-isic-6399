# wasm/ — kotoba-wasm deployment of the displayed-compensation-mismatch check

`displayed_compensation.kotoba` is a port of `jobsearchops.registry/
displayed-compensation-matches-claim?`'s pure ground-truth comparison —
does a posting's own displayed compensation equal source-hourly-wage x
source-monthly-hours? (see `src/jobsearchops/registry.cljc` lines ~47-62,
consumed by `src/jobsearchops/governor.cljc`'s
`displayed-compensation-mismatch-violations`, lines ~251-265) — into the
minimal `.kotoba` language subset, compiled to a real WASM module via
`kotoba wasm emit`, and hosted via `kototama.tender`
(`test/wasm/displayed_compensation_test.clj`).

This follows the same `kotoba wasm emit` → `kototama.tender` pattern
already proven by `cloud-itonami-isic-6492`'s `wasm/affordability.kotoba`,
`cloud-itonami-isic-6511`'s `wasm/underwriting_decision.kotoba`,
`cloud-itonami-isic-6512`'s `wasm/claim_coverage.kotoba` and
`cloud-itonami-isic-6630`'s `wasm/fee_accrual.kotoba` (ADR-2607062330
addendum 5) — another sibling actor's hot-path decision function ported
to real WASM.

## Why the source differs from `jobsearchops.registry`

The `.kotoba` compiler's actual WASM code-generator supports only a small,
empirically-verified subset: the special forms `do`/`let`/`if` plus
`+ - * quot / rem mod = < > <= >= zero? not inc dec` (confirmed by reading
`compile-wasm-expr` in `kotoba-lang/kotoba/src/kotoba/runtime.clj` — no
`pos?`/`neg?`/`and`/`or`/`when`, unlike the broader tree-walking
interpreter). The port therefore:

- Ports ONLY the pure ground-truth recompute-and-compare — `registry.cljc`'s
  `compute-displayed-compensation` (`source-hourly-wage x
  source-monthly-hours`) folded directly into a single equality check —
  never `governor.cljc`'s store lookups (`store/posting`) or its op-gate
  (`content-gated-ops` dispatch), both of which stay in Clojure and never
  get ported (no maps, no protocols, no op-keyword dispatch in the
  wasm-compilable subset).
- Uses plain positional integer args instead of `{:keys [...]}` map
  destructuring (no maps in the wasm-compilable subset).
- Drops `registry.cljc`'s `(double ...)` casts entirely: every real
  `:source-hourly-wage`/`:source-monthly-hours`/`:displayed-compensation`
  fixture in this repo (`src/jobsearchops/store.cljc`,
  `test/jobsearchops/registry_test.clj`) is an exact-integer yen amount
  (yen has no sub-unit) and an integer hour count — the `double` cast in
  the JVM source exists only so `==` compares a `long` product against a
  literal-`.0` fixture value, not because the domain has genuine cents/
  fractional-hour precision. A plain `i32` `=` on the same integers is
  therefore an exact, not an approximate, reapplication — no floats
  needed, consistent with `cloud-itonami-isic-6492`/`-6512`/`-6630`'s own
  convention of representing amounts as plain integers in the smallest
  currency unit.

This is a direct sibling of `claim_coverage.kotoba` (one comparison, no
multi-term formula) but with a multiplication folded into the comparison
instead of a bare `<=` — closer in shape to `affordability.kotoba`'s
cross-multiplication, minus the zero-guard branch (no floor/ceiling
division here, so there is no `/`-by-zero to guard against — a zero
`source-monthly-hours` just recomputes to `0`, which is exercised by the
`displayed-compensation-wasm-handles-zero-hours` test).

## ABI — parameterized invocation

`kotoba wasm emit` rejects any `main` with parameters (`:main-arity` — the
compiler only ever exports a 0-arity `main`, see `compile-wasm-expr` in
`kotoba-lang/kotoba/src/kotoba/runtime.clj`), so real inputs are passed
through the guest's exported linear memory instead — the same convention
`cloud-itonami-isic-6492`/`-6511`/`-6512`/`-6630` use. A host writes three
little-endian i32 values before calling `main()`:

| offset | field                     |
|--------|---------------------------|
| 0      | `source-hourly-wage`      |
| 4      | `source-monthly-hours`    |
| 8      | `displayed-compensation`  |

`main()` returns `1` (displayed compensation matches the independent
recompute — no violation on this check) or `0` (mismatch — a HARD
`:displayed-compensation-mismatch` violation per `jobsearchops.governor`).
Both offsets are well below `heap-base` (2048), so they never collide with
anything the compiler itself places in memory.

## Rebuilding

```sh
cd ../../kotoba-lang/kotoba   # sibling checkout, west-managed
bin/kotoba-clj wasm emit ../../cloud-itonami/cloud-itonami-isic-6399/wasm/displayed_compensation.kotoba \
  --package-lock kotoba.lock.edn \
  --output ../../cloud-itonami/cloud-itonami-isic-6399/wasm/displayed_compensation.wasm --json
```

Fleet deployment: not attempted in this pass — see cloud-itonami-isic-6492/6511 for the established pattern.
