package com.lukesteuber.signalstation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The license wording shown in About must be the repository's own, not a paraphrase. */
class SignalLicenseTextTest {
    private val assets = File("src/main/assets/signal-station")
    private fun flat(text: String) = text.replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1").replace("`", "").replace(Regex("\\s+"), " ").trim()

    @Test fun aboutStatementIsTakenFromLicensingMd() {
        val licensing = flat(File("../LICENSING.md").readText())
        val sentences = flat(File(assets, "ABOUT_LICENSE.txt").readText()).split(Regex("(?<=\\.) ")).filter { it.isNotBlank() }
        assertTrue(sentences.size >= 5)
        sentences.forEach { assertTrue(licensing.contains(it), "Not in LICENSING.md: $it") }
    }

    @Test fun packagedMitTextMatchesTheRepositoryLicense() {
        assertEquals(File("../LICENSE-MIT").readText(), File(assets, "LICENSE-MIT.txt").readText())
    }

    @Test fun noticesNameEveryBundledNonMavenItem() {
        val notices = File(assets, "THIRD_PARTY_NOTICES.txt").readText()
        listOf("vosk-model-small-en-us-0.15", "com.alphacephei:vosk-android", "io.rebble.pebblekit2:client", "com.google.mlkit:genai-prompt",
            "com.google.ai.edge.litertlm:litertlm-android", "com.garmin.connectiq:ciq-companion-app-sdk", "Public Suffix List",
            "Apache License", "Mozilla Public License Version 2.0", "CC0 1.0 Universal", "Redistribution and use in source and binary forms").forEach {
            assertTrue(notices.contains(it), "Missing from notices: $it")
        }
    }
}
