package io.github.olbartek.agentctl

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What a command, an appearance or a device event does to an MVVM app: a function of its coordinator. A value, so
 * the device configuration can build one without a reference to the running app, which a relaunch replaces.
 */
public fun interface AppIntent<App> {
    /** Does it, on the app's own thread. */
    public fun run(app: App)
}

/**
 * A screen's command in an MVVM app: a function of its own view model. [activeScreen] binds it to the instance on
 * screen and lifts it to the app's [AppIntent].
 */
public fun interface ScreenIntent<VM> {
    /** Does it, on the app's own thread. */
    public fun run(viewModel: VM)
}

/**
 * A screen of an MVVM app: a view model (a Jetpack `ViewModel`, say). Its agent surface is an [AgentScreen] unchanged
 * — path, summary, error, gates and commands are functions of a value, [agentState], exactly as a store screen's are
 * of its state — so the command builders, gates and docs are shared. Its commands are [ScreenIntent]s; the screen is
 * usually the view model's companion object.
 *
 * ```kotlin
 * class ItemsViewModel(environment: AgentEnvironment) : ViewModel(), AgentViewModel<ItemsViewModel.AgentState, ItemsViewModel> {
 *     override val agentState get() = AgentState(items.size, isLoading, error)
 *     override val agentScreen get() = Companion
 *     companion object : AgentScreen<AgentState, ScreenIntent<ItemsViewModel>> { … }
 * }
 * ```
 */
public interface AgentViewModel<S, VM : AgentViewModel<S, VM>> {
    /** The values the summary, the error and the gates read. Read on the app's thread, on every settling round. */
    public val agentState: S

    /** The screen this view model is: its paths, summary, error and commands. */
    public val agentScreen: AgentScreen<S, ScreenIntent<VM>>
}

/** This screen, with its intents bound to this instance and lifted to the app's. */
public fun <S, VM : AgentViewModel<S, VM>, App> VM.activeScreen(): ActiveScreen<AppIntent<App>> {
    val viewModel = this
    return agentScreen.activeScreen(agentState).map { intent -> AppIntent { intent.run(viewModel) } }
}

/**
 * The root of an MVVM app: the coordinator, which owns navigation (the back stack), resolves the screen an agent is
 * looking at (what [AgentContainer] does for a store's root, on an instance), and can cancel everything the app is
 * doing.
 *
 * The rules that keep it deterministic, as a store keeps a reducer: view models run on the app's thread; every clock,
 * id and client comes from the [AgentEnvironment] they are given; async work starts through [AgentTasks]
 * (`environment.tasks`); a child view model is created with its parent's environment; a pop cancels the popped
 * screen's work; stack ids are a counter, not random; `onAppear` is an intent the view also calls.
 */
public interface AgentCoordinator<C : AgentCoordinator<C>> {
    /** The screen on top, with the coordinator's own commands (`back`) appended. */
    public fun activeScreen(): ActiveScreen<AppIntent<C>>

    /** Cancels the work of every view model it holds: a relaunch throws the app away. */
    public fun cancelAllWork()

    /** What `state` prints. The default names the class only. */
    public val agentStateDump: String get() = javaClass.simpleName
}

/**
 * Where an app that starts its own coroutines — an MVVM app's view models — starts them, so the agent runtime sees
 * its work start and finish: settling waits for it, as it waits for a store's effects. Each [AgentEnvironment] has
 * one, on its scope (`environment.tasks`), and a view model launches its work there instead of in `viewModelScope`,
 * which is neither counted nor on the host's dispatcher (headlessly, virtual time):
 *
 * ```kotlin
 * fun refresh() {
 *     loadJob?.cancel()
 *     loadJob = environment.tasks.run {
 *         try {
 *             val items = client.fetch()
 *             if (isActive) this@ItemsViewModel.items = items
 *         } catch (error: CancellationException) {
 *             throw error
 *         } catch (error: Exception) {
 *             if (isActive) this@ItemsViewModel.error = error   // a failure (`mock items.fetch network`) is state
 *         }
 *     }
 * }
 * ```
 *
 * The scope is a supervisor's, so an exception [run]'s block lets out is uncaught: it crashes the app. In the running
 * app the dispatcher is `Dispatchers.Main.immediate`, so a block started on the main thread runs up to its first
 * suspension before [run] returns; headlessly it is always queued. A block should not depend on either, for example
 * by reading the job it is being assigned to before it suspends.
 *
 * In a release build it is the same: a coroutine in the scope, plus one atomic count. A store app does not need it:
 * its effects are counted by the store.
 */
public class AgentTasks(private val scope: CoroutineScope) {
    private val count = AtomicInteger(0)

    /** Jobs started and not yet finished, including ones suspended on the clock (a countdown). */
    public val inFlight: Int get() = count.get()

    /** Starts [block] in the environment's scope, counted until it completes. Keep the job to cancel it. */
    public fun run(block: suspend CoroutineScope.() -> Unit): Job {
        count.incrementAndGet()
        val job = scope.launch(block = block)
        // On completion rather than in a `finally`: a job cancelled before it was dispatched never runs its body, but
        // it always completes.
        job.invokeOnCompletion { count.decrementAndGet() }
        return job
    }
}
