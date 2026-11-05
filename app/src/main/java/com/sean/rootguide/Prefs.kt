package com.sean.rootguide

import android.content.Context

object Prefs {

    private const val FILE = "rootguide"
    private const val KEY_BRANCH = "branch_id"
    private const val KEY_DONE_PREFIX = "done_"
    private const val KEY_MODULES = "installed_modules"
    private const val KEY_SUSFS_ON = "susfs_on"
    private const val KEY_LAST_BOOT_ID = "last_boot_id"

    /** 缓存上一次探测到的已装模块目录名，供清单页标「已装」 */
    fun saveInstalledModules(context: Context, modules: List<String>) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODULES, modules.joinToString(",")).apply()
    }

    fun getInstalledModules(context: Context): List<String> {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_MODULES, "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun saveBranchId(context: Context, id: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_BRANCH, id).apply()
    }

    fun getBranchId(context: Context): String? {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_BRANCH, null)
    }

    fun getDone(context: Context, branchId: String): MutableSet<String> {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getStringSet(KEY_DONE_PREFIX + branchId, emptySet()) ?: emptySet()
        return raw.toMutableSet()
    }

    fun saveDone(context: Context, branchId: String, done: Set<String>) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_DONE_PREFIX + branchId, done).apply()
    }

    /** 缓存最近一次检测到的 SUSFS 开关状态，供清单页（PlanActivity）决定推什么 Zygisk 组合 */
    fun saveSusfsOn(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SUSFS_ON, on).apply()
    }

    fun getSusfsOn(context: Context): Boolean {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_SUSFS_ON, false)
    }

    /** 监控上一次重启：记录上一次看到的 boot_id，变化时说明期间发生过真重启 */
    fun saveLastBootId(context: Context, bootId: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_BOOT_ID, bootId).apply()
    }

    fun getLastBootId(context: Context): String? {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_LAST_BOOT_ID, null)
    }
}
