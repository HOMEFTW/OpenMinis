package com.openminis.app.ui.chat

/** UI contract for a request_user_input / ask_user tool waiting for an answer. */
data class PendingUserQuestion(
    val id: String,
    val questions: List<UserQuestionPrompt>,
)

data class UserQuestionPrompt(
    val id: String,
    val question: String,
    val header: String = "",
    val options: List<UserQuestionOption> = emptyList(),
    val allowOther: Boolean = true,
)

data class UserQuestionOption(val label: String, val description: String = "")

/** Null until every question has an answer; -1 selects the free-text field. */
internal fun userQuestionAnswers(
    request: PendingUserQuestion,
    selected: Map<String, Int>,
    freeText: Map<String, String>,
): Map<String, String>? {
    if (request.questions.isEmpty()) return null
    val answers = linkedMapOf<String, String>()
    for (question in request.questions) {
        val index = selected[question.id]
        val answer = when {
            question.options.isEmpty() -> freeText[question.id]?.trim()
            index == -1 && question.allowOther -> freeText[question.id]?.trim()
            index != null && index in question.options.indices -> question.options[index].label
            else -> null
        }
        if (answer.isNullOrBlank()) return null
        answers[question.id] = answer
    }
    return answers
}
