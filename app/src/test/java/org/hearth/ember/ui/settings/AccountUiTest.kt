package org.hearth.ember.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AccountUiTest {
    @Test fun usernamesNeedThreeToTwentyFourSafeCharacters() {
        assertNull(usernameProblem("ann.lee_9", "ann"))
        assertNotNull(usernameProblem("ab", "ann"))
        assertNotNull(usernameProblem("has space", "ann"))
        assertNotNull(usernameProblem("x".repeat(25), "ann"))
    }

    @Test fun keepingTheSameUsernameIsNotAChange() {
        assertEquals("That is already your username.", usernameProblem("ann", "ann"))
    }

    @Test fun newPasswordsNeedEightCharactersAndToMatch() {
        assertNull(passwordProblem("long-enough", "long-enough"))
        assertNotNull(passwordProblem("short", "short"))
        assertEquals("The two new passwords are not the same.", passwordProblem("long-enough", "long-enougH"))
    }
}
