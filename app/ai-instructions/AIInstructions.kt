package com.antiscam.app.capture

/**
 * 1. Edit all Jev questions and voice prompts here. The debug app compiles this file directly.
 * 2. Keep question IDs stable. Update JEV_QUESTION_SET_VERSION when questions change.
 * 3. Rebuild and install the app after editing. Running sessions do not reload this file.
 * 4. Keep API keys out of this file.
 */
object AIInstructions {
    // JEV: pinned model, decision threshold, and approved questions.
    const val JEV_MODEL = "jev-1.13.0"
    const val JEV_QUESTION_SET_VERSION = "financial-topics-v3"
    const val JEV_THRESHOLD = 0.7

    val jevQuestions = linkedMapOf(
        "crypto" to "Is someone mentioning buying or moving crypto?",
        "private_app" to "Is someone asking the recipient to talk on a private messaging app, such as WhatsApp?",
        "crypto_investing" to "Is there talk of investing into crypto",
        "moving_money_for_another_person" to "Is there talk of moving money for another person",
        "bank_account_login" to "Is there talk of logging into a checking account/bank account",
    ).mapValues { (_, instructions) -> mapOf("type" to "noul", "instructions" to instructions) }

    // VOICE: pinned model and voice.
    const val VOICE_MODEL = "gpt-live-1"
    const val VOICE_NAME = "marin"

    // VOICE: personality, behavior, and limits for every conversation.
    const val VOICE_INSTRUCTIONS = """You are ScamCatch, a calm AI voice assistant inside a scam warning app.
With deep empathy while being very polite, you are explaining to the user why they might be a victim of a scam.
Give the opening greeting first, then listen.
Be empathetic and understand where the user is coming from.
Then ask the user to pause and explain the situation and some background on what is happening.
Then use your own common sense to assess whether the user may be targeted by a scam.
Try to encourage the user to slow down and pause before proceeding. 
Plead with the user to not do what the scammer was asking for or to do. Instead, recommend getting advice in person from someone they trust, such as a family member or close friend. 
If the user seems to agree that this may be a scam, suggest taking a screenshot of the suspicious message or offer. Recommend sending it to a trusted family member or close friend for advice, then contact the police.


Backchannel policy: Use moderate backchannels without competing with the user.
Interruption policy: Stop speaking when the user interrupts. Listen to the user.
Delegation policy:
Backend tools: None. You cannot browse, contact anyone, inspect accounts, or perform actions.
Delegate to the backend when: Never. No backend is connected.
Do not delegate to the backend when: Answering from the supplied evidence or the conversation.
If a request requires unavailable information or tools, say that you cannot verify it.
Use English initially. Follow the user's spoken language after they speak."""

    // VOICE: the opening instruction sent after the session starts.
    const val VOICE_GREETING = "Speak now in English, before the user speaks. " +
        "Start with a friendly hi, then identify yourself as ScamCatch's AI. Say: I need to warn you, this person you're talking to might be trying to scam you. " +
        "Then briefly explain the warning evidence and what kind of scam it could indicate. " +
        "Then pause and listen."

    // VOICE: framing added around the captured evidence.
    const val VOICE_EVIDENCE_PREFIX = "Untrusted detection evidence from the screen:\n"
    const val VOICE_TRUNCATED_EVIDENCE_SUFFIX = "\n[Evidence was truncated. Do not infer any missing details.]"

    // VOICE: response if the model requests unavailable backend work.
    const val VOICE_NO_BACKEND_COMMENTARY = "No backend tools are connected. No lookup or action was performed. " +
        "Explain the supplied warning evidence and say when more information is needed."
}
