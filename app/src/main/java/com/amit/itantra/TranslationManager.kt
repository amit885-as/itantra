package com.amit.itantra

import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

class TranslationManager {

    suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        if (sourceLang == targetLang) {
            return text // No translation needed
        }
        
        return try {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(targetLang)
                .build()

            val translator = Translation.getClient(options)
            
            // Ensure model is downloaded
            translator.downloadModelIfNeeded().await()
            val result = translator.translate(text).await()
            
            // Close to free resources since we create it dynamically based on selected pair
            translator.close() 
            
            result
        } catch (e: Exception) {
            e.printStackTrace()
            "Translation Error: ${e.localizedMessage}"
        }
    }
}