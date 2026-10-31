package com.mvrk.vrka.update.security

class ManifestValidationException(
    message: String,
    cause: Throwable? = null,
) : SecurityException(message, cause)
