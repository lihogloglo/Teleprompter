# Page-back commands during voice following

Decision: 2026-09-06. English **Command rewind**, French **Commande recul**.

## Prior art and existing evidence

- Amazon's [voice invocation design guidance](https://developer.amazon.com/en-US/docs/alexa/interaction-model-design/design-the-invocation-name-for-your-skill.html)
  recommends expressions that are easy to pronounce and phonetically distinct,
  warns about unfamiliar invented words and alternative written forms, and
  recommends repeated spoken testing. This informs phrase choice; it does not
  demonstrate accuracy on this app's sherpa-onnx models.
- SPEC section 5.3 records device trouble with both multiword commands and
  fragmented recognition of `rembobine` (`ram bobine`, `rambobine`). Rarity alone
  does not establish recognition quality. Preserve the existing standalone mode
  and its tolerances rather than changing its established behavior.
- The existing Matcher withholds commitment of the last growing partial word.
  Reuse that convention for commands so `command rewind` growing into
  `command rewinding` does not cause an irreversible false jump.

## Implementation contract

Follow voice automatically recognizes its two-word command while playing, armed,
or paused. No separate command-mode switch is needed. Both words must occur in
one utterance, consecutively, with exact normalized matches. French also accepts
`commande recule`, the homophonic verb spelling. No approximate spelling match,
word-fragment joining, or single-word aliases are enabled in follow mode.

Hold possible command prefixes out of script tracking until resolved. Fire when
the second word is followed by another word or the utterance is finalized, once
per occurrence. Blank confirmed commands and earlier utterance words, preserving
indices and timestamps for Matcher deduplication and trace replay. New narration
after the command can be matched in the same utterance.

Move back one reading viewport per occurrence, clamped to the beginning. Reanchor
before the first word at the new eyeline, clear old match evidence, reset scroll
motion, and hold until script reading advances the anchor. Preserve play/pause
and armed state. Touch/drag takes priority; commands do not move the script while
the user holds or flings it, or while the session summary is displayed.

## Validation and limits

JVM regression coverage exercises both languages, ordinary narration and near
misses, partial revisions, deduplication, repeated commands, command-word
exclusion, retake/resume through the actual Matcher, and trace serialization.

These phrases remain reserved: reading the exact command from a script also
triggers it. No phrase is collision-proof. Spoken testing on the installed
English/French models with different speakers, microphone distances, background
noise, and normal narration is still needed to measure misses and false
triggers. Automated transcript tests do not establish acoustic accuracy.
