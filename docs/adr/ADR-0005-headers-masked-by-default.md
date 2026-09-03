# ADR-0005: Logged header values are masked by default; plaintext is an explicit allowlist

- **Status:** accepted
- **Date:** 2026-09-03
- **Context:** A header section had three independent lists - `includes`
  (what is logged), `excludes` (what is removed from that), `masked` (whose
  values are fingerprinted) - and `masked` defaulted to EMPTY. `masked: []`
  is harmless as long as `includes` stays empty; but the documented
  debugging move `includes: ["*"]` then logs every header the message
  carries in plaintext, because masking is a separate, equally empty list.
  Two independent switches whose unsafe combination is the convenient one.
  The fingerprint is not anonymisation either: it is a stable pseudonym,
  and only the keyed variant (`masking-key`, HMAC) stops a reader from
  confirming a guess - so the question is not only whether to mask by
  default, but how the documentation names what masking does.

## Decision

**Whatever a section logs is masked unless its name is explicitly allowed
in plaintext:**

1. `masked` defaults to `["*"]`. Narrowing it to explicit names remains
   possible; emptying it switches masking off for the section - a visible
   decision written into the configuration, never the side effect of
   another list.
2. A new list `unmasked` names the logged headers that appear in plaintext
   although `masked` covers them: the allowlist of harmless names
   (`Content-Type`, `Accept`, a correlation id). An unmasked name always
   wins over a masked one.
3. `unmasked` rejects the `*` wildcard at binding time, like `excludes`
   does. The plaintext set is an explicit list of names by design; the
   one-token way back to plaintext-everything is `masked: []`, which reads
   as what it is.
4. The documentation calls the fingerprint what it is: a pseudonym that
   keeps equal values recognisable as equal (the point: correlation) and
   therefore lets a reader confirm a guessed value unless the fingerprint
   is keyed (`masking-key`) - never "anonymised".

The rule holds for both twins, both directions, and the sibling project
limesium, whose configuration namespace mirrors this one.

## Consequences

- `includes: ["*"]` now costs readability, not confidentiality: the debug
  line shows fingerprints, and the operator adds the handful of names worth
  reading to `unmasked`.
- Behavioural change for every existing configuration that lists headers
  in `includes` without `masked`: those values are fingerprinted from now
  on. Migration: name the harmless ones in `unmasked`, or set `masked: []`
  to restore the old rendering knowingly.
- The property surface grows by one list per section; the reference
  configuration and the lockstep tests carry it, and the fuzz target for
  header masking asserts the new precedence (unmasked wins over masked).
