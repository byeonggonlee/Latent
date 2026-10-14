package com.byeonggon.cameramanager

import androidx.lifecycle.ViewModelProvider

/**
 * AndroidX creates view models with the first Application it ever saw. In a test run that is the
 * Application of an earlier test, along with that test's database and files, so screens would show
 * and change another test's library. Call this before the activity starts to let them use their own.
 */
internal fun forgetTheApplicationOfEarlierTests() {
    ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance").apply {
        isAccessible = true
        set(null, null)
    }
}
