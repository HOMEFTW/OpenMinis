package com.openminis.app.ui.chat.voice

import android.content.Context
import com.openminis.app.R
import com.openminis.app.speech.correction.CorrectionFailure
import com.openminis.app.speech.correction.CorrectionFailureKind

/** Only typed, non-secret diagnostics reach the UI; never interpolate an exception message. */
internal fun correctionFailureMessage(context: Context, failures: List<CorrectionFailure>, rejection: String?): String {
    val supplemental = when (rejection) {
        "llm_timeout" -> CorrectionFailureKind.TIMEOUT
        "diff_too_large" -> CorrectionFailureKind.REWRITE
        "engine_unavailable" -> CorrectionFailureKind.UNAVAILABLE
        "llm_error:NoModelAvailable" -> CorrectionFailureKind.NO_MODEL
        "llm_error:EmptyResponse" -> CorrectionFailureKind.EMPTY
        else -> null
    }
    val details = failures.map { failure ->
        val label = context.getString(when (failure.kind) {
            CorrectionFailureKind.NO_MODEL -> R.string.b34_correction_no_model
            CorrectionFailureKind.UNAVAILABLE -> R.string.b34_correction_unavailable
            CorrectionFailureKind.AUTH -> R.string.b34_correction_auth
            CorrectionFailureKind.RATE_LIMIT -> R.string.b34_correction_rate_limit
            CorrectionFailureKind.HTTP -> R.string.b34_correction_http
            CorrectionFailureKind.TIMEOUT -> R.string.b34_correction_timeout
            CorrectionFailureKind.DNS -> R.string.b34_correction_dns
            CorrectionFailureKind.CERTIFICATE -> R.string.b34_correction_certificate
            CorrectionFailureKind.NETWORK -> R.string.b34_correction_network
            CorrectionFailureKind.EMPTY -> R.string.b34_correction_empty
            CorrectionFailureKind.DECODING -> R.string.b34_correction_decoding
            CorrectionFailureKind.PROVIDER -> R.string.b34_correction_provider
            CorrectionFailureKind.REWRITE -> R.string.b34_correction_rewrite
            CorrectionFailureKind.UNKNOWN -> R.string.b34_correction_unknown
        }, failure.status ?: 0)
        if (failure.model.isBlank()) label else context.getString(R.string.b34_correction_model_failure, failure.model, label)
    }.toMutableList()
    supplemental?.let {
        val extra = when (it) {
            CorrectionFailureKind.TIMEOUT -> R.string.b34_correction_timeout
            CorrectionFailureKind.REWRITE -> R.string.b34_correction_rewrite
            CorrectionFailureKind.UNAVAILABLE -> R.string.b34_correction_unavailable
            CorrectionFailureKind.NO_MODEL -> R.string.b34_correction_no_model
            CorrectionFailureKind.EMPTY -> R.string.b34_correction_empty
            else -> R.string.b34_correction_unknown
        }
        details += context.getString(extra)
    }
    if (details.isEmpty()) details += context.getString(R.string.b34_correction_unknown)
    return context.getString(R.string.b34_correction_failed_message, details.joinToString("\n"))
}
