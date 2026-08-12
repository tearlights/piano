package com.gpiano.app.scoreworkspace

import android.content.Context

class WorkspaceSelectionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): String? = preferences.getString(KEY_STRUCTURE_ID, null)?.takeIf(String::isNotBlank)

    fun save(structureId: String) {
        require(structureId.isNotBlank()) { "结构化乐谱标识不能为空" }
        check(preferences.edit().putString(KEY_STRUCTURE_ID, structureId).commit()) {
            "无法保存最近打开的练习工作区"
        }
    }

    private companion object {
        const val PREFERENCES = "workspace-selection"
        const val KEY_STRUCTURE_ID = "structureId"
    }
}
