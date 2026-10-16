# The coverage guards

`agentctl-test-support` fails your build when the agent surface drifts from what the app actually does. This is the
suite's own use of it
([`CoverageTest.kt`](../../tests/src/test/kotlin/io/github/olbartek/agentctl/tests/CoverageTest.kt)):

```kotlin
private fun coverage() = AgentCoverage(TinyRootAgent.registry, scenariosDirectory) { TinyAppConfig.headless().makeRunner() }

@Test
fun everyCommandIsUsedByAScenario() {
    assertEquals(emptyList(), coverage().unusedCommands())
}
```

- `unusedCommands()`: a command no scenario ever sends.
- `undocumentedSummaryKeys()`: a key a run emits that the screen's `summaryKeys` does not list.
- `unvisitedScreens()`: a documented screen path no scenario reaches. An `<id>` segment matches any concrete one.
- `unusedDeviceCommands()`: a `device` subcommand, or a push, the app configures and no scenario sends.

The constructor throws `NoScenariosFound` when the directory holds no `*.appctl` files; otherwise all four guards
would pass having examined nothing.

The guards are deliberately shallow: they check that a command *appears* in some script, not that its result was
asserted.

## The scenario checks

`AgentScenarioChecks` is the rest of what every host's tests used to write for themselves: the unit tests alone
catch what `test` and `docs --check` catch, and the promises the agent docs make hold for your app's own wiring. It
takes your config, finds the repo root by the config's root marker (walking up from the test's working directory, a
Gradle test task's module directory; `RepoRoot.find(marker, start)` is the same walk the CLI makes), and reads the
scenarios at `scenariosPath`. Each check returns its problems as readable lines, empty when there are none
([`ScenarioTest.kt`](../../tests/src/test/kotlin/io/github/olbartek/agentctl/tests/ScenarioTest.kt)):

```kotlin
@Test
fun scenariosAreDeterministic() {
    val problems = AgentScenarioChecks(TinyAppConfig.appCtl).deterministic(runs = 10)
    assertTrue(problems.isEmpty(), problems.joinToString("\n"))
}
```

- `allPass()`: every scenario passes headlessly.
- `endWithExpect()`: every scenario's last command is an `expect`.
- `deterministic(runs)`: each scenario prints byte-identical steps across fresh runs.
- `sessionReplayMatches(parts)`: each scenario, split into parts resumed the way `run --session` resumes them,
  prints the same steps and ends in the same state as one run.
- `docsCurrent(root)`: the file at `docsPath` is what `docs` would write (the same `AppCtlConfig.docsMarkdown`).
- `readmeListsAll(readme)`: a README (`scenarios/README.md` by default) names every scenario file in backticks, and
  no file that is gone.
- `noStepShows(secrets, personalCommands)`: no step's output (the echo of the command aside) contains a secret, or a
  value a scenario types into one of `personalCommands`, ignoring case. The values stay in your test.

Every headless run has its own virtual-time dispatcher, so the checks may run beside other tests. The constructor
throws `NoScenariosFound` when there are no scenario files, like `AgentCoverage`, and `RepoRootNotFound` when no
directory above the start holds the marker.
