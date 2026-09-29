package io.github.olbartek.agentctl.examples.agentshop.onboarding

import io.github.olbartek.agentctl.examples.agentshop.TestStore
import io.github.olbartek.agentctl.examples.agentshop.clients.AccountClient
import io.github.olbartek.agentctl.examples.agentshop.clients.MockAccounts
import io.github.olbartek.agentctl.examples.agentshop.models.AccountError
import io.github.olbartek.agentctl.examples.agentshop.models.AccountException
import io.github.olbartek.agentctl.examples.agentshop.models.AccountProfile
import io.github.olbartek.agentctl.examples.agentshop.models.Address
import io.github.olbartek.agentctl.examples.agentshop.models.OnboardingAnswers
import io.github.olbartek.agentctl.examples.agentshop.models.ProductCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WelcomeTest {
    @Test
    fun pagesThenFinish() {
        val store = TestStore(Welcome.State(), reducer = Welcome.reducer)
        store.send(Welcome.Action.NextTapped).send(Welcome.Action.NextTapped)
        assertEquals(3, store.state.page)
        store.send(Welcome.Action.BackTapped)
        assertEquals(2, store.state.page)
        store.send(Welcome.Action.NextTapped).send(Welcome.Action.NextTapped)
        assertEquals(3, store.state.page)
        assertEquals(Welcome.Action.Delegate.Finished, store.received.last())
    }

    @Test
    fun skipAndTheFirstPagesBack() {
        val store = TestStore(Welcome.State(), reducer = Welcome.reducer)
        assertEquals("page=1", WelcomeAgent.activeScreen(store.state).command("back")?.disabledReason)
        store.send(Welcome.Action.BackTapped)
        assertEquals(1, store.state.page)
        store.send(Welcome.Action.SkipTapped)
        assertEquals(Welcome.Action.Delegate.Finished, store.received.last())
    }
}

class InterestsTest {
    @Test
    fun twoToFour() {
        val store = TestStore(Interests.State(), reducer = Interests.reducer)
        store.send(Interests.Action.Toggled(ProductCategory.SHOES))
        assertFalse(store.state.canContinue)
        store.send(Interests.Action.ContinueTapped)
        assertFalse(store.received.any { it is Interests.Action.Delegate })
        for (category in listOf(ProductCategory.BAGS, ProductCategory.WATCHES, ProductCategory.HOME)) store.send(Interests.Action.Toggled(category))
        store.send(Interests.Action.Toggled(ProductCategory.JACKETS))
        assertEquals(Interests.Error.TOO_MANY, store.state.error)
        assertEquals(4, store.state.selected.size)
        store.send(Interests.Action.Toggled(ProductCategory.BAGS))
        assertNull(store.state.error)
        assertEquals(listOf(ProductCategory.SHOES, ProductCategory.WATCHES, ProductCategory.HOME), store.state.selected)
        store.send(Interests.Action.ContinueTapped)
        assertEquals(Interests.Action.Delegate.Chose(store.state.selected), store.received.last())
        assertEquals(
            "<shoes|bags|watches|jackets|accessories|home>",
            InterestsAgent.activeScreen(store.state).command("toggle")?.argument,
        )
    }
}

class AddressFormTest {
    private val address = Address("Nina", "2 Elm St", "Portland", "9720")

    @Test
    fun zipIsValidatedOnContinue() {
        val store = TestStore(AddressForm.State(address), reducer = AddressForm.reducer)
        store.send(AddressForm.Action.ContinueTapped)
        assertEquals(AddressForm.Error.INVALID_ZIP, store.state.error)
        store.send(AddressForm.Action.ZipChanged("97201"))
        assertNull(store.state.error)
        store.send(AddressForm.Action.ContinueTapped)
        assertEquals(AddressForm.Action.Delegate.Finished(address.copy(zip = "97201")), store.received.last())
    }

    @Test
    fun incompleteCannotContinueButCanSkip() {
        val store = TestStore(AddressForm.State(Address(name = "Nina")), reducer = AddressForm.reducer)
        assertFalse(store.state.canContinue)
        store.send(AddressForm.Action.ContinueTapped)
        assertFalse(store.received.any { it is AddressForm.Action.Delegate })
        store.send(AddressForm.Action.SkipTapped)
        assertEquals(AddressForm.Action.Delegate.Finished(null), store.received.last())
    }
}

