package io.github.olbartek.agentctl.examples.agentshop.models

/**
 * A value agents see as text: an error reported as `error=<code>`, a status in a summary, a command's argument. The
 * code is the Swift reference's raw value, spelled the same, so both ports print the same bytes.
 */
interface Coded {
    val code: String
}

/** The entry of a [Coded] enum with this code, if there is one. */
inline fun <reified E> codeOf(code: String): E? where E : Enum<E>, E : Coded =
    enumValues<E>().firstOrNull { it.code == code }

/** Every code of a [Coded] enum, in declaration order, joined by `|`: how a command documents its argument. */
inline fun <reified E> codeChoices(): String where E : Enum<E>, E : Coded = enumValues<E>().joinToString("|") { it.code }

/** Errors from the auth backend. */
enum class AuthError(override val code: String) : Coded {
    INVALID_CREDENTIALS("invalidCredentials"),
    ACCOUNT_LOCKED("accountLocked"),
    UNKNOWN_EMAIL("unknownEmail"),
    INVALID_CODE("invalidCode"),
    CODE_EXPIRED("codeExpired"),
    RESEND_NOT_AVAILABLE("resendNotAvailable"),
    EMAIL_TAKEN("emailTaken"),
    WEAK_PASSWORD("weakPassword"),

    /** The account was registered but its email address hasn't been verified yet. */
    EMAIL_NOT_VERIFIED("emailNotVerified"),
    NETWORK("network"),
    ;

    companion object {
        /** Any error from the auth client as an [AuthError]; unexpected errors count as `network`. */
        fun of(error: Throwable): AuthError = (error as? AuthException)?.error ?: NETWORK
    }
}

class AuthException(val error: AuthError) : Exception(error.code)

/** Errors from the orders backend. */
enum class OrdersError(override val code: String) : Coded {
    NOT_FOUND("notFound"),
    NOT_CANCELLABLE("notCancellable"),

    /** The card was declined when placing an order (the mock declines 4000 0000 0000 0002). */
    PAYMENT_DECLINED("paymentDeclined"),
    NETWORK("network"),

    /** No session. Normal flows never hit this; it guards against calling orders while logged out. */
    UNAUTHORIZED("unauthorized"),
    ;

    companion object {
        /** Any error from the orders client as an [OrdersError]; unexpected errors count as `network`. */
        fun of(error: Throwable): OrdersError = (error as? OrdersException)?.error ?: NETWORK
    }
}

class OrdersException(val error: OrdersError) : Exception(error.code)

/** Errors from the account server. */
enum class AccountError(override val code: String) : Coded {
    NETWORK("network"),
    UNAUTHORIZED("unauthorized"),
    ;

    companion object {
        fun of(error: Throwable): AccountError = (error as? AccountException)?.error ?: NETWORK
    }
}

class AccountException(val error: AccountError) : Exception(error.code)

/** Errors from the catalog server. */
enum class CatalogError(override val code: String) : Coded {
    NETWORK("network"),
    TIMEOUT("timeout"),
    ;

    companion object {
        fun of(error: Throwable): CatalogError = (error as? CatalogException)?.error ?: NETWORK
    }
}

class CatalogException(val error: CatalogError) : Exception(error.code)

/** Errors from the cart server. */
enum class CartError(override val code: String) : Coded {
    INVALID_PROMO("invalidPromo"),
    NETWORK("network"),
    ;

    companion object {
        fun of(error: Throwable): CartError = (error as? CartException)?.error ?: NETWORK
    }
}

class CartException(val error: CartError) : Exception(error.code)

/** A client call's result, as a response action carries it: the value, or the typed error. */
sealed interface Outcome<out T, out E> {
    data class Success<T>(val value: T) : Outcome<T, Nothing>

    data class Failure<E>(val error: E) : Outcome<Nothing, E>
}
