package com.gemmory.modelinstall

/**
 * Everything that identifies and validates a distributable model artefact.
 *
 * All model constants live here (and in [ModelCatalog]) rather than being
 * scattered through the code base.
 */
data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val downloadUrl: String,
    val licenseName: String,
    val licenseUrl: String,
    val sourceUrl: String,
) {
    init {
        require(sha256.length == 64) { "sha256 must be a 64 character hex digest" }
        require(sizeBytes > 0) { "sizeBytes must be positive" }
    }

    /**
     * Free space required to install: the file itself plus the temporary copy
     * that exists until the atomic move, plus a small safety margin.
     */
    val requiredFreeSpaceBytes: Long
        get() = sizeBytes + SAFETY_MARGIN_BYTES

    private companion object {
        const val SAFETY_MARGIN_BYTES = 256L * 1024 * 1024
    }
}

/**
 * The models this build knows how to install.
 *
 * Size and digest were taken from the Hugging Face repository metadata for
 * `litert-community/gemma-4-E2B-it-litert-lm`. They are verified on device
 * before the file is accepted, so a stale value fails loudly rather than
 * silently loading a wrong artefact.
 */
object ModelCatalog {

    const val GEMMA_4_E2B_ID = "gemma-4-e2b-it"

    val gemma4E2bIt = ModelDescriptor(
        id = GEMMA_4_E2B_ID,
        displayName = "Gemma 4 E2B (instruction tuned)",
        fileName = "gemma-4-E2B-it.litertlm",
        sizeBytes = 2_588_147_712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/" +
            "resolve/main/gemma-4-E2B-it.litertlm",
        licenseName = "Gemma Terms of Use",
        licenseUrl = "https://ai.google.dev/gemma/terms",
        sourceUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
    )

    val default: ModelDescriptor = gemma4E2bIt

    /**
     * Returns [default] with a user supplied mirror URL, so the download source
     * stays configurable without duplicating the integrity metadata.
     */
    fun withDownloadUrl(url: String): ModelDescriptor =
        if (url.isBlank()) default else default.copy(downloadUrl = url.trim())
}
