package com.ai.assistance.operit.ui.features.chat.webview.workspace.editor

import java.util.Locale

/**
 * 根据文件名检测编程语言
 */
object LanguageDetector {
    
    /**
     * 根据文件名检测语言
     */
    fun detectLanguage(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return when (extension) {
            // JVM语言
            "kt" -> "kotlin"
            "java" -> "java"
            "groovy", "gradle" -> "groovy"
            "scala" -> "scala"
            
            // Web
            "js", "mjs", "cjs" -> "javascript"
            "ts" -> "typescript"
            "jsx" -> "javascriptreact"
            "tsx" -> "typescriptreact"
            "html", "htm" -> "html"
            "css" -> "css"
            "scss" -> "scss"
            "sass" -> "sass"
            "less" -> "less"
            "vue" -> "vue"
            
            // 系统语言
            "c" -> "c"
            "cpp", "cxx", "cc", "h", "hpp" -> "cpp"
            "go" -> "go"
            "rs" -> "rust"
            "swift" -> "swift"
            
            // 脚本语言
            "py" -> "python"
            "rb" -> "ruby"
            "php" -> "php"
            "pl" -> "perl"
            "lua" -> "lua"
            "sh", "bash", "zsh" -> "shell"
            "ps1" -> "powershell"
            "bat", "cmd" -> "bat"
            
            // 数据格式
            "json" -> "json"
            "xml" -> "xml"
            "yaml", "yml" -> "yaml"
            "toml" -> "toml"
            "ini" -> "ini"
            "csv" -> "csv"
            
            // 标记语言
            "md", "markdown" -> "markdown"
            "tex" -> "latex"
            "rst" -> "restructuredtext"
            
            // 查询语言
            "sql" -> "sql"
            "graphql", "gql" -> "graphql"
            
            // 配置文件
            "dockerfile" -> "dockerfile"
            "dockerignore" -> "ignore"
            "gitignore" -> "ignore"
            "properties" -> "properties"
            
            // 其他
            "txt" -> "plaintext"
            else -> "plaintext"
        }
    }
}