class NotificationsTest {
    @Test
    fun failureThenRetrySavesTheSameAnswers() {
        val saved = mutableListOf<OnboardingAnswers>()
        var fail = true
        val client = AccountClient(completeOnboarding = { answers ->
            saved.add(answers)
            if (fail) throw AccountException(AccountError.NETWORK)
            AccountProfile(needsOnboarding = false, interests = answers.interests, address = answers.address)
        })
        val start = Notifications.State(interests = listOf(ProductCategory.BAGS, ProductCategory.HOME))
        val store = TestStore(start, reducer = Notifications.reducer(client))
        assertEquals("error=none", NotificationsAgent.activeScreen(store.state).command("retry")?.disabledReason)
        store.send(Notifications.Action.Chose(Notifications.Choice.ALLOWED))
        assertEquals(start.copy(choice = Notifications.Choice.ALLOWED, error = AccountError.NETWORK), store.state)
        fail = false
        store.send(Notifications.Action.RetryTapped)
        assertEquals(
            listOf(OnboardingAnswers(start.interests, null, true), OnboardingAnswers(start.interests, null, true)),
            saved,
        )
        assertEquals(Notifications.Action.Delegate.Finished(AccountProfile(false, start.interests, null)), store.received.last())
    }
}

class OnboardingFlowTest {
    private val nina = MockAccounts.session(MockAccounts.nina.user)

    @Test
    fun stepsCarryAnswersForward() {
        val address = Address("Nina", "2 Elm St", "Portland", "97201")
        var answers: OnboardingAnswers? = null
        val client = AccountClient(completeOnboarding = { answers = it; AccountProfile(false, it.interests, it.address) })
        val store = TestStore(OnboardingFlow.State(nina), reducer = OnboardingFlow.reducer(client))
        store.send(OnboardingFlow.Action.Welcome(Welcome.Action.SkipTapped))
        assertEquals(OnboardingFlow.Step.INTERESTS, store.state.step)
        store.send(OnboardingFlow.Action.Interests(Interests.Action.Toggled(ProductCategory.BAGS)))
            .send(OnboardingFlow.Action.Interests(Interests.Action.Toggled(ProductCategory.HOME)))
            .send(OnboardingFlow.Action.Interests(Interests.Action.ContinueTapped))
        assertEquals(OnboardingFlow.Step.ADDRESS, store.state.step)
        // Back keeps what was picked.
        store.send(OnboardingFlow.Action.BackTapped)
        assertEquals(OnboardingFlow.Step.INTERESTS, store.state.step)
        assertEquals(listOf(ProductCategory.BAGS, ProductCategory.HOME), store.state.interests.selected)
        store.send(OnboardingFlow.Action.Interests(Interests.Action.ContinueTapped))
        for (action in listOf(
            AddressForm.Action.NameChanged(address.name),
            AddressForm.Action.StreetChanged(address.street),
            AddressForm.Action.CityChanged(address.city),
            AddressForm.Action.ZipChanged(address.zip),
            AddressForm.Action.ContinueTapped,
        )) {
            store.send(OnboardingFlow.Action.Address(action))
        }
        assertEquals(OnboardingFlow.Step.NOTIFICATIONS, store.state.step)
        store.send(OnboardingFlow.Action.Notifications(Notifications.Action.Chose(Notifications.Choice.OFF)))
        assertEquals(OnboardingAnswers(listOf(ProductCategory.BAGS, ProductCategory.HOME), address, notifications = false), answers)
        assertEquals(OnboardingFlow.Action.Delegate.Finished(nina), store.received.last())
    }

    @Test
    fun backGoesToThePreviousStepButNotWhileSaving() {
        val flow = OnboardingFlow.reducer(AccountClient())
        val saving = OnboardingFlow.State(nina, step = OnboardingFlow.Step.NOTIFICATIONS, notifications = Notifications.State(isLoading = true))
        assertEquals(saving, flow.reduce(saving, OnboardingFlow.Action.BackTapped).state)
        val welcome = OnboardingFlow.State(nina)
        assertEquals(welcome, flow.reduce(welcome, OnboardingFlow.Action.BackTapped).state)
    }

    @Test
    fun agentScreens() {
        val welcome = OnboardingFlowAgent.activeScreen(OnboardingFlow.State(nina))
        assertEquals("onboarding/welcome", welcome.path)
        assertEquals("Welcome", welcome.commands.first { it.name == "back" }.source)
        val interests = OnboardingFlowAgent.activeScreen(OnboardingFlow.State(nina, step = OnboardingFlow.Step.INTERESTS))
        assertEquals("OnboardingFlow", interests.command("back")?.source)
        assertEquals(
            listOf("onboarding/welcome", "onboarding/interests", "onboarding/address", "onboarding/notifications"),
            OnboardingFlowAgent.registry.map { it.path },
        )
        assertTrue(OnboardingFlowAgent.registry.drop(1).all { doc -> doc.commands.last().name == "back" })
    }
}
