package com.example.ui

import android.app.Application
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ChatMessageEntity
import com.example.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

class ChatViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    private val repository: ChatRepository
    val currentSessionId = MutableStateFlow("default")

    val messages: StateFlow<List<ChatMessageEntity>>

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isTtsSpeaking = MutableStateFlow(false)
    val isTtsSpeaking: StateFlow<Boolean> = _isTtsSpeaking.asStateFlow()

    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false

    init {
        val database = AppDatabase.getDatabase(application)
        repository = ChatRepository(database.chatMessageDao())

        messages = repository.getMessagesForSession("default")
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

        tts = TextToSpeech(application, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("id", "ID"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.US)
            }
            isTtsInitialized = true
        }
    }

    fun onInputTextChanged(newText: String) {
        _inputText.value = newText
    }

    fun sendMessage(prompt: String = _inputText.value) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty() || _isLoading.value) return

        _inputText.value = ""
        _isLoading.value = true

        viewModelScope.launch {
            try {
                repository.sendMessage(
                    userPrompt = trimmed,
                    sessionId = currentSessionId.value,
                    recentMessagesHistory = messages.value
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearChat() {
        viewModelScope.launch {
            stopSpeech()
            repository.clearSession(currentSessionId.value)
        }
    }

    fun speakText(text: String) {
        if (!isTtsInitialized) return

        if (_isTtsSpeaking.value) {
            stopSpeech()
        } else {
            // Clean markdown syntax for speech
            val cleanText = text
                .replace(Regex("\\*\\*|\\*|`|#"), "")
                .trim()
            tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "XAI_TTS")
            _isTtsSpeaking.value = true
        }
    }

    fun stopSpeech() {
        tts?.stop()
        _isTtsSpeaking.value = false
    }

    override fun onCleared() {
        super.onCleared()
        tts?.stop()
        tts?.shutdown()
    }
}
