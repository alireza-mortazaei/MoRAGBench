package com.example.onnxtok

import android.content.Context
import com.ml.shubham0204.sentence_embeddings.HFTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

interface TokenizerSource {
    suspend fun readTokenizerBytes(): ByteArray
}

class AssetTokenizerSource(
    private val context: Context,
    private val assetName: String
) : TokenizerSource {

    override suspend fun readTokenizerBytes(): ByteArray =
        withContext(Dispatchers.IO) {
            context.applicationContext.assets
                .open(assetName)
                .use { it.readBytes() }
        }
}

class ExternalTokenizerSource(
    private val context: Context,
    private val relativePath: String
) : TokenizerSource {

    override suspend fun readTokenizerBytes(): ByteArray =
        withContext(Dispatchers.IO) {
            val file = context.getExternalFilesDir(null)
                ?.resolve(relativePath)
                ?: error("External storage not available")

            file.readBytes()
        }
}


/**
 * Splits text files into fixed-token-length chunks using HFTokenizer.
 *
 */
class TextChunker(
    private val tokenizerSource: TokenizerSource,
    private val chunkingMethod: String = "token", // Should be one of "token", "word", "sentence"
    private val chunkSize: Int = 256,
    private val overlap: Int = 50
) : AutoCloseable {

    private var tokenizer: HFTokenizer? = null

    /** Load tokenizer from assets.  Must be called before chunkFiles(). */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (tokenizer != null) return@withContext
        val tokenizerBytes = tokenizerSource.readTokenizerBytes()
        tokenizer = HFTokenizer(tokenizerBytes)
    }

    /** Release tokenizer resources. */
    override fun close() {
        tokenizer?.close()
        tokenizer = null
    }

    /** Chunk several text files and return filename → chunk list. */
    suspend fun chunkFiles(files: List<File>): Map<String, List<String>> = withContext(Dispatchers.IO) {
        if (tokenizer == null) throw IllegalStateException("Tokenizer not initialized. Call initialize() first.")
        val result = mutableMapOf<String, List<String>>()

        for (file in files) {
            val text = file.readText(Charsets.UTF_8)
            result[file.name] = chunkText(text)
        }
        result
    }

    /** Split one text string into fixed-length chunks. */
    fun chunkText(text: String): List<String> {
        return when (chunkingMethod.lowercase()) {
            "token" -> chunkByTokens(text)
            "word" -> chunkByWords(text)
            "character" -> chunkByCharacters(text)
            "sentence"  -> chunkBySentences(text)
            else -> throw IllegalArgumentException("Unknown chunking method: $chunkingMethod")
        }
    }

    private fun chunkBySentences(text: String): List<String> {
        val sentenceRegex = Regex("(?<=[.!?])\\s+")
        val sentences = text.split(sentenceRegex).filter { it.isNotBlank() }

        val chunks = mutableListOf<String>()
        var start = 0

        while (start < sentences.size) {
            val chunkSentences = mutableListOf<String>()
            var tokenCount = 0
            var end = start

            // Keep adding sentences until we hit chunkSize (in tokens)
            while (end < sentences.size) {
                val sentenceTokens = tokenizer!!.tokenize(sentences[end]).ids.size
                if (tokenCount + sentenceTokens > chunkSize && chunkSentences.isNotEmpty()) {
                    break
                }
                chunkSentences.add(sentences[end])
                tokenCount += sentenceTokens
                end++
            }

            // Join sentences back into chunk
            val chunkText = chunkSentences.joinToString(" ").trim()
            if (chunkText.isNotEmpty()) chunks.add(chunkText)

            // Handle overlap — go back N sentences
            start = if (overlap > 0) {
                maxOf(start + 1, end - overlap)
            } else {
                end
            }
        }
        return chunks

    }


    private fun chunkByTokensOld(text: String): List<String> {
        val tokenIds = tokenizer!!.tokenize(text).ids
        val chunks = mutableListOf<String>()
        var start = 0

        while (start < tokenIds.size) {
            val end = minOf(start + chunkSize, tokenIds.size)
            val chunkIds = tokenIds.sliceArray(start until end)
            val chunkText = tokenizer!!.decode(chunkIds, skipSpecialTokens = true).trim()
            if (chunkText.isNotEmpty()) chunks.add(chunkText)
            start += chunkSize - overlap
        }
        return chunks
    }

    private fun chunkByTokens(text: String): List<String> {
        val allTokenIds = tokenizer!!.tokenize(text).ids
        val chunks = mutableListOf<String>()
        var start = 0

        while (start < allTokenIds.size) {
            val end = minOf(start + chunkSize, allTokenIds.size)

            val chunkFraction = (end - start).toDouble() / allTokenIds.size.toDouble()

            val charStart = ((start.toDouble() / allTokenIds.size) * text.length).toInt()
            val charEnd = minOf((charStart + (chunkFraction * text.length).toInt()), text.length)

            val chunkText = text.substring(charStart, charEnd).trim()
            if (chunkText.isNotEmpty()) chunks.add(chunkText)

            start += chunkSize - overlap
        }

        return chunks
    }

    private fun chunkByWords(text: String): List<String> {
        val words = text.split(Regex("\\s+"))
        val chunks = mutableListOf<String>()
        var start = 0

        while (start < words.size) {
            val end = minOf(start + chunkSize, words.size)
            val chunkWords = words.subList(start, end)
            val chunkText = chunkWords.joinToString(" ").trim()
            if (chunkText.isNotEmpty()) chunks.add(chunkText)
            start += chunkSize - overlap
        }
        return chunks
    }

    private fun chunkByCharacters(text: String): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0

        while (start < text.length) {
            val end = minOf(start + chunkSize, text.length)
            val chunk = text.substring(start, end).trim()

            if (chunk.isNotEmpty()) chunks.add(chunk)
            start += chunkSize - overlap
        }

        return chunks
    }
}
