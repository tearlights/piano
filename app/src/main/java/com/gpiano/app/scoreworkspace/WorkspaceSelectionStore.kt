package com.gpiano.app.scoreworkspace

import android.content.Context

class WorkspaceSelectionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): String? = preferences.getString(KEY_STRUCTURE_ID, null)?.takeIf(String::isNotBlank)

    fun save(structureId: String) {
        require(structureId.isNotBlank()) { "结构化乐谱标识不能为空" }
        preferences.edit().putString(KEY_STRUCTURE_ID, structureId).apply()
    }

    fun autoRestoreEnabled(): Boolean = preferences.getBoolean(KEY_AUTO_RESTORE, false)

    fun setAutoRestoreEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_RESTORE, enabled).apply()
    }

    private companion object {
        const val PREFERENCES = "workspace-selection"
        const val KEY_STRUCTURE_ID = "structureId"
        const val KEY_AUTO_RESTORE = "autoRestore"
    }
}
