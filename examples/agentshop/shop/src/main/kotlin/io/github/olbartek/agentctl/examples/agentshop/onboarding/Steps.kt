package io.github.olbartek.agentctl.examples.agentshop.onboarding

import io.github.olbartek.agentctl.AgentCommand
import io.github.olbartek.agentctl.AgentScreen
import io.github.olbartek.agentctl.CommandGate
import io.github.olbartek.agentctl.Effect
import io.github.olbartek.agentctl.Reducer
import io.github.olbartek.agentctl.SummaryItem
import io.github.olbartek.agentctl.examples.agentshop.clients.AccountClient
import io.github.olbartek.agentctl.examples.agentshop.models.AccountError
import io.github.olbartek.agentctl.examples.agentshop.models.AccountProfile
import io.github.olbartek.agentctl.examples.agentshop.models.Address
import io.github.olbartek.agentctl.examples.agentshop.models.Coded
import io.github.olbartek.agentctl.examples.agentshop.models.OnboardingAnswers
import io.github.olbartek.agentctl.examples.agentshop.models.Outcome
import io.github.olbartek.agentctl.examples.agentshop.models.ProductCategory
import io.github.olbartek.agentctl.examples.agentshop.models.attempt
import io.github.olbartek.agentctl.examples.agentshop.models.codeChoices
import io.github.olbartek.agentctl.examples.agentshop.models.isValidZip
import io.github.olbartek.agentctl.invalidArgument
import io.github.olbartek.agentctl.next

/** The welcome carousel: three pages, then interests. "Skip" goes straight to interests. */
object Welcome {
    const val PAGE_COUNT: Int = 3

    /** @param page 1-based, as a shopper reads "1 of 3". */
    data class State(val page: Int = 1)

    sealed interface Action {
        data object NextTapped : Action
        data object SkipTapped : Action
        data object BackTapped : Action

        sealed interface Delegate : Action {
            data object Finished : Delegate
        }
    }

    val reducer: Reducer<State, Action> = Reducer { state, action ->
        when (action) {
            Action.NextTapped ->
                if (state.page < PAGE_COUNT) next(state.copy(page = state.page + 1)) else next(state, Effect.send(Action.Delegate.Finished))
            Action.SkipTapped -> next(state, Effect.send(Action.Delegate.Finished))
            Action.BackTapped -> next(state.copy(page = maxOf(1, state.page - 1)))
            is Action.Delegate -> next(state)
        }
    }
}

object WelcomeAgent : AgentScreen<Welcome.State, Welcome.Action> {
    override val screenPaths: List<String> = listOf("onboarding/welcome")

    override fun screenPath(state: Welcome.State): String = "onboarding/welcome"

    override val summaryKeys: List<String> = listOf("page", "pages")

    override fun summary(state: Welcome.State): List<SummaryItem> =
        listOf(SummaryItem("page", state.page), SummaryItem("pages", Welcome.PAGE_COUNT))

    override val commands: List<AgentCommand<Welcome.State, Welcome.Action>> = listOf(
        AgentCommand.action("next", help = "The next page; on the last one, go on to interests.", action = Welcome.Action.NextTapped),
        AgentCommand.action("skip", help = "Skip the introduction and go to interests.", action = Welcome.Action.SkipTapped),
        // The carousel's own back: the previous page. It shadows the flow's `back`, which has nothing before welcome.
        AgentCommand.action(
            "back",
            help = "The previous page.",
            action = Welcome.Action.BackTapped,
            gate = CommandGate("page=1") { it.page > 1 },
        ),
    )
}

/**
 * Pick what you like: at least two categories, at most four. Picking a fifth is refused with `tooMany` instead of
 * being ignored.
 */
object Interests {
    const val MINIMUM: Int = 2
    const val MAXIMUM: Int = 4

    enum class Error(override val code: String) : Coded { TOO_MANY("tooMany") }

    data class State(
        /** In the order they were picked. */
        val selected: List<ProductCategory> = emptyList(),
        val error: Error? = null,
    ) {
        val canContinue: Boolean get() = selected.size >= MINIMUM
    }

    sealed interface Action {
        data class Toggled(val category: ProductCategory) : Action
        data object ContinueTapped : Action

        sealed interface Delegate : Action {
            data class Chose(val interests: List<ProductCategory>) : Delegate
        }
    }

