# The contract

[`CONTRACT.md`](../../CONTRACT.md) is copied unchanged from agentctl-ios; the Swift package is its reference
implementation; a change to it lands there first and is always a minor release of both libraries. It specifies:

- the script language and command resolution;
- the runtime commands (`expect`, `advance`, `mock`, and `device` when the app configures one);
- the exact step output and the exit codes;
- the determinism requirements;
- the bridge protocol.

On the contract's open questions, this port follows the reference in every case, so fixtures are shared:

- escapes are generic;
- a duration takes a single unit;
- summary values are not escaped;
- settling uses the same thresholds;
- the JSON uses sorted keys laid out as Foundation's encoder lays them out;
- the "Valid here" list ends in the same tail.

It also counts script columns in grapheme clusters, and compares a script's text by canonical equivalence
(`AgentText`), as Swift does. Two public helpers have no Swift counterpart, because the modules that need them are
separate here: `AgentText` (Swift's `String ==` is canonical already) and `ScriptParser.read` (strict UTF-8, as
`String(contentsOf:encoding: .utf8)` reads).
