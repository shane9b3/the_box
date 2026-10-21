# Bound user regex work and LAN overload resources

Date: 2026-10-20. Security reviewer proposed; performance reviewer independently reviewed; lead approved the engine
dependency and integration. Applies to the current audit's findings 4 and 12 and supersedes the Java/ICU regex
blacklist in M5 §3.7.3 / M3 §3.12.2. Other provider protocol parsers keep their fixed, application-owned regexes.

## User patterns

Use `com.google.re2j:re2j:1.8` behind `core.text.UserRegex` for custom channel-name prefixes, hide rules and prefix
editor validation. RE2/J is pure Java and matches in time linear in the input size; Android and JVM use the same
engine. Its regular-language subset supports the common anchors, groups, alternation, character classes, repetitions,
word boundaries and case-insensitive matching used by existing rules. There is no fallback to Java/ICU.

The prior blacklist did not cover transparent groups, optional separators, bounded repetition variants or unanchored
search costs. Extending an ad hoc blacklist cannot establish bounded work; implementing a parser/NFA ourselves would
add security-critical code. The small dependency is justified by removing this execution risk. The old shape blacklist
is retired, so expressions that were formerly rejected only for backtracking are now supported safely.

`RegexSafety` now bounds compilation before invoking the engine: source length at most 200 characters (the existing
hide-rule limit, now also enforced for prefixes), a conservative product of written numeric repetition counts at most
1,000, and source length × that product at most 4,096. Counting independent or quoted numeric brace expressions also
uses this budget; some harmless large expressions are deliberately refused. A post-compilation program-size cap of
4,096 instructions is defense in depth. These bounds prevent nested counted repeats from expanding into a huge
compiled program. Prefix matching still sees at most 256 leading name characters. Hide rules retain their 50 ms page
budget, now checked through the pure-Java input sequence during matching on Android as well as JVM.

Lookaround, backreferences, possessive quantifiers and other unsupported Java syntax fail validation with the existing
invalid-pattern message. A saved/imported unsupported prefix logs a redacted reason and falls back to Auto; a saved
hide rule is disabled as `BAD_REGEX` on its next application, with the normal disabled-rule UI. No stored pattern is
silently rewritten. Compiling an over-budget rule reports the existing too-complex/slow outcome; prefix edits remain
open with an invalid-pattern message. RE2 Unicode shorthand/word-boundary semantics are its own, rather than full
Java/ICU syntax parity; Unicode case-insensitive literals and explicit Unicode categories remain supported.

The RE2/J BSD license ships in the Licences page. Primary references: [RE2/J design and supported syntax caveats](https://github.com/google/re2j),
[1.8 release](https://github.com/google/re2j/releases/tag/re2j-1.8),
[upstream license](https://github.com/google/re2j/blob/re2j-1.8/LICENSE).

## LAN connection rejection

Close excess accepted sockets directly on the accept thread, without reading/writing streams or starting another
thread. The normal fixed pool, active-connection tracking, pairing checks and watchdog remain. Under overload a
browser now sees a closed connection and can retry, instead of a best-effort 503 from an untracked worker. This bounds
rejection work even when the peer does not read. A deterministic test occupies the sole worker, floods 128 rejected
connections, and verifies all are closed without touching streams or entering the handler.

If stop shuts down the pool between accept and worker submission, executor rejection also closes the unserved socket.
A separate deterministic test delivers an accepted socket after pool shutdown and verifies that cleanup.

## Validation

Core tests cover input-read work across grouped/optional/bounded/unanchored adversarial variants, ordinary prefix
behavior, Unicode case folding/UTF-16 offsets, unsupported syntax, structural budgets and editor validation. Data tests
cover long adversarial names, deadlines and the preview/disable migration for unsupported stored rules. Central Gradle
validation is recorded by the lead in the audit; no on-device timing claim is made by these unit tests.
