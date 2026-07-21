package com.example.ai

import com.squareup.moshi.Json
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

// NOTE: no @JsonClass codegen — Moshi reflection (KotlinJsonAdapterFactory in
// AppModule) powers these DTOs. This removes the entire moshi-codegen KSP step
// (fewer generated files, faster builds) and these JSON annotations are still
// honoured at runtime by the reflective adapter.
// ---------------------------------------------------------------------------
// Gemini REST API (generativelanguage.googleapis.com)
// Uses the user's own API key, so no google-services.json / Firebase is needed.
// ---------------------------------------------------------------------------

data class GeminiPart(
    @Json(name = "text") val text: String? = null
)

data class GeminiContent(
    @Json(name = "parts") val parts: List<GeminiPart>,
    @Json(name = "role") val role: String? = null
)

data class GeminiRequest(
    @Json(name = "contents") val contents: List<GeminiContent>,
    @Json(name = "systemInstruction") val systemInstruction: GeminiContent? = null
)

data class GeminiCandidate(
    @Json(name = "content") val content: GeminiContent? = null
)

data class GeminiResponse(
    @Json(name = "candidates") val candidates: List<GeminiCandidate>? = null
)

interface GeminiApi {

    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Header("x-goog-api-key") apiKey: String,
        @Body request: GeminiRequest
    ): GeminiResponse
}
