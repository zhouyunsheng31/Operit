package com.ai.assistance.operit.core.tools.javascript

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import dalvik.system.DexClassLoader
import java.io.File
import java.util.LinkedHashMap
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

internal class JsExternalJavaCodeLoader(private val context: Context) {
    private enum class SourceType(val wireName: String) {
        DEX("dex"),
        JAR("jar");
    }

    private data class LoadOptions(
        val nativeLibraryDir: String?
    )

    private data class LoadedArtifact(
        val sourceType: SourceType,
        val sourcePath: String,
        val nativeLibraryDir: String?,
        val classLoader: ClassLoader
    ) {
        fun toJson(index: Int, alreadyLoaded: Boolean): JSONObject {
            return JSONObject()
                .put("index", index)
                .put("type", sourceType.wireName)
                .put("path", sourcePath)
                .put("nativeLibraryDir", nativeLibraryDir)
                .put("alreadyLoaded", alreadyLoaded)
        }
    }

    companion object {
        private const val TAG = "JsExternalJavaCodeLoader"
    }

    private val loadedArtifacts = LinkedHashMap<String, LoadedArtifact>()

    @Synchronized
    fun getEffectiveClassLoader(baseClassLoader: ClassLoader): ClassLoader {
        return loadedArtifacts.values.lastOrNull()?.classLoader ?: baseClassLoader
    }

    @Synchronized
    fun loadDex(path: String, optionsJson: String, baseClassLoader: ClassLoader): String {
        return load(SourceType.DEX, path, optionsJson, baseClassLoader)
    }

    @Synchronized
    fun loadJar(path: String, optionsJson: String, baseClassLoader: ClassLoader): String {
        return load(SourceType.JAR, path, optionsJson, baseClassLoader)
    }

    @Synchronized
    fun listLoadedArtifacts(): String {
        val payload = JSONArray()
        loadedArtifacts.values.forEachIndexed { index, artifact ->
            payload.put(artifact.toJson(index = index, alreadyLoaded = true))
        }
        return success(payload)
    }

    private fun load(
        sourceType: SourceType,
        path: String,
        optionsJson: String,
        baseClassLoader: ClassLoader
    ): String {
        return try {
            val normalizedPath = normalizeSourcePath(path)
            val options = parseOptions(optionsJson)
            val sourceFile = File(normalizedPath)

            require(sourceFile.exists()) { "external code file does not exist: $normalizedPath" }
            require(sourceFile.isFile) { "external code path is not a file: $normalizedPath" }
            require(sourceFile.canRead()) { "external code file is not readable: $normalizedPath" }

            val canonicalPath = sourceFile.canonicalPath
            validateSourceFile(sourceType = sourceType, sourceFile = sourceFile, canonicalPath = canonicalPath)

            val nativeLibraryDir = resolveNativeLibraryDir(options.nativeLibraryDir)
            val artifactKey = buildArtifactKey(sourceType, canonicalPath, nativeLibraryDir)
            loadedArtifacts[artifactKey]?.let { existing ->
                return success(existing.toJson(indexOf(artifactKey), alreadyLoaded = true))
            }

            val optimizedDir = ensureOptimizedDir()
            val parent = getEffectiveClassLoader(baseClassLoader)
            val classLoader =
                DexClassLoader(
                    canonicalPath,
                    optimizedDir.absolutePath,
                    nativeLibraryDir,
                    parent
                )

            val artifact =
                LoadedArtifact(
                    sourceType = sourceType,
                    sourcePath = canonicalPath,
                    nativeLibraryDir = nativeLibraryDir,
                    classLoader = classLoader
                )
            loadedArtifacts[artifactKey] = artifact
            success(artifact.toJson(indexOf(artifactKey), alreadyLoaded = false))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load external ${sourceType.wireName}: ${e.message}", e)
            failure(e.message ?: "failed to load external ${sourceType.wireName}")
        }
    }

    private fun validateSourceFile(sourceType: SourceType, sourceFile: File, canonicalPath: String) {
        when (sourceType) {
            SourceType.DEX -> {
                val extension = sourceFile.extension.lowercase()
                require(extension == "dex") {
                    "loadDex only accepts .dex files, got: $canonicalPath"
                }
            }

            SourceType.JAR -> {
                val extension = sourceFile.extension.lowercase()
                require(extension == "jar") {
                    "loadJar only accepts .jar files, got: $canonicalPath"
                }
                ZipFile(sourceFile).use { zip ->
                    require(zip.getEntry("classes.dex") != null) {
                        "jar must contain classes.dex; plain JVM bytecode jars are not supported on Android"
                    }
                }
            }
        }
    }

    private fun normalizeSourcePath(path: String): String {
        val normalized = path.trim()
        require(normalized.isNotEmpty()) { "external code path is required" }
        return normalized
    }

    private fun parseOptions(optionsJson: String): LoadOptions {
        val normalized = optionsJson.trim()
        if (normalized.isEmpty()) {
            return LoadOptions(nativeLibraryDir = null)
        }

        val parsed = JSONObject(normalized)
        val nativeLibraryDir = parsed.optString("nativeLibraryDir").trim().ifEmpty { null }
        return LoadOptions(nativeLibraryDir = nativeLibraryDir)
    }

    private fun resolveNativeLibraryDir(nativeLibraryDir: String?): String? {
        val normalized = nativeLibraryDir?.trim()?.ifEmpty { null } ?: return null
        val dir = File(normalized)
        require(dir.exists()) { "native library dir does not exist: $normalized" }
        require(dir.isDirectory) { "native library dir is not a directory: $normalized" }
        return dir.canonicalPath
    }

    private fun ensureOptimizedDir(): File {
        val preferredDir = context.codeCacheDir ?: context.cacheDir
        requireNotNull(preferredDir) { "app cache directory is unavailable" }
        if (!preferredDir.exists()) {
            preferredDir.mkdirs()
        }
        require(preferredDir.exists() && preferredDir.isDirectory) {
            "optimized dex directory is unavailable: ${preferredDir.absolutePath}"
        }
        return preferredDir
    }

    private fun buildArtifactKey(
        sourceType: SourceType,
        canonicalPath: String,
        nativeLibraryDir: String?
    ): String {
        return listOf(sourceType.wireName, canonicalPath, nativeLibraryDir.orEmpty()).joinToString("|")
    }

    private fun indexOf(artifactKey: String): Int {
        return loadedArtifacts.keys.indexOf(artifactKey).coerceAtLeast(0)
    }

    private fun success(data: Any?): String {
        return JSONObject()
            .put("success", true)
            .put("data", data)
            .toString()
    }

    private fun failure(message: String): String {
        return JSONObject()
            .put("success", false)
            .put("error", message)
            .toString()
    }
}
