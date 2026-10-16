package com.safe.drivevoiceassist

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "kd_prefs"
    fun get(ctx: Context): SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun language(ctx: Context): String = get(ctx).getString("lang", "en-US") ?: "en-US"
    fun confirm(ctx: Context): Boolean = get(ctx).getBoolean("confirm", true)
    fun autoSendEmail(ctx: Context): Boolean = get(ctx).getBoolean("auto_email", false)
    fun countryCode(ctx: Context): String = get(ctx).getString("cc", "60") ?: "60"
    fun userName(ctx: Context): String = get(ctx).getString("user_name", "") ?: ""
    fun apiKey(ctx: Context): String = get(ctx).getString("api_key", "") ?: ""
    fun model(ctx: Context): String = get(ctx).getString("model", "claude-haiku-4-5") ?: "claude-haiku-4-5"
}
