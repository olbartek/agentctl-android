package io.github.olbartek.agentctl.examples.agentshop.app.design

import io.github.olbartek.agentctl.examples.agentshop.models.AccountError
import io.github.olbartek.agentctl.examples.agentshop.models.AuthError
import io.github.olbartek.agentctl.examples.agentshop.models.CatalogError
import io.github.olbartek.agentctl.examples.agentshop.models.OrdersError
import io.github.olbartek.agentctl.examples.agentshop.models.ValidationIssue

// Human-readable messages for backend errors, worded as the reference's `DesignSystem/ErrorMessages.swift` words
// them. Kept here so every screen words errors the same way.

val AuthError.message: String
    get() = when (this) {
        AuthError.INVALID_CREDENTIALS -> "Please enter correct password"
        AuthError.ACCOUNT_LOCKED -> "This account is locked. Reset your password to unlock it."
        AuthError.UNKNOWN_EMAIL -> "No account uses this email."
        AuthError.INVALID_CODE -> "That code is not valid."
        AuthError.CODE_EXPIRED -> "That code has expired. Request a new one."
        AuthError.RESEND_NOT_AVAILABLE -> "Wait before requesting another code."
        AuthError.EMAIL_TAKEN -> "An account with this email already exists."
        AuthError.WEAK_PASSWORD -> "Choose a stronger password."
        AuthError.EMAIL_NOT_VERIFIED -> "Verify your email address first. Check your inbox for the code."
        AuthError.NETWORK -> "The network is unreachable. Try again."
    }

val OrdersError.message: String
    get() = when (this) {
        OrdersError.NOT_FOUND -> "This order does not exist."
        OrdersError.NOT_CANCELLABLE -> "This order can no longer be cancelled."
        OrdersError.PAYMENT_DECLINED -> "Your card was declined. Try another card or Apple Pay."
        OrdersError.NETWORK -> "The network is unreachable. Try again."
        OrdersError.UNAUTHORIZED -> "You are signed out."
    }

val AccountError.message: String
    get() = when (this) {
        AccountError.NETWORK -> "The network is unreachable. Try again."
        AccountError.UNAUTHORIZED -> "You are signed out."
    }

val CatalogError.message: String
    get() = when (this) {
        CatalogError.NETWORK -> "The network is unreachable. Try again."
        CatalogError.TIMEOUT -> "The shop took too long to answer. Try again."
    }

val ValidationIssue.message: String
    get() = when (this) {
        ValidationIssue.EMAIL -> "Enter a valid email address."
        ValidationIssue.PASSWORD_TOO_SHORT -> "Use at least 8 characters."
        ValidationIssue.PASSWORD_MISSING_LETTER -> "Include at least one letter."
        ValidationIssue.PASSWORD_MISSING_DIGIT -> "Include at least one digit."
        ValidationIssue.CONFIRM_MISMATCH -> "The passwords don't match."
    }

/** The issues' messages as one line, or `null` when there are none (a field's error line). */
val List<ValidationIssue>.message: String?
    get() = if (isEmpty()) null else joinToString(" ") { it.message }

/** A category as the reference shows it: `rawValue.capitalized`. */
fun capitalized(code: String): String = code.split(" ").joinToString(" ") { word ->
    word.lowercase().replaceFirstChar { it.uppercaseChar() }
}
