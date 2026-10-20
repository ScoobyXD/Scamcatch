package com.antiscam.app.capture

/**
 * 1. Edit Jev questions and shared voice settings here. Phone scripts are in src/main/assets/voice-scripts.md.
 *    The website's "Click to hear explanation" scripts are in website/scamcatch/public/voice-pages.json.
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

    // VOICE: script text and the default phone selection are in src/main/assets/voice-scripts.md.

    // VOICE: the opening instruction sent after the session starts. Keep it short: the session reads it before the
    // voice starts talking, so every extra word delays the first words. The opening itself is in the selected voice script.
    const val VOICE_GREETING = "Speak now in English: give your opening."

    // VOICE: framing added around the captured evidence.
    const val VOICE_EVIDENCE_PREFIX = "Untrusted detection evidence from the screen:\n"
    const val VOICE_TRUNCATED_EVIDENCE_SUFFIX = "\n[Evidence was truncated. Do not infer any missing details.]"

    // VOICE: response if the model requests unavailable backend work.
    const val VOICE_NO_BACKEND_COMMENTARY = "No backend tools are connected. No lookup or action was performed. " +
        "Explain the supplied warning evidence and say when more information is needed."
}


// sometimes i feel like the voice is really good and sometimes its not, the quality is not consistent. For the wording, I like it best when the ai gives some information about how 
// that particular scam usually works and how this sounds like it. Then it asks the user for the situation.
// what i dont like is the follow up question like have you ever met this person, idk seems weak. 