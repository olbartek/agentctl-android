package io.github.olbartek.agentctl.examples.tinyapp.android

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.olbartek.agentctl.AgentStore
import io.github.olbartek.agentctl.examples.tinyapp.ItemDetail
import io.github.olbartek.agentctl.examples.tinyapp.Items
import io.github.olbartek.agentctl.examples.tinyapp.TinyRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * TinyApp's one activity: the list, or the detail screen on top of it, rendered from the store's state with plain
 * views. The views send the store the same actions the agent commands do — and, like SwiftUI's `onAppear`, the
 * list sends its appearance each time it comes on screen, which is what the headless runner stands in for.
 */
class MainActivity : Activity() {
    private lateinit var app: AppStore
    private lateinit var content: LinearLayout
    private val scope: CoroutineScope = MainScope()
    private var listVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = AppStore.get(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        setContentView(ScrollView(this).apply { addView(content) })
        scope.launch {
            app.isReady.combine(app.store.states) { ready, state -> ready to state }.collect { (ready, state) ->
                if (ready) render(app.store, state) else splash()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun splash() {
        content.removeAllViews()
        content.addView(text("Starting…"))
    }

    private fun render(store: AgentStore<TinyRoot.State, TinyRoot.Action>, state: TinyRoot.State) {
        content.removeAllViews()
        val top = state.path.lastOrNull()
        if (top == null) {
            val appeared = !listVisible
            listVisible = true
            renderList(store, state.items)
            if (appeared) store.send(TinyRoot.Action.Items(Items.Action.OnAppear))
            return
        }
        listVisible = false
        val detail = (top.screen as TinyRoot.Path.Detail).state
        content.addView(text(detail.item.title, size = 24f))
        content.addView(text("Saved: ${detail.saved}"))
        if (detail.cooldown > 0) content.addView(text("Cooldown: ${detail.cooldown}s"))
        detail.error?.let { content.addView(text("Error: ${it.code}")) }
        content.addView(button("Save") { store.send(TinyRoot.Action.Detail(top.id, ItemDetail.Action.SaveTapped)) })
        content.addView(button("Back") { store.send(TinyRoot.Action.PopFrom(top.id)) })
    }

    private fun renderList(store: AgentStore<TinyRoot.State, TinyRoot.Action>, items: Items.State) {
        content.addView(text("Items", size = 24f))
        if (items.isLoading) content.addView(text("Loading…"))
        items.error?.let { content.addView(text("Error: ${it.code}")) }
        for (item in items.items) {
            content.addView(button(item.title) { store.send(TinyRoot.Action.Items(Items.Action.OpenTapped(item.id))) })
        }
        content.addView(button("Refresh") { store.send(TinyRoot.Action.Items(Items.Action.Refresh)) })
        content.addView(button("Try again", enabled = items.error != null) { store.send(TinyRoot.Action.Items(Items.Action.Retry)) })
    }

    private fun text(value: String, size: Float = 18f) = TextView(this).apply {
        text = value
        textSize = size
    }

    private fun button(title: String, enabled: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        text = title
        isEnabled = enabled
        setOnClickListener(View.OnClickListener { onClick() })
    }
}
