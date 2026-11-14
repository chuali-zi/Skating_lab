package com.skatingcoach.android

import com.skatingcoach.android.data.JumpOption
import com.skatingcoach.android.data.LocalDraft
import com.skatingcoach.android.ui.SelectionEditor
import org.junit.Assert.*
import org.junit.Test

class SelectionEditorTest {
    private val catalog = listOf(JumpOption("1S", "后内结环", 1), JumpOption("2A", "阿克塞尔", 2), JumpOption("2T", "后外点冰", 2), JumpOption("3S", "后内结环", 3), JumpOption("3T", "后外点冰", 3))

    @Test fun lowerDefaultsRespectExplicitCancellation() {
        var draft = SelectionEditor.toggleSingle(LocalDraft(), "3S", catalog)
        assertTrue("2A" in draft.selectedSingles)
        draft = SelectionEditor.toggleSingle(draft, "2A", catalog)
        draft = SelectionEditor.toggleSingle(draft, "3T", catalog)
        assertFalse("2A" in draft.selectedSingles)
        assertTrue("2T" in draft.selectedSingles)
    }

    @Test fun singleAndCombinationSelectionsStayIndependent() {
        var draft = SelectionEditor.toggleCombination(LocalDraft(), "3S+3T")
        draft = SelectionEditor.toggleSingle(draft, "3T", catalog)
        draft = SelectionEditor.toggleSingle(draft, "3T", catalog)
        assertFalse("3T" in draft.selectedSingles)
        assertTrue("3S+3T" in draft.selectedCombinations)
    }

    @Test fun cancelledCombinationDefaultDoesNotReappear() {
        var draft = SelectionEditor.toggleCombination(LocalDraft(), "3S+3T")
        draft = SelectionEditor.toggleCombination(draft, "3S+2T")
        draft = SelectionEditor.toggleCombination(draft, "3S+3T")
        draft = SelectionEditor.toggleCombination(draft, "3S+3T")
        assertFalse("3S+2T" in draft.selectedCombinations)
    }
}
