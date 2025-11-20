package com.ai.assistance.operit.ui.features.chat.webview.workspace.editor

/**
 * 代码编辑器接口
 */
interface NativeCodeEditor {
    /**
     * 撤销
     */
    fun undo()
    
    /**
     * 重做
     */
    fun redo()
    
    /**
     * 替换所有文本
     */
    fun replaceAllText(text: String)
    
    /**
     * 在当前光标位置插入文本
     */
    fun insertText(text: String)
    
    /**
     * 获取选中的文本
     */
    fun getSelectedText(): String?
}
