package com.example.comicdav.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.comicdav.video.MediaKind
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MuBoxDenseMediaRowSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun selectedRowExposesClickAndLongClickSemantics() {
        var longClicked = false
        compose.setContent {
            ComicDavTheme {
                MuBoxDenseMediaRow(
                    title = "示例漫画",
                    mediaKind = MediaKind.Comic,
                    onClick = {},
                    selected = true,
                    onLongClick = { longClicked = true },
                    onLongClickLabel = "选择示例漫画",
                )
            }
        }

        val row = compose.onNodeWithText("示例漫画")
        row.assertIsSelected()
            .assertHasClickAction()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .performTouchInput { longClick() }

        compose.runOnIdle { assertTrue(longClicked) }
    }
}
