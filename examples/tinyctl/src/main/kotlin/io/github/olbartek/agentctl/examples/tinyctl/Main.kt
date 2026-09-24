// TinyApp's CLI, and the shape of every host's: an import, the app's config, one call.
//
//   ./tinyctl run "open 2; save"
//   ./tinyctl test
//
// `./tinyctl` at the repository root is the wrapper that rebuilds this executable first (Templates/appctl), and
// `HelpExamples.invocation` spells it that way so the help pages name the command its users actually run.
package io.github.olbartek.agentctl.examples.tinyctl

import io.github.olbartek.agentctl.cli.AgentCtl
import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig

fun main(args: Array<String>): Unit = AgentCtl.main(TinyAppConfig.appCtl, args)
