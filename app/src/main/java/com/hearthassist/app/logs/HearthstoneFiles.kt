package com.hearthassist.app.logs

import android.os.Environment

object HearthstoneFiles {
    const val PACKAGE = "com.blizzard.wtcg.hearthstone"

    val baseDir: String
        get() = "${Environment.getExternalStorageDirectory().absolutePath}/Android/data/$PACKAGE/files"

    val logConfig: String get() = "$baseDir/log.config"
    val logsDir: String get() = "$baseDir/Logs"
}
