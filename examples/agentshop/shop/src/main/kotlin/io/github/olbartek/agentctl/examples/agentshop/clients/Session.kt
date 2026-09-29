package io.github.olbartek.agentctl.examples.agentshop.clients

import io.github.olbartek.agentctl.examples.agentshop.models.Session
import io.github.olbartek.agentctl.examples.agentshop.models.ShopCalls
import io.github.olbartek.agentctl.examples.agentshop.models.User
import java.io.File
import java.io.IOException
import java.util.Properties

/** A saved session, and whether the user chose "Keep me signed in". */
data class StoredSession(
    val session: Session,
    /** When false, the session only lasts until the app is relaunched. */
    val remember: Boolean,
)

/**
 * Where [SessionClient] keeps the session. Other mocks read it directly (without logging a call) to know who is
 * signed in, the way a server reads a token.
 */
interface SessionStorage {
    fun load(): StoredSession?

    fun store(session: StoredSession?)

    /** The signed-in user's session, remembered or not. */
    val currentSession: Session? get() = load()?.session

    companion object {
        /** In memory: headless runs and tests. */
        fun inMemory(initial: StoredSession? = null): SessionStorage = InMemorySessionStorage(initial)

        /** A properties file: the app passes one in its `filesDir`, so the session survives a relaunch. */
        fun file(file: File): SessionStorage = FileSessionStorage(file)
    }
}

private class InMemorySessionStorage(initial: StoredSession?) : SessionStorage {
    @Volatile private var stored: StoredSession? = initial

    override fun load(): StoredSession? = stored

    override fun store(session: StoredSession?) {
        stored = session
    }
}

/** The session as a small properties file; no file is no session, and an unreadable one counts as none. */
private class FileSessionStorage(private val file: File) : SessionStorage {
    private val lock = Any()

    override fun load(): StoredSession? = synchronized(lock) {
        if (!file.isFile) return null
        val properties = Properties()
        try {
            file.inputStream().use { properties.load(it) }
        } catch (_: IOException) {
            return null
        }
        val user = User(
            id = properties.getProperty("user.id") ?: return null,
            name = properties.getProperty("user.name") ?: return null,
            email = properties.getProperty("user.email") ?: return null,
        )
        val token = properties.getProperty("token") ?: return null
        StoredSession(Session(user, token), remember = properties.getProperty("remember") == "true")
    }

    override fun store(session: StoredSession?): Unit = synchronized(lock) {
        if (session == null) {
            file.delete()
            return
        }
        val properties = Properties()
        properties.setProperty("user.id", session.session.user.id)
        properties.setProperty("user.name", session.session.user.name)
        properties.setProperty("user.email", session.session.user.email)
        properties.setProperty("token", session.session.token)
        properties.setProperty("remember", session.remember.toString())
        file.parentFile?.mkdirs()
        file.outputStream().use { properties.store(it, null) }
    }
}

/**
 * Keeps the signed-in session. In the app it survives relaunches ([SessionStorage.file]); headlessly and in tests it
 * lives in memory. Its calls are logged like every mock call (`calls=session.save`) but have no latency and cannot
 * fail.
 */
class SessionClient(
    /**
     * The session to restore at launch: only one saved with "Keep me signed in". A session that wasn't remembered
     * is cleared instead, as a relaunch would have lost it.
     */
    val current: suspend () -> Session? = { null },
    /** Saves the signed-in session. `remember = false` means it won't survive a relaunch. */
    val save: suspend (session: Session, remember: Boolean) -> Unit = { _, _ -> unimplemented("session.save") },
    val clear: suspend () -> Unit = { unimplemented("session.clear") },
) {
    companion object {
        fun live(calls: ShopCalls, storage: SessionStorage): SessionClient = SessionClient(
            current = {
                calls.logged("session.current") {
                    val stored = storage.load()
                    when {
                        stored == null -> null
                        !stored.remember -> {
                            storage.store(null)
                            null
                        }
                        else -> stored.session
                    }
                }
            },
            save = { session, remember -> calls.logged("session.save") { storage.store(StoredSession(session, remember)) } },
            clear = { calls.logged("session.clear") { storage.store(null) } },
        )
    }
}
