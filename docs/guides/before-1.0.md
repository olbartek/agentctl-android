# Before 1.0

Until 1.0, a minor version may change the API: a host that moves to it follows its "Moving a host to X.Y" notes in
[Releases](releases.md). The scripts, scenarios and step output change only with CONTRACT.md, in a minor release of
both libraries.

These are to be settled by 1.0:

- **`AgentLaunchSession` gets one meaning in both ports.** Here it is the Android-free piece below `AgentLaunch`; in
  agentctl-ios it is the launch an app keeps, the role `AgentLaunch` plays here
  ([the platform differences](platform-differences.md) map the two). At 1.0 the two ports agree on one name for the
  launch an app keeps, and one for the lower-level piece.
- **`BridgeServer`'s tokenless constructors** from 0.8, deprecated since 0.9, go. Code built against them still
  links, and a server made with one checks every request but the token; the reference's `BridgeServer` has always
  taken a token.
