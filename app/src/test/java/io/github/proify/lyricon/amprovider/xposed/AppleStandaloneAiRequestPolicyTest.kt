package io.github.proify.lyricon.amprovider.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleStandaloneAiRequestPolicyTest {
    @Test fun synchronousIpcFailureReleasesBoundConnectionBeforeRetry() {
        // Keeping binding=true with binder=null strands a queued request;
        // releasing the dead ServiceConnection must precede a new bind.
        val recovery = AppleStandaloneAiRequestPolicy.connectionRecoveryAfterIpcFailure(
            binding = true, pendingRetry = true, bridgeActive = false,
            consecutiveFailures = 1,
        )
        assertTrue(recovery.releasePreviousBinding)
        assertTrue(recovery.requestNewBinding)

        val disconnected = AppleStandaloneAiRequestPolicy.connectionRecoveryAfterIpcFailure(
            binding = false, pendingRetry = true, bridgeActive = false,
            consecutiveFailures = 1,
        )
        assertFalse(disconnected.releasePreviousBinding)
        assertTrue(disconnected.requestNewBinding)

        val bridged = AppleStandaloneAiRequestPolicy.connectionRecoveryAfterIpcFailure(
            binding = true, pendingRetry = true, bridgeActive = true,
            consecutiveFailures = 1,
        )
        assertTrue(bridged.releasePreviousBinding)
        assertFalse(bridged.requestNewBinding)

        val empty = AppleStandaloneAiRequestPolicy.connectionRecoveryAfterIpcFailure(
            binding = true, pendingRetry = false, bridgeActive = false,
            consecutiveFailures = 1,
        )
        assertTrue(empty.releasePreviousBinding)
        assertFalse(empty.requestNewBinding)

        // A service that accepts bind but throws on every one-way translate
        // must not create an unbounded onConnected→throw→rebind loop.
        val exhausted = AppleStandaloneAiRequestPolicy.connectionRecoveryAfterIpcFailure(
            binding = true, pendingRetry = true, bridgeActive = false,
            consecutiveFailures = 2,
        )
        assertTrue(exhausted.releasePreviousBinding)
        assertFalse(exhausted.requestNewBinding)
    }

    @Test fun disconnectedInflightSongReplaysOnlyWhileItsGenerationIsUnanswered() {
        assertTrue(AppleStandaloneAiRequestPolicy.shouldReplay(
            requestGeneration = 7L, currentGeneration = 7L,
            requestAttempt = 3L, deliveredAttempt = -1L,
            bridgeActive = false,
        ))
        assertFalse(AppleStandaloneAiRequestPolicy.shouldReplay(
            requestGeneration = 7L, currentGeneration = 7L,
            requestAttempt = 3L, deliveredAttempt = 3L,
            bridgeActive = false,
        ))
        assertFalse(AppleStandaloneAiRequestPolicy.shouldReplay(
            requestGeneration = 7L, currentGeneration = 8L,
            requestAttempt = 3L, deliveredAttempt = -1L,
            bridgeActive = false,
        ))
        assertFalse(AppleStandaloneAiRequestPolicy.shouldReplay(
            requestGeneration = 7L, currentGeneration = 7L,
            requestAttempt = 3L, deliveredAttempt = -1L,
            bridgeActive = true,
        ))
    }

    @Test fun replayAttemptSupersedesLateOldServiceResponseAndClearRevokesBoth() {
        assertTrue(AppleStandaloneAiRequestPolicy.mayAcceptResponse(
            requestGeneration = 7L, currentGeneration = 7L,
            requestAttempt = 4L, activeAttempt = 4L,
            bridgeActive = false,
        ))
        assertFalse(AppleStandaloneAiRequestPolicy.mayAcceptResponse(
            requestGeneration = 7L, currentGeneration = 7L,
            requestAttempt = 3L, activeAttempt = 4L,
            bridgeActive = false,
        ))
        assertFalse(AppleStandaloneAiRequestPolicy.mayAcceptResponse(
            requestGeneration = 7L, currentGeneration = 8L,
            requestAttempt = 4L, activeAttempt = 4L,
            bridgeActive = false,
        ))
        assertFalse(AppleStandaloneAiRequestPolicy.mayAcceptResponse(
            requestGeneration = 7L, currentGeneration = 7L,
            requestAttempt = 4L, activeAttempt = 4L,
            bridgeActive = true,
        ))
    }
}
