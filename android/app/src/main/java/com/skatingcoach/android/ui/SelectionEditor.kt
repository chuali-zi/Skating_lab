package com.skatingcoach.android.ui

import com.skatingcoach.android.data.JumpOption
import com.skatingcoach.android.data.LocalDraft

/** Input convenience only. Selections do not claim verified mastery or force use. */
object SelectionEditor {
    fun toggleSingle(draft: LocalDraft, code: String, catalog: List<JumpOption>): LocalDraft {
        if (code in draft.selectedSingles) return draft.copy(
            selectedSingles = draft.selectedSingles - code,
            excludedDefaults = draft.excludedDefaults + code,
        )
        val revolutions = catalog.firstOrNull { it.code == code }?.revolutions ?: 0
        val lower = catalog.filter { it.revolutions < revolutions }.map { it.code }.toSet()
        return draft.copy(
            selectedSingles = draft.selectedSingles + code + (lower - draft.excludedDefaults),
            excludedDefaults = draft.excludedDefaults - code,
        )
    }

    fun toggleCombination(draft: LocalDraft, code: String): LocalDraft {
        if (code in draft.selectedCombinations) return draft.copy(
            selectedCombinations = draft.selectedCombinations - code,
            excludedCombinationDefaults = draft.excludedCombinationDefaults + code,
        )
        val defaults = if (code == "3S+3T") setOf("3S+2T") else emptySet()
        return draft.copy(
            selectedCombinations = draft.selectedCombinations + code + (defaults - draft.excludedCombinationDefaults),
            excludedCombinationDefaults = draft.excludedCombinationDefaults - code,
        )
    }
}
