package dev.kaff.app.domain.transfer

/** Result of feeding one event to a reducer: the next model plus side effects to run, in order. */
data class Transition<out M, out F>(val model: M, val effects: List<F> = emptyList())

/** Haptic/sound cue requested by a session. */
enum class FeedbackKind {
    /** Fist confirmed, file lifted. */
    Grab,

    /** Palm confirmed, receive accepted. */
    Accept,

    /** Someone wants to connect or offered a file. */
    Attention,
    Success,
    Error,
}

/** Session timeouts, shared by both roles. */
data class SessionTimeouts(
    /**
     * Sender keeps an armed offer open this long. 7 s, not the 5 s of the first design: on device,
     * walking the hand to the other phone plus the palm hold did not fit comfortably in 5 s.
     */
    val armedWindowMs: Long = 7_000,
    /** Receiver watches for the palm this long after an offer arrives. */
    val watchMs: Long = 10_000,
    /** Sender waits this long for the receiver's DONE receipt after the bytes are out. */
    val receiptMs: Long = 60_000,
    /** A paired connection with no transfer is dropped after this long. */
    val idleMs: Long = 90_000,
    /** Receiver keeps the link this long after DONE so the receipt is delivered, then disconnects. */
    val lingerMs: Long = 1_500,
)
