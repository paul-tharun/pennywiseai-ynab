package com.pennywiseai.ynab.ui.nav

/**
 * Hand-rolled navigation destinations. A tiny back stack in PennyWiseApp beats pulling in
 * androidx.navigation (a version this catalog doesn't pin) for four screens. The three
 * Tab destinations are the bottom-bar roots; RuleEditor is pushed on top.
 */
sealed interface Screen {
    sealed interface Tab : Screen
    data object Home : Tab
    data object Backfill : Tab
    data object Settings : Tab

    /**
     * The add/edit-rule editor. When [prefillBank] is set it was opened from an unrouted
     * suggestion / Home "map this route" action — the editor pre-fills bank+last4 and,
     * after saving, offers a retroactive import for that route.
     */
    data class RuleEditor(
        val prefillBank: String? = null,
        val prefillLast4: String? = null,
        val editRuleId: Long? = null,
    ) : Screen
}
