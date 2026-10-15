// TinyMVVM's CLI: the same one call as TinyApp's, on an MVVM app's config.
//
//   ./tinymvvmctl run "open 2; save"
//   ./tinymvvmctl test
package io.github.olbartek.agentctl.examples.tinymvvmctl

import io.github.olbartek.agentctl.cli.AgentCtl
import io.github.olbartek.agentctl.examples.tinymvvm.TinyMVVMConfig

fun main(args: Array<String>): Unit = AgentCtl.main(TinyMVVMConfig.appCtl, args)
