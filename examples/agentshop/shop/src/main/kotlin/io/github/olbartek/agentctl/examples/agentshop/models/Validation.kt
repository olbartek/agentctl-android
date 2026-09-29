package io.github.olbartek.agentctl.examples.agentshop.models

/**
 * A pragmatic `local@domain.tld` check: one `@`, no whitespace, a dotted domain with non-empty labels and a top-level
 * domain of at least two letters.
 */
fun isValidEmail(email: String): Boolean {
    val parts = email.split("@")
    if (parts.size != 2) return false
    val (local, domain) = parts
    if (local.isEmpty() || Graphemes.of(email).any { it.isWhitespaceCharacter() }) return false
    val labels = domain.split(".")
    if (labels.size < 2 || labels.any { it.isEmpty() }) return false
    val tld = Graphemes.of(labels.last())
    return tld.size >= 2 && tld.all { it.isLetterCharacter() }
}

/** Why a password is too weak. The code is what agents see in `issues=`. */
enum class PasswordIssue(override val code: String) : Coded {
    TOO_SHORT("tooShort"),
    MISSING_LETTER("missingLetter"),
    MISSING_DIGIT("missingDigit"),
}

/** Password rules: at least 8 characters, with at least one letter and one digit. */
fun passwordIssues(password: String): List<PasswordIssue> {
    val characters = Graphemes.of(password)
    val issues = mutableListOf<PasswordIssue>()
    if (characters.size < 8) issues.add(PasswordIssue.TOO_SHORT)
    if (characters.none { it.isLetterCharacter() }) issues.add(PasswordIssue.MISSING_LETTER)
    if (characters.none { it.isNumberCharacter() }) issues.add(PasswordIssue.MISSING_DIGIT)
    return issues
}

fun isStrongPassword(password: String): Boolean = passwordIssues(password).isEmpty()

/** A form validation problem. The code is what agents see in `issues=`. */
enum class ValidationIssue(override val code: String) : Coded {
    EMAIL("email"),
    PASSWORD_TOO_SHORT("passwordTooShort"),
    PASSWORD_MISSING_LETTER("passwordMissingLetter"),
    PASSWORD_MISSING_DIGIT("passwordMissingDigit"),
    CONFIRM_MISMATCH("confirmMismatch"),
    ;

    companion object {
        fun of(issue: PasswordIssue): ValidationIssue = when (issue) {
            PasswordIssue.TOO_SHORT -> PASSWORD_TOO_SHORT
            PasswordIssue.MISSING_LETTER -> PASSWORD_MISSING_LETTER
            PasswordIssue.MISSING_DIGIT -> PASSWORD_MISSING_DIGIT
        }

        /** `none`, or the codes joined by commas: `email,passwordTooShort`. */
        fun summary(issues: List<ValidationIssue>): String =
            if (issues.isEmpty()) "none" else issues.joinToString(",") { it.code }
    }
}

/** A US-style zip code: exactly five digits. */
fun isValidZip(zip: String): Boolean {
    val characters = Graphemes.of(zip)
    return characters.size == 5 && characters.all { it.isAsciiCharacter() && it.isNumberCharacter() }
}

/** A payment card number the mock accepts: 16 digits, spaces allowed. */
fun isValidCardNumber(number: String): Boolean {
    val digits = Graphemes.of(number).filter { it != " " }
    return digits.size == 16 && digits.all { it.isAsciiCharacter() && it.isNumberCharacter() }
}
