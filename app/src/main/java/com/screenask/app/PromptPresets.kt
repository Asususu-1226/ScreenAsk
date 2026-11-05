package com.screenask.app

data class PromptPreset(val title: String, val question: String)

object PromptPresets {
    val questions = listOf(
        PromptPreset("解答题目", "请识别屏幕中的题目，给出完整解题步骤和最终答案。"),
        PromptPreset("选择题速答", "请解答屏幕上的选择题，直接给出选项和简短理由。"),
        PromptPreset("翻译", "请翻译屏幕中的外文内容，并保留原文与译文对照。"),
        PromptPreset("总结要点", "请用中文总结屏幕内容的核心要点。"),
        PromptPreset("听力原文", "请根据屏幕与音频内容给出听力原文和中文翻译。"),
        PromptPreset("解释代码", "请解释屏幕中的代码，指出功能和潜在问题。")
    )

    val systemPrompts = listOf(
        PromptPreset("通用识屏", AppConfig.DEFAULT_PROMPT),
        PromptPreset("考试答题", "你是严谨的答题助手。识别题目后逐步推理，先给最终答案再给过程。看不清的内容必须说明，禁止编造。回答使用中文，尽量简洁。"),
        PromptPreset("只给答案", "只输出最终答案或结论，不要解释过程，尽量控制在两行以内。"),
        PromptPreset("翻译模式", "你是翻译助手。把屏幕中的外文完整翻译成中文，保留必要的原文引用，不要添加额外评论。")
    )
}