    val reducer: Reducer<State, Action> = Reducer { state, action ->
        when (action) {
            is Action.Toggled -> when {
                action.category in state.selected -> next(state.copy(selected = state.selected - action.category, error = null))
                state.selected.size >= MAXIMUM -> next(state.copy(error = Error.TOO_MANY))
                else -> next(state.copy(selected = state.selected + action.category, error = null))
            }
            Action.ContinueTapped ->
                if (state.canContinue) next(state, Effect.send(Action.Delegate.Chose(state.selected))) else next(state)
            is Action.Delegate -> next(state)
        }
    }
}

object InterestsAgent : AgentScreen<Interests.State, Interests.Action> {
    private val categories = codeChoices<ProductCategory>()

    override val screenPaths: List<String> = listOf("onboarding/interests")

    override fun screenPath(state: Interests.State): String = "onboarding/interests"

    override val summaryKeys: List<String> = listOf("selected", "interests", "canContinue")

    override fun summary(state: Interests.State): List<SummaryItem> = listOf(
        SummaryItem("selected", state.selected.size),
        SummaryItem("interests", if (state.selected.isEmpty()) "none" else state.selected.joinToString(",") { it.code }),
        SummaryItem("canContinue", state.canContinue),
    )

    override fun errorCode(state: Interests.State): String? = state.error?.code

    override val commands: List<AgentCommand<Interests.State, Interests.Action>> = listOf(
        AgentCommand.parsing(
            "toggle",
            argument = "<$categories>",
            help = "Pick or unpick a category (${Interests.MINIMUM}–${Interests.MAXIMUM}); a fifth reports error=tooMany.",
        ) { text ->
            val category = ProductCategory.ofCode(text) ?: invalidArgument("expected $categories")
            Interests.Action.Toggled(category)
        },
        AgentCommand.action(
            "continue",
            help = "Save the picks and go on to the address.",
            action = Interests.Action.ContinueTapped,
            gate = CommandGate("canContinue=false") { it.canContinue },
        ),
    )
}

/**
 * A shipping address, saved for checkout. Optional: "Skip" moves on without one. "Continue" needs every field, and a
 * zip that is not five digits is refused with `invalidZip`.
 */
object AddressForm {
    enum class Error(override val code: String) : Coded { INVALID_ZIP("invalidZip") }

    data class State(val address: Address = Address(), val error: Error? = null) {
        val canContinue: Boolean get() = address.isComplete
    }

    sealed interface Action {
        data class NameChanged(val name: String) : Action
        data class StreetChanged(val street: String) : Action
        data class CityChanged(val city: String) : Action
        data class ZipChanged(val zip: String) : Action
        data object ContinueTapped : Action
        data object SkipTapped : Action

        sealed interface Delegate : Action {
            /** `address` is `null` when skipped. */
            data class Finished(val address: Address?) : Delegate
        }
    }

    val reducer: Reducer<State, Action> = Reducer { state, action ->
        when (action) {
            is Action.NameChanged -> next(state.copy(address = state.address.copy(name = action.name), error = null))
            is Action.StreetChanged -> next(state.copy(address = state.address.copy(street = action.street), error = null))
            is Action.CityChanged -> next(state.copy(address = state.address.copy(city = action.city), error = null))
            is Action.ZipChanged -> next(state.copy(address = state.address.copy(zip = action.zip), error = null))
            Action.ContinueTapped -> when {
                !state.canContinue -> next(state)
                !isValidZip(state.address.zip) -> next(state.copy(error = Error.INVALID_ZIP))
                else -> next(state, Effect.send(Action.Delegate.Finished(state.address)))
            }
            Action.SkipTapped -> next(state, Effect.send(Action.Delegate.Finished(null)))
            is Action.Delegate -> next(state)
        }
    }
}

object AddressFormAgent : AgentScreen<AddressForm.State, AddressForm.Action> {
    override val screenPaths: List<String> = listOf("onboarding/address")

    override fun screenPath(state: AddressForm.State): String = "onboarding/address"

    override val summaryKeys: List<String> = listOf("name", "street", "city", "zip", "canContinue")

    override fun summary(state: AddressForm.State): List<SummaryItem> = listOf(
        SummaryItem("name", state.address.name),
        SummaryItem("street", state.address.street),
        SummaryItem("city", state.address.city),
        SummaryItem("zip", state.address.zip),
        SummaryItem("canContinue", state.canContinue),
    )

