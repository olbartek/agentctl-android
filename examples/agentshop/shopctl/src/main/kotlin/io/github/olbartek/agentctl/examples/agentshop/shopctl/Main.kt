// AgentShop's CLI: the whole host-side integration is AgentShop's config, handed to the CLI.
//
//   examples/agentshop/appctl run "login-as alice; open 1003; cancel"
//   examples/agentshop/appctl test
//
// `examples/agentshop/appctl` is the wrapper that rebuilds this executable first (Templates/appctl), and
// `HelpExamples.invocation` spells it `./appctl` so the help pages name the command its users actually run.
package io.github.olbartek.agentctl.examples.agentshop.shopctl

import io.github.olbartek.agentctl.cli.AgentCtl
import io.github.olbartek.agentctl.examples.agentshop.ctl.AgentShopConfig

fun main(args: Array<String>): Unit = AgentCtl.main(AgentShopConfig.appCtl, args)
