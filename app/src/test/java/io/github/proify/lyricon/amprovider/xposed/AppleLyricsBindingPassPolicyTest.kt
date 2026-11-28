package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleLyricsBindingPassPolicyTest {
    @Test fun twoAndThreeArgumentNativeBindingsUpdateSameHolderOnce() {
        val holder = Any()
        assertFalse(AppleLyricsBindingPassPolicy.isNestedForSameHolder(
            parentHolder = null, parentAdapterIdentity = null,
            holder = holder, adapterIdentity = 7,
        ))
        assertTrue(AppleLyricsBindingPassPolicy.isNestedForSameHolder(
            parentHolder = holder, parentAdapterIdentity = 7,
            holder = holder, adapterIdentity = 7,
        ))
    }

    @Test fun anotherHolderOrAdapterMustStillBind() {
        val holder = Any()
        assertFalse(AppleLyricsBindingPassPolicy.isNestedForSameHolder(
            parentHolder = holder, parentAdapterIdentity = 7,
            holder = Any(), adapterIdentity = 7,
        ))
        assertFalse(AppleLyricsBindingPassPolicy.isNestedForSameHolder(
            parentHolder = holder, parentAdapterIdentity = 7,
            holder = holder, adapterIdentity = 8,
        ))
    }
}