    override fun errorCode(state: AddressForm.State): String? = state.error?.code

    override val commands: List<AgentCommand<AddressForm.State, AddressForm.Action>> = listOf(
        AgentCommand.text("name", help = "Set the full name.") { AddressForm.Action.NameChanged(it) },
        AgentCommand.text("street", help = "Set the street.") { AddressForm.Action.StreetChanged(it) },
        AgentCommand.text("city", help = "Set the city.") { AddressForm.Action.CityChanged(it) },
        AgentCommand.text("zip", help = "Set the zip code (five digits).") { AddressForm.Action.ZipChanged(it) },
        AgentCommand.action(
            "continue",
            help = "Save the address for checkout; a zip that isn't five digits reports error=invalidZip.",
            action = AddressForm.Action.ContinueTapped,
            gate = CommandGate("canContinue=false") { it.canContinue },
        ),
        AgentCommand.action("skip", help = "Go on without an address.", action = AddressForm.Action.SkipTapped),
    )
}

/**
 * The last onboarding step: allow notifications or not. Either answer saves the whole onboarding
 * (`account.completeOnboarding`); a failure keeps the shopper here with `retry`.
 */
object Notifications {
    enum class Choice(override val code: String) : Coded {
        ALLOWED("allowed"),
        OFF("off"),
    }

    data class State(
        /** What the earlier steps collected, saved together with the choice. */
        val interests: List<ProductCategory> = emptyList(),
        val address: Address? = null,
        val choice: Choice? = null,
        val isLoading: Boolean = false,
        val error: AccountError? = null,
    )

    sealed interface Action {
        data class Chose(val choice: Choice) : Action
        data object RetryTapped : Action
        data class Completed(val result: Outcome<AccountProfile, AccountError>) : Action

        sealed interface Delegate : Action {
            data class Finished(val profile: AccountProfile) : Delegate
        }
    }

    fun reducer(accountClient: AccountClient): Reducer<State, Action> {
        fun save(state: State): io.github.olbartek.agentctl.Next<State, Action> {
            val answers = OnboardingAnswers(state.interests, state.address, notifications = state.choice == Choice.ALLOWED)
            return next(
                state.copy(isLoading = true, error = null),
                Effect.run { send -> send(Action.Completed(attempt(AccountError::of) { accountClient.completeOnboarding(answers) })) },
            )
        }

        return Reducer { state, action ->
            when (action) {
                is Action.Chose -> if (state.isLoading) next(state) else save(state.copy(choice = action.choice))
                Action.RetryTapped -> if (state.error == null || state.isLoading) next(state) else save(state)
                is Action.Completed -> when (val result = action.result) {
                    is Outcome.Success -> next(state.copy(isLoading = false), Effect.send(Action.Delegate.Finished(result.value)))
                    is Outcome.Failure -> next(state.copy(isLoading = false, error = result.error))
                }
                is Action.Delegate -> next(state)
            }
        }
    }
}

object NotificationsAgent : AgentScreen<Notifications.State, Notifications.Action> {
    override val screenPaths: List<String> = listOf("onboarding/notifications")

    override fun screenPath(state: Notifications.State): String = "onboarding/notifications"

    override val summaryKeys: List<String> = listOf("notifications", "loading")

    override fun summary(state: Notifications.State): List<SummaryItem> = listOf(
        SummaryItem("notifications", state.choice?.code ?: "undecided"),
        SummaryItem("loading", state.isLoading),
    )

    override fun errorCode(state: Notifications.State): String? = state.error?.code

    override val commands: List<AgentCommand<Notifications.State, Notifications.Action>> = listOf(
        AgentCommand.action(
            "allow",
            help = "Allow notifications and finish onboarding (account.completeOnboarding).",
            action = Notifications.Action.Chose(Notifications.Choice.ALLOWED),
        ),
        AgentCommand.action(
            "not-now",
            help = "Finish onboarding without notifications.",
            action = Notifications.Action.Chose(Notifications.Choice.OFF),
        ),
        AgentCommand.action(
            "retry",
            help = "Save onboarding again after a failure.",
            action = Notifications.Action.RetryTapped,
            gate = CommandGate("error=none") { it.error != null },
        ),
    )
}
