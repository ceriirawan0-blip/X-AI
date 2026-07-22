package com.example.repository

import com.example.BuildConfig
import com.example.api.GeminiClient
import com.example.api.GeminiContent
import com.example.api.GeminiGenerationConfig
import com.example.api.GeminiPart
import com.example.api.GeminiRequest
import com.example.data.ChatMessageDao
import com.example.data.ChatMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ChatRepository(private val chatDao: ChatMessageDao) {

    fun getMessagesForSession(sessionId: String): Flow<List<ChatMessageEntity>> {
        return chatDao.getMessagesForSession(sessionId)
    }

    suspend fun clearSession(sessionId: String) {
        chatDao.clearSessionMessages(sessionId)
    }

    suspend fun deleteMessage(id: Long) {
        chatDao.deleteMessageById(id)
    }

    suspend fun sendMessage(
        userPrompt: String,
        sessionId: String = "default",
        recentMessagesHistory: List<ChatMessageEntity> = emptyList()
    ) = withContext(Dispatchers.IO) {
        // 1. Save User Message to Local DB
        val userEntity = ChatMessageEntity(
            sessionId = sessionId,
            sender = "user",
            text = userPrompt.trim(),
            timestamp = System.currentTimeMillis()
        )
        chatDao.insertMessage(userEntity)

        // 2. Check if prompt is asking about creator / pencipta
        val normalizedPrompt = userPrompt.trim().lowercase()
        val isCreatorQuestion = isAskedAboutCreator(normalizedPrompt)

        if (isCreatorQuestion) {
            val creatorAnswer = "Saya adalah **X AI**, kecerdasan buatan cerdas yang diciptakan oleh **X CERI**. Ada yang bisa saya bantu lagi hari ini?"
            val aiEntity = ChatMessageEntity(
                sessionId = sessionId,
                sender = "ai",
                text = creatorAnswer,
                timestamp = System.currentTimeMillis()
            )
            chatDao.insertMessage(aiEntity)
            return@withContext
        }

        // 3. Prepare Gemini API Request
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            // Placeholder / Missing key fallback answer
            val fallbackResponse = "Maaf, API Key Gemini belum dikonfigurasi di AI Studio Secrets. Namun saya adalah **X AI**, diciptakan oleh **X CERI**. Silakan atur GEMINI_API_KEY untuk respon AI penuh."
            val aiEntity = ChatMessageEntity(
                sessionId = sessionId,
                sender = "ai",
                text = fallbackResponse,
                timestamp = System.currentTimeMillis(),
                isError = true
            )
            chatDao.insertMessage(aiEntity)
            return@withContext
        }

        // System Instruction
        val systemInstruction = GeminiContent(
            parts = listOf(
                GeminiPart(
                    text = "You are X AI, an advanced AI chatbot assistant created by X CERI. " +
                            "If anyone asks who created you, who your developer is, or questions like 'siapa penciptamu', 'siapa pembuatmu', 'who created you', " +
                            "you MUST state clearly that you were created by X CERI. " +
                            "Always be helpful, polite, intelligent, and accurate."
                )
            )
        )

        // Build contents list from recent history + new prompt
        val contents = mutableListOf<GeminiContent>()
        recentMessagesHistory.takeLast(10).forEach { msg ->
            if (!msg.isError) {
                val role = if (msg.sender == "user") "user" else "model"
                contents.add(
                    GeminiContent(
                        role = role,
                        parts = listOf(GeminiPart(text = msg.text))
                    )
                )
            }
        }
        contents.add(
            GeminiContent(
                role = "user",
                parts = listOf(GeminiPart(text = userPrompt))
            )
        )

        val request = GeminiRequest(
            contents = contents,
            systemInstruction = systemInstruction,
            generationConfig = GeminiGenerationConfig(
                temperature = 0.7f
            )
        )

        try {
            val response = GeminiClient.apiService.generateContent(apiKey, request)
            var replyText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text

            if (replyText.isNullOrBlank()) {
                replyText = "Maaf, X AI tidak dapat memproses jawaban saat ini."
            }

            // Save AI response
            val aiEntity = ChatMessageEntity(
                sessionId = sessionId,
                sender = "ai",
                text = replyText,
                timestamp = System.currentTimeMillis()
            )
            chatDao.insertMessage(aiEntity)

        } catch (e: Exception) {
            val errorMessage = "Gagal terhubung ke server X AI: ${e.localizedMessage ?: "Terjadi kesalahan koneksi"}. Diciptakan oleh X CERI."
            val aiEntity = ChatMessageEntity(
                sessionId = sessionId,
                sender = "ai",
                text = errorMessage,
                timestamp = System.currentTimeMillis(),
                isError = true
            )
            chatDao.insertMessage(aiEntity)
        }
    }

    private fun isAskedAboutCreator(prompt: String): Boolean {
        val keywords = listOf(
            "pencipta", "pembuat", "ciptain", "menciptakan", "ciptakan",
            "dibuat oleh", "diciptakan oleh", "yang buat", "yang bikin",
            "who created", "who made", "who is your creator", "who developed", "developer",
            "siapa buat kamu", "siapa penciptamu", "siapa pembuatmu", "siapa pencipta kamu"
        )
        val isWhoQuestion = prompt.contains("siapa") || prompt.contains("who") || prompt.contains("siapakah")
        return keywords.any { prompt.contains(it) } && (isWhoQuestion || prompt.contains("penciptamu") || prompt.contains("pembuatmu"))
    }
}
