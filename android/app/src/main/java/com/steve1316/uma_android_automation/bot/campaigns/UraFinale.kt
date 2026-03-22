package com.steve1316.uma_android_automation.bot.campaigns

import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.BotService
import com.steve1316.automation_library.utils.DiscordUtils
import com.steve1316.automation_library.data.SharedData

import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.bot.Campaign

import com.steve1316.uma_android_automation.types.DateYear
import com.steve1316.uma_android_automation.types.DateMonth
import com.steve1316.uma_android_automation.types.DatePhase
import com.steve1316.uma_android_automation.types.FanCountClass
import com.steve1316.uma_android_automation.types.BoundingBox

import com.steve1316.uma_android_automation.components.*

import android.util.Log
import android.graphics.Bitmap
import org.opencv.core.Point
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Handles the URA Finale scenario with scenario-specific logic and handling.
 *
 * @property game The [Game] instance for interacting with the game state.
 */
class UraFinale(game: Game) : Campaign(game) {
    override fun openFansDialog() {
        ButtonHomeFansInfo.click(game.imageUtils, region = game.imageUtils.regionTopHalf, tries = 10)
        bHasTriedCheckingFansToday = true
        game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)
    }
}
