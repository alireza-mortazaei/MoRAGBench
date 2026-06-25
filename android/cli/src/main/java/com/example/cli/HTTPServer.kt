package com.example.cli

import android.content.Context
import com.example.cli.Progress.TaskProgress
import com.example.cli.Progress.ANNProgress
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

class HttpServer(private val context: Context, port: Int = Constants.PORT): NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        try {
            when (session.uri) {
                "/status" -> {
                    val json = Json {
                        prettyPrint = true
                        encodeDefaults = true
                        explicitNulls = true
                    }

                    val taskSnapshot = BenchmarkManager.taskBenchmark?.progress?.getSnapshot()
                    val annSnapshot = BenchmarkManager.annBenchmark?.progress?.getSnapshot()

                    if (taskSnapshot == null && annSnapshot == null) {
                        return newFixedLengthResponse(
                            Response.Status.NO_CONTENT,
                            "application/json",
                            ""
                        )
                    } else {
                        val currentTestType = BenchmarkManager.testType
                        val jsonString = if (currentTestType == "task") {
                            json.encodeToString(
                                TaskProgress.ProgressSnapshot.serializer(),
                                taskSnapshot!!
                            )
                        } else {
                            json.encodeToString(
                                ANNProgress.ProgressSnapshot.serializer(),
                                annSnapshot!!
                            )
                        }

                        return newFixedLengthResponse(
                            Response.Status.OK,
                            "application/json",
                            jsonString
                        )
                    }
                }
                "/start_benchmark" -> {
                    if (session.method != Method.POST) {
                        return newFixedLengthResponse(
                            Response.Status.METHOD_NOT_ALLOWED,
                            "text/plain",
                            "Use POST"
                        )
                    }

                    // Parse body into a map
                    val body = HashMap<String, String>()
                    session.parseBody(body)

                    // Raw body is stored under "postData"
                    val rawJson = body["postData"] ?: ""

                    // Parse JSON
                    val json = JSONObject(rawJson)
                    val testType = json.optString("test_type", "task")

                    val testStatus = BenchmarkManager.startBenchmarkAsync(testType)

                    return newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        """{"status":$testStatus,"test_type":"$testType"}"""
                    )
                }
                "/prepare_dirs" -> {
                    if (session.method != Method.POST) {
                        return newFixedLengthResponse(
                            Response.Status.METHOD_NOT_ALLOWED,
                            "text/plain",
                            "Use POST"
                        )
                    }

                    // Read POST body
                    val body = HashMap<String, String>()
                    session.parseBody(body)
                    val rawJson = body["postData"] ?: ""

                    val req = JSONObject(rawJson)
                    val dirsArray = req.optJSONArray("dirs") ?: JSONArray()

                    val base = context.getExternalFilesDir(null)
                        ?: throw IllegalStateException("External files dir unavailable")
                    val basePath = base.canonicalPath

                    val created = JSONArray()
                    for (i in 0 until dirsArray.length()) {
                        val relative = dirsArray.getString(i)
                        val target = base.resolve(relative)

                        // Guard against path traversal escaping the sandbox
                        if (!target.canonicalPath.startsWith(basePath)) {
                            throw SecurityException("Illegal path outside sandbox: $relative")
                        }

                        target.mkdirs()
                        created.put(target.absolutePath)
                    }

                    val resp = JSONObject()
                    resp.put("created", created)
                    resp.put("base", base.absolutePath)

                    return newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        resp.toString()
                    )
                }
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        } catch (e: Exception) {
            val j = JSONObject()
            j.put("error", e.message)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", j.toString())
        }
    }
}
