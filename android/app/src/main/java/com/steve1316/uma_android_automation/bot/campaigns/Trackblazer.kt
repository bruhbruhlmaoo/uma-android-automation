package com.steve1316.uma_android_automation.bot.campaigns

import android.graphics.Bitmap
import android.util.Log
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.bot.DialogHandlerResult
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.bot.MainScreenAction
import com.steve1316.uma_android_automation.components.ButtonBack
import com.steve1316.uma_android_automation.components.ButtonClose
import com.steve1316.uma_android_automation.components.ButtonConfirmUse
import com.steve1316.uma_android_automation.components.ButtonOk
import com.steve1316.uma_android_automation.components.ButtonRaceDayRace
import com.steve1316.uma_android_automation.components.ButtonRaceListFullStats
import com.steve1316.uma_android_automation.components.ButtonRaces
import com.steve1316.uma_android_automation.components.ButtonShopTrackblazer
import com.steve1316.uma_android_automation.components.ButtonSkillUp
import com.steve1316.uma_android_automation.components.ButtonTraining
import com.steve1316.uma_android_automation.components.ButtonTrainingItems
import com.steve1316.uma_android_automation.components.ButtonUseTrainingItems
import com.steve1316.uma_android_automation.components.DialogConfirmUse
import com.steve1316.uma_android_automation.components.DialogExchangeComplete
import com.steve1316.uma_android_automation.components.DialogInterface
import com.steve1316.uma_android_automation.components.DialogUtils
import com.steve1316.uma_android_automation.components.IconGoalRibbon
import com.steve1316.uma_android_automation.components.IconRaceDayRibbon
import com.steve1316.uma_android_automation.components.IconTrainingEventHorseshoe
import com.steve1316.uma_android_automation.components.IconUnityCupTutorialHeader
import com.steve1316.uma_android_automation.types.DateMonth
import com.steve1316.uma_android_automation.types.DatePhase
import com.steve1316.uma_android_automation.types.DateYear
import com.steve1316.uma_android_automation.types.Mood
import com.steve1316.uma_android_automation.types.RaceGrade
import com.steve1316.uma_android_automation.types.ScannedItem
import com.steve1316.uma_android_automation.types.StatName
import com.steve1316.uma_android_automation.types.TrackblazerShopList
import com.steve1316.uma_android_automation.types.Trainee
import com.steve1316.uma_android_automation.utils.ScrollListEntry
import org.json.JSONArray
import org.opencv.core.Point

/**
 * Handles the Trackblazer scenario with scenario-specific logic and handling.
 *
 * @property game The [Game] instance for interacting with the game state.
 */
class Trackblazer(game: Game) : Campaign(game) {
    /** Flag indicating if the tutorial has been disabled. */
    private var tutorialDisabled = false

    /** Representation of the item shop list along with the mapping of items to their price and effect. */
    private val shopList: TrackblazerShopList = TrackblazerShopList(game)

    /** Current number of coins available to spend in the shop. */
    var shopCoins: Int = 0

    /** Map representing the current inventory of items. */
    var currentInventory: Map<String, Int> = mapOf()

    /** The limit for consecutive races before the bot should stop and recover. */
    private val consecutiveRacesLimit: Int = SettingsHelper.getIntSetting("scenarioOverrides", "trackblazerConsecutiveRacesLimit", 5)

    /** List of race grades that trigger a shop check afterward. */
    private val shopCheckGrades: List<RaceGrade> =
        try {
            val gradesString = SettingsHelper.getStringSetting("scenarioOverrides", "trackblazerShopCheckGrades", "[\"G1\",\"G2\",\"G3\"]")
            val jsonArray = JSONArray(gradesString)
            val grades = mutableListOf<RaceGrade>()
            for (i in 0 until jsonArray.length()) {
                val gradeName = jsonArray.getString(i)
                val grade = RaceGrade.fromName(gradeName)
                if (grade != null) {
                    grades.add(grade)
                }
            }
            grades
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] shopCheckGrades:: Failed to parse shopCheckGrades setting: ${e.message}")
            listOf(RaceGrade.G1, RaceGrade.G2, RaceGrade.G3)
        }

    /** Tracks the number of consecutive races performed. */
    private var consecutiveRaceCount: Int = 0

    /** Flag to prevent double incrementing the counter when OCR already updated it. */
    private var counterUpdatedByOCR: Boolean = false

    /** Whether the Reset Whistle has been used this turn. */
    private var bUsedWhistleToday: Boolean = false

    /** Whether the Good-Luck Charm has been used this turn. */
    private var bUsedCharmToday: Boolean = false

    /** Whether a race hammer has been used this turn. */
    private var bUsedHammerToday: Boolean = false

    /** Tracks whether the inventory has been synced at least once during this session. */
    private var bInventorySynced: Boolean = false

    /** Tracks items that were found to be disabled during the last inventory management pass. */
    private var disabledItems: Set<String> = setOf()

    /** Flag to track when a shop check should be performed after a race. */
    private var bShouldCheckShop: Boolean = false

    /** Threshold for energy level to use energy items. */
    private var energyThresholdToUseEnergyItems: Int = SettingsHelper.getIntSetting("scenarioOverrides", "trackblazerEnergyThreshold", 40)

    /** Whether the Reset Whistle forces training. */
    private val whistleForcesTraining: Boolean = SettingsHelper.getBooleanSetting("scenarioOverrides", "trackblazerWhistleForcesTraining", true)

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Debug Tests

    /**
     * Starts debug tests for the Trackblazer campaign.
     *
     * @return True if any tests were run, false otherwise.
     */
    override fun startTests(): Boolean {
        var bDidAnyTestsRun = super.startTests()

        val fnMap: Map<String, () -> Unit> =
            mapOf(
                "debugMode_startTrackblazerRaceSelectionTest" to ::startTrackblazerRaceSelectionTest,
                "debugMode_startTrackblazerInventorySyncTest" to ::startTrackblazerInventorySyncTest,
                "debugMode_startTrackblazerBuyItemsTest" to ::startTrackblazerBuyItemsTest,
            )

        for ((settingName, fn) in fnMap) {
            if (SettingsHelper.getBooleanSetting("debug", settingName)) {
                fn()
                bDidAnyTestsRun = true
            }
        }

        return bDidAnyTestsRun
    }

    /**
     * Debug test for Trackblazer's race selection logic.
     */
    fun startTrackblazerRaceSelectionTest() {
        MessageLog.i(TAG, "\n[TEST] Now beginning Trackblazer race selection test.")

        val sourceBitmap = game.imageUtils.getSourceBitmap()

        // If on Main Screen, navigate to the Race List screen first.
        if (checkMainScreen()) {
            MessageLog.i(TAG, "[TEST] Currently on Main Screen. Navigating to Race List...")
            if (!ButtonRaces.click(game.imageUtils, sourceBitmap = sourceBitmap) && !ButtonRaceDayRace.click(game.imageUtils, sourceBitmap = sourceBitmap)) {
                MessageLog.e(TAG, "[ERROR] startTrackblazerRaceSelectionTest:: Failed to click Races button.")
                return
            }
            game.wait(1.0)

            // Handle any consecutive race warning dialogs that might pop up.
            handleDialogs(args = mapOf("overrideIgnoreConsecutiveRaceWarning" to true))
        }

        // Now check if we are on the Race List screen.
        if (ButtonRaceListFullStats.check(game.imageUtils)) {
            // Update the date first for racing logic.
            updateDate(isOnMainScreen = false)

            MessageLog.i(TAG, "[TEST] Currently on Race List screen. Calling findSuitableTrackblazerRace($consecutiveRaceCount)...")
            val result = racing.findSuitableTrackblazerRace(consecutiveRaceCount)

            if (result != null) {
                val (point, raceData) = result
                MessageLog.i(TAG, "[TEST] Selection Finalized: ${raceData.name} (${raceData.grade}) at (${point.x}, ${point.y}).")
            } else {
                MessageLog.i(TAG, "[TEST] findSuitableTrackblazerRace returned null. No suitable races found.")
            }
        } else {
            MessageLog.e(TAG, "[ERROR] startTrackblazerRaceSelectionTest:: Not on Main Screen or Race List screen. Ending test.")
        }
    }

    /**
     * Debug test for Trackblazer's inventory sync logic.
     */
    fun startTrackblazerInventorySyncTest() {
        MessageLog.i(TAG, "\n[TEST] Now beginning Trackblazer inventory sync test.")

        // If on Main Screen, open Training Items.
        if (checkMainScreen()) {
            MessageLog.i(TAG, "[TEST] Currently on Main Screen. Opening Training Items...")
            if (shopList.openTrainingItemsDialog()) {
                MessageLog.i(TAG, "[TEST] Training Items dialog opened. Calling manageInventoryItems with bDryRun = true and bQuickUseOnly = true...")
                manageInventoryItems(bQuickUseOnly = true, bDryRun = true)
            } else {
                MessageLog.e(TAG, "[ERROR] startTrackblazerInventorySyncTest:: Failed to open Training Items dialog.")
            }
        } else if (ButtonClose.check(game.imageUtils)) {
            // Assume we are already in some dialog, possibly training items.
            MessageLog.i(TAG, "[TEST] Close button detected. Assuming Training Items dialog is open. Calling manageInventoryItems...")
            manageInventoryItems(bQuickUseOnly = true, bDryRun = true)
        } else {
            MessageLog.e(TAG, "[ERROR] startTrackblazerInventorySyncTest:: Not on Main Screen or in a dialog. Ending test.")
        }
    }

    /**
     * Debug test for Trackblazer's buying process logic.
     */
    fun startTrackblazerBuyItemsTest() {
        MessageLog.i(TAG, "\n[TEST] Now beginning Trackblazer buy items test.")

        // If on Main Screen, open the Shop.
        if (checkMainScreen()) {
            MessageLog.i(TAG, "[TEST] Currently on Main Screen. Opening Shop...")
            openShop()
            game.wait(1.0)
        }

        // Check if we are in the Shop.
        if (ButtonTrainingItems.check(game.imageUtils)) {
            MessageLog.i(TAG, "[TEST] Shop detected. Calling buyItems with bDryRun = true...")
            buyItems(bDryRun = true)
        } else {
            MessageLog.e(TAG, "[ERROR] startTrackblazerBuyItemsTest:: Shop not detected. Ending test.")
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    override fun handleDialogs(dialog: DialogInterface?, args: Map<String, Any>): DialogHandlerResult {
        val result: DialogHandlerResult = super.handleDialogs(dialog, args + mapOf("dialogNameToDefer" to "consecutive_race_warning"))

        // Extract dialog name if result has one.
        val dialogName =
            when (result) {
                is DialogHandlerResult.Handled -> result.dialog.name
                is DialogHandlerResult.Unhandled -> result.dialog.name
                is DialogHandlerResult.Deferred -> result.dialog.name
                else -> ""
            }

        if (result !is DialogHandlerResult.Unhandled) {
            // Only handle successful results that are NOT the consecutive race warning,
            // as we want to apply Trackblazer-specific logic for that one.
            if (dialogName != "consecutive_race_warning") {
                return result
            }
        }

        // Use the dialog from the result if super already detected it, otherwise detect it now.
        val detectedDialog =
            when (result) {
                is DialogHandlerResult.Handled -> result.dialog
                is DialogHandlerResult.Unhandled -> result.dialog
                is DialogHandlerResult.Deferred -> result.dialog
                else -> DialogUtils.getDialog(game.imageUtils)
            } ?: return DialogHandlerResult.NoDialogDetected

        when (detectedDialog.name) {
            "exchange_complete" -> {
                val boughtItems = args["itemsBought"] as? List<String> ?: emptyList()
                val quickUseItemsOnly = boughtItems.filter { shopList.shopItems[it]?.isQuickUsage == true }

                if (quickUseItemsOnly.isNotEmpty()) {
                    MessageLog.i(TAG, "[TRACKBLAZER] Quick-use items were purchased. Navigating and queuing for usage...")
                    val usedItems = shopList.useSpecificItems(quickUseItemsOnly, bUseAll = true, reason = "Quick-use after purchase.")
                    usedItems.forEach { useInventoryItem(it) }

                    // This clicks the "Confirm Use" button on the "Exchange Complete" dialog.
                    if (detectedDialog.ok(game.imageUtils)) {
                        game.wait(0.5)
                        // This clicks the "Use Training Items" button on the "Confirm Use" dialog.
                        handleDialogs(DialogConfirmUse)
                        // This clicks the "Close" button on the "Exchange Complete" dialog after handling quick-use.
                        detectedDialog.close(game.imageUtils)
                    } else {
                        // Fallback to closing the dialog if "Confirm Use" button was not found.
                        MessageLog.i(TAG, "[TRACKBLAZER] Quick-use items were identified but the \"Confirm Use\" button was not found. Closing dialog...")
                        detectedDialog.close(game.imageUtils)
                    }
                } else {
                    MessageLog.i(TAG, "[TRACKBLAZER] No quick-use items were purchased. Closing dialog...")
                    detectedDialog.close(game.imageUtils)
                }
            }

            "confirm_use" -> {
                detectedDialog.ok(game.imageUtils)
            }

            "shop" -> {
                // Once it gets to Junior Year Early July, the shop will be unlocked for use.
                // But the date update has not happened yet, so we need to check for the previous date instead.
                if (date.year == DateYear.JUNIOR && date.month == DateMonth.JUNE && date.phase == DatePhase.LATE) {
                    MessageLog.i(TAG, "[TRACKBLAZER] Shop unlocked! Initiating the first time buying process.")
                } else {
                    MessageLog.i(TAG, "[TRACKBLAZER] Shop discount detected! Initiating buying process.")
                }

                detectedDialog.ok(game.imageUtils)
                game.wait(3.0)

                // Clear the shop check flag as the shop is already being handled.
                if (bShouldCheckShop) {
                    MessageLog.i(TAG, "[TRACKBLAZER] Shop check fallback: Shop handled via dialog.")
                    bShouldCheckShop = false
                }

                buyItems()
            }

            "consecutive_race_warning" -> {
                val okButtonLocation: Point? = ButtonOk.find(game.imageUtils).first

                if (okButtonLocation != null) {
                    val ocrText =
                        game.imageUtils.performOCRFromReference(
                            okButtonLocation,
                            offsetX = -560,
                            offsetY = -525,
                            width = game.imageUtils.relWidth(690),
                            height = game.imageUtils.relHeight(50),
                            useThreshold = true,
                            useGrayscale = true,
                            scale = 2.0,
                            ocrEngine = "mlkit",
                            debugName = "TrackblazerConsecutiveRaceOCR",
                        )

                    Log.d(TAG, "[DEBUG] handleDialogs:: OCR text from consecutive warning: \"$ocrText\"")

                    // Regex: This will put you at ([0-9]+) consecutive races.
                    val match = Regex("""([0-9]+)""").find(ocrText)
                    val ocrCount = match?.groups?.get(1)?.value?.toInt() ?: -1

                    if (ocrCount != -1) {
                        Log.d(TAG, "[DEBUG] handleDialogs:: OCR detected a count of $ocrCount consecutive races.")

                        // Trust OCR as the primary source of truth if it successfully parses a number.
                        consecutiveRaceCount = ocrCount
                        counterUpdatedByOCR = true
                    } else {
                        MessageLog.w(TAG, "[WARN] handleDialogs:: Failed to parse consecutive race count from OCR. Counter will be incremented after race.")
                    }
                } else {
                    MessageLog.e(TAG, "[ERROR] handleDialogs:: Failed to find ButtonOk on consecutive race warning screen. Counter will be incremented after race.")
                }

                MessageLog.i(TAG, "[TRACKBLAZER] Current consecutive race count: $consecutiveRaceCount.")

                // Check if we should ignore the warning based on global settings or arguments.
                val overrideIgnoreConsecutiveRaceWarning = args["overrideIgnoreConsecutiveRaceWarning"] as? Boolean ?: false
                val forceRace = overrideIgnoreConsecutiveRaceWarning || racing.enableForceRacing || racing.ignoreConsecutiveRaceWarning

                // Edge case: if there is only 1 turn left before a mandatory race, we can safely race
                // even if it would be our 6th consecutive race.
                val turnsRemaining = game.imageUtils.determineTurnsRemainingBeforeNextGoal()
                val onlyOneTurnLeft = turnsRemaining == 1

                if (forceRace) {
                    MessageLog.i(TAG, "[TRACKBLAZER] Consecutive race warning! Forced racing enabled. Continuing.")
                    detectedDialog.ok(game.imageUtils)
                    game.wait(1.0, skipWaitingForLoading = true)
                } else {
                    // A -30 stat penalty can apply starting from 3 consecutive races.
                    if (consecutiveRaceCount >= 3) {
                        MessageLog.w(TAG, "[WARN] handleDialogs:: Current consecutive race count is $consecutiveRaceCount. Note that a -30 stat penalty can apply starting from 3 consecutive races!")
                    }

                    if (consecutiveRaceCount < (consecutiveRacesLimit + 1) || onlyOneTurnLeft) {
                        if (onlyOneTurnLeft && consecutiveRaceCount >= (consecutiveRacesLimit + 1)) {
                            MessageLog.i(
                                TAG,
                                "[TRACKBLAZER] Consecutive race count $consecutiveRaceCount >= ${consecutiveRacesLimit + 1}, but only 1 turn remains before mandatory race. Racing is safe. Continuing.",
                            )
                        } else {
                            MessageLog.i(TAG, "[TRACKBLAZER] Consecutive race count $consecutiveRaceCount < ${consecutiveRacesLimit + 1}. Continuing.")
                        }
                        detectedDialog.ok(game.imageUtils)
                        game.wait(1.0, skipWaitingForLoading = true)
                    } else {
                        MessageLog.w(TAG, "[WARN] handleDialogs:: Consecutive race count $consecutiveRaceCount >= ${consecutiveRacesLimit + 1}. Aborting racing.")
                        racing.encounteredRacingPopup = false
                        racing.clearRacingRequirementFlags()
                        detectedDialog.close(game.imageUtils)
                    }
                }
                return DialogHandlerResult.Handled(detectedDialog)
            }

            "try_again" -> {
                // Specialized Trackblazer retry logic.
                if (racing.lastRaceIsRival && !racing.bRetriedCurrentRace) {
                    MessageLog.i(TAG, "[TRACKBLAZER] Rival Race retry button is available. Retrying once.")
                    racing.bRetriedCurrentRace = true
                    if (detectedDialog.ok(game.imageUtils)) {
                        game.wait(1.0)
                    }
                    return DialogHandlerResult.Handled(detectedDialog)
                } else if (racing.lastRaceGrade == RaceGrade.G1 && racing.raceRetries >= 0) {
                    MessageLog.i(TAG, "[TRACKBLAZER] G1 race retry button is available. Retrying.")
                    racing.raceRetries--
                    if (detectedDialog.ok(game.imageUtils)) {
                        game.wait(1.0)
                    }
                    return DialogHandlerResult.Handled(detectedDialog)
                } else {
                    MessageLog.w(TAG, "[WARN] handleDialogs:: No retries remaining for this race. Closing dialog.")
                    detectedDialog.close(game.imageUtils)
                    return DialogHandlerResult.Handled(detectedDialog)
                }
            }

            else -> {
                Log.w(TAG, "[WARN] handleDialogs:: Unknown dialog \"${detectedDialog.name}\" detected so it will not be handled.")
                return DialogHandlerResult.Unhandled(detectedDialog)
            }
        }

        game.wait(0.5)
        return DialogHandlerResult.Handled(detectedDialog)
    }

    override fun handleTrainingEvent() {
        if (!tutorialDisabled) {
            tutorialDisabled =
                if (IconUnityCupTutorialHeader.check(game.imageUtils)) {
                    // If the tutorial is detected, select the second option to close it.
                    MessageLog.i(TAG, "[TRACKBLAZER] Detected tutorial for Trackblazer. Closing it now.")
                    val trainingOptionLocations: ArrayList<Point> = IconTrainingEventHorseshoe.findAll(game.imageUtils)
                    if (trainingOptionLocations.size >= 2) {
                        game.tap(trainingOptionLocations[1].x, trainingOptionLocations[1].y, IconTrainingEventHorseshoe.template.path)
                        true
                    } else {
                        MessageLog.w(TAG, "[WARN] handleTrainingEvent:: Could not find training options to dismiss tutorial.")
                        false
                    }
                } else {
                    MessageLog.i(TAG, "[TRACKBLAZER] Tutorial must have already been dismissed.")
                    super.handleTrainingEvent()
                    true
                }
        } else {
            super.handleTrainingEvent()
        }
    }

    override fun recoverEnergy(sourceBitmap: Bitmap?): Boolean {
        MessageLog.i(TAG, "[TRACKBLAZER] Resetting consecutive race counter due to energy recovery.")
        consecutiveRaceCount = 0
        return super.recoverEnergy(sourceBitmap)
    }

    override fun recoverMood(sourceBitmap: Bitmap?): Boolean {
        MessageLog.i(TAG, "[TRACKBLAZER] Resetting consecutive race counter due to mood recovery.")
        consecutiveRaceCount = 0
        return super.recoverMood(sourceBitmap)
    }

    override fun handleRaceEvents(isScheduledRace: Boolean): Boolean {
        counterUpdatedByOCR = false

        // If it's not a scheduled race, we need to apply Trackblazer-specific filtering.
        if (!isScheduledRace) {
            val sourceBitmap = game.imageUtils.getSourceBitmap()

            // Check if we're at a mandatory race screen first (IconRaceDayRibbon or IconGoalRibbon).
            // If we are, we should treat it as a mandatory race and NOT an extra race.
            if (IconRaceDayRibbon.check(game.imageUtils, sourceBitmap = sourceBitmap) || IconGoalRibbon.check(game.imageUtils, sourceBitmap = sourceBitmap)) {
                MessageLog.i(TAG, "[TRACKBLAZER] Mandatory race ribbon detected. Processing as mandatory race.")
                return super.handleRaceEvents(true)
            }

            MessageLog.i(TAG, "[TRACKBLAZER] Checking for suitable races.")
            // We need to enter the race list to check for predictions and grades.
            // Try both standard Races button and the Race Day variant.
            if (!ButtonRaces.click(game.imageUtils, sourceBitmap = sourceBitmap) && !ButtonRaceDayRace.click(game.imageUtils, sourceBitmap = sourceBitmap)) {
                MessageLog.e(TAG, "[ERROR] handleRaceEvents:: Failed to click Races button.")
                return false
            }
            game.wait(1.0)

            // Handle any consecutive race warning dialogs that might pop up after clicking "Races".
            val dialogResult = handleDialogs(args = mapOf("overrideIgnoreConsecutiveRaceWarning" to true))
            if (dialogResult is DialogHandlerResult.Handled && consecutiveRaceCount >= consecutiveRacesLimit && game.imageUtils.determineTurnsRemainingBeforeNextGoal() != 1) {
                MessageLog.i(TAG, "[TRACKBLAZER] Consecutive race warning obeyed. Aborting racing.")
                return false
            }

            val suitableRaceResult = racing.findSuitableTrackblazerRace(consecutiveRaceCount)
            if (suitableRaceResult != null) {
                val suitableRaceLocation = suitableRaceResult.first
                val raceData = suitableRaceResult.second
                MessageLog.i(TAG, "[TRACKBLAZER] Found suitable race: ${raceData.name} (${raceData.grade}). Processing items.")

                // Use race-related items (Hammers, Glow Sticks).
                // Skip OP, Pre-debut, and Maiden races as hammers provide no benefit for those grades.
                if (raceData.grade == RaceGrade.G1 || raceData.grade == RaceGrade.G2 || raceData.grade == RaceGrade.G3) {
                    useRaceItems(raceData.grade, raceData.fans)
                } else {
                    MessageLog.i(TAG, "[TRACKBLAZER] Non-G1/G2/G3 race detected (${raceData.grade}). Skipping race item usage.")
                }

                racing.lastRaceGrade = raceData.grade
                racing.lastRaceIsRival = raceData.isRival
                game.tap(suitableRaceLocation.x, suitableRaceLocation.y, "race_list_prediction_double_star", ignoreWaiting = true)
                game.wait(0.5)
            } else {
                MessageLog.i(TAG, "[TRACKBLAZER] No suitable races found. Backing out and training.")
                ButtonBack.click(game.imageUtils)
                game.wait(0.5)
                return false
            }
        }

        val result = super.handleRaceEvents(isScheduledRace)
        if (result) {
            if (!counterUpdatedByOCR) {
                consecutiveRaceCount++
                MessageLog.i(TAG, "[TRACKBLAZER] Incremented consecutive race count to $consecutiveRaceCount.")
            } else {
                MessageLog.i(TAG, "[TRACKBLAZER] Consecutive race count was already updated by OCR: $consecutiveRaceCount.")
            }

            // Check if we should perform a shop check after this race.
            // Any graded race defined in the settings should trigger a shop check.
            if (shopCheckGrades.contains(racing.lastRaceGrade)) {
                MessageLog.i(TAG, "[TRACKBLAZER] Graded race detected (${racing.lastRaceGrade}). Shop check will be performed on main screen.")
                bShouldCheckShop = true
            }
        }
        return result
    }

    override fun resetDailyFlags() {
        bUsedWhistleToday = false
        bUsedCharmToday = false
        bUsedHammerToday = false
    }

    override fun onBeforeMainScreenUpdate() {
        // Buy items if a shop check is pending after a race.
        if (bShouldCheckShop) {
            MessageLog.i(TAG, "[TRACKBLAZER] Pending shop check detected! Checking Shop for new items...")
            bShouldCheckShop = false
            game.wait(0.5)
            if (openShop()) {
                buyItems(bAfterRacePurchase = true)
            }
        }
    }

    override fun onMainScreenEntry() {
        // Before taking any action, check for items to use.
        // This handles Stats, Energy, Mood, and Bad Conditions.
        // Training items are only available starting Turn 13 (Junior Year Early July).
        if (date.day >= 13) {
            useItems(trainee)
        }
    }

    override fun shouldRecoverMood(sourceBitmap: Bitmap): Boolean {
        // Recover mood if it is below Normal, and we do not have any mood recovery items.
        if (trainee.mood < Mood.NORMAL) {
            val hasMoodItems =
                currentInventory.any { (name, count) ->
                    count > 0 && (name == "Berry Sweet Cupcake" || name == "Plain Cupcake")
                }
            if (!hasMoodItems) {
                MessageLog.i(TAG, "[TRACKBLAZER] Mood is ${trainee.mood} and no mood items are available. Attempting to recover mood via rest/recreation...")
                return true
            }
        }
        return false
    }

    override fun performMoodRecovery(sourceBitmap: Bitmap): Boolean {
        // If we don't have Cupcakes, we fall back to the standard recovery method.
        return recoverMood()
    }

    override fun decideNextAction(): MainScreenAction {
        // Summer Training: Train during July and August in Classic/Senior.
        if (date.isSummer()) {
            MessageLog.i(TAG, "[TRACKBLAZER] It is Summer. Prioritizing training.")
            return MainScreenAction.TRAIN
        }

        // Finale: Train during the final 3 turns (Qualifier, Semifinal, Finals).
        if (date.bIsFinaleSeason && date.day >= 73) {
            MessageLog.i(TAG, "[TRACKBLAZER] It is the Finale. Prioritizing training.")
            return MainScreenAction.TRAIN
        }

        // Otherwise, use base class decision logic.
        return super.decideNextAction()
    }

    override fun executeAction(action: MainScreenAction, bIsScheduledRaceDay: Boolean): Boolean {
        val result =
            when (action) {
                MainScreenAction.TRAIN -> {
                    MessageLog.i(TAG, "[TRACKBLAZER] Decision made to train.")
                    handleTrackblazerTraining()
                    bHasCheckedDateThisTurn = false
                    true
                }

                else -> {
                    super.executeAction(action, bIsScheduledRaceDay)
                }
            }

        if (result && action != MainScreenAction.NONE) {
            // Turn is over, decrement megaphone counter.
            if (trainee.megaphoneTurnCounter > 0) {
                trainee.megaphoneTurnCounter--
                MessageLog.i(TAG, "[TRACKBLAZER] Megaphone duration reduced. Turns remaining: ${trainee.megaphoneTurnCounter}.")
            }
        }

        return result
    }

    override fun onRaceWin() {
        MessageLog.i(TAG, "[TRACKBLAZER] Rival Race win detected via post-race popup.")
        bShouldCheckShop = true
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Opens the Shop UI.
     *
     * @param tries The number of scan attempts to perform to find the shop button.
     * @return True if the shop was opened successfully, false otherwise.
     */
    fun openShop(tries: Int = 5): Boolean {
        if (ButtonShopTrackblazer.click(game.imageUtils, tries = tries)) {
            game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)
            return true
        } else {
            MessageLog.e(TAG, "[ERROR] openShop:: Unable to open the Shop due to failing to find its button.")
            return false
        }
    }

    /**
     * Reads the Shop Coins amount via OCR and updates our internal count.
     * If it fails to read the Shop Coins, it will return the last known Shop Coins amount.
     *
     * @return The Shop Coins amount.
     */
    fun updateShopCoins(): Int {
        MessageLog.i(TAG, "[TRACKBLAZER] Updating current amount of Shop Coins...")
        val (trainingItemsButtonLocation, sourceBitmap) = ButtonTrainingItems.find(game.imageUtils)
        if (trainingItemsButtonLocation == null) {
            MessageLog.e(TAG, "[ERROR] updateShopCoins:: Failed to find Training Items button.")
            return shopCoins
        }
        val coinText =
            game.imageUtils.performOCROnRegion(
                sourceBitmap,
                game.imageUtils.relX(trainingItemsButtonLocation.x, -37),
                game.imageUtils.relY(trainingItemsButtonLocation.y, 84),
                game.imageUtils.relWidth(175),
                game.imageUtils.relHeight(60),
                useThreshold = false,
                useGrayscale = true,
                scale = 1.0,
                ocrEngine = "mlkit",
                debugName = "ShopCoins",
            )

        return try {
            val cleanedText = coinText.replace(Regex("[^0-9]"), "")
            if (cleanedText.isEmpty()) {
                MessageLog.w(TAG, "[WARN] updateShopCoins:: Parsed empty string for Shop Coins from raw text: \"$coinText\".")
                shopCoins
            } else {
                shopCoins = cleanedText.toInt()
                MessageLog.i(TAG, "[INFO] Current Shop Coins: $shopCoins (Raw OCR text: \"$coinText\")")
                shopCoins
            }
        } catch (_: NumberFormatException) {
            MessageLog.e(TAG, "[ERROR] updateShopCoins:: Failed to parse Shop Coins from OCR text: \"$coinText\".")
            shopCoins
        }
    }

    /**
     * Starts the process to buy items from the Shop.
     *
     * @param priorityList An ordered list of item names to buy. Defaults to an empty list.
     * @param bDryRun If true, only logs intentions without performing any clicks.
     * @param bAfterRacePurchase If true, indicates this process was triggered by a post-race shop check.
     */
    fun buyItems(priorityList: List<String> = listOf(), bDryRun: Boolean = false, bAfterRacePurchase: Boolean = false) {
        val finalPriorityList = priorityList.ifEmpty { getPriorityList() }

        if (bAfterRacePurchase) {
            MessageLog.i(TAG, "[TRACKBLAZER] Buying extra items after participating in a race...")
        }
        MessageLog.i(TAG, "[TRACKBLAZER] Initiating buying process.")

        // Update current coins via OCR before buying.
        updateShopCoins()

        // If the shop coins are 0, it is possible that the OCR failed to read them correctly.
        // In this case, we will initiate a "Force Purchase" process to attempt to buy items until we can't anymore.
        val bForcePurchase = shopCoins == 0
        if (bForcePurchase) {
            MessageLog.i(TAG, "[TRACKBLAZER] Shop coins read as 0. This may be an OCR failure. Initiating Force Purchase mode.")
        }

        // Buy items from the shop.
        // Rule: Each item is limited to 5 in the inventory.
        // Bad Condition items except Rich Hand Cream and Miracle Cure will be limited to 1 in our current inventory.
        val nonPriorityBadConditions = listOf("Fluffy Pillow", "Pocket Planner", "Smart Scale", "Aroma Diffuser", "Practice Drills DVD")
        val inventoryLimits =
            finalPriorityList.associateWith { itemName ->
                val itemCount = currentInventory[itemName] ?: 0
                val maxLimit = if (nonPriorityBadConditions.contains(itemName)) 1 else 5
                (maxLimit - itemCount).coerceAtLeast(0)
            }

        val filteredPriorityList = finalPriorityList.filter { (inventoryLimits[it] ?: 0) > 0 }

        if (filteredPriorityList.isEmpty()) {
            printCurrentInventory()
        } else if (bDryRun) {
            MessageLog.i(TAG, "[TEST] Dry Run: Identified items that would be bought: ${filteredPriorityList.joinToString(", ")}")
            shopList.buyItems(filteredPriorityList, shopCoins, inventoryLimits, bDryRun = true, bForcePurchase = bForcePurchase)
            return
        }

        val itemsBought = shopList.buyItems(filteredPriorityList, shopCoins, inventoryLimits, bForcePurchase = bForcePurchase)
        if (itemsBought.isNotEmpty()) {
            // Update internal inventory.
            val nextInventory = currentInventory.toMutableMap()
            itemsBought.forEach { itemName ->
                nextInventory[itemName] = (nextInventory[itemName] ?: 0) + 1
            }
            currentInventory = nextInventory.toMap()

            // Handle "Exchange Complete" dialog.
            if (handleDialogs(DialogExchangeComplete, args = mapOf("itemsBought" to itemsBought)) is DialogHandlerResult.Handled) {
                MessageLog.i(TAG, "[TRACKBLAZER] Successfully handled \"Exchange Complete\" dialog.")

                // Update internal coins count via OCR after purchase.
                updateShopCoins()

                ButtonBack.click(game.imageUtils)
                game.wait(2.0)
            }
        }

        // Exit the Shop to return to the Main screen.
        MessageLog.i(TAG, "[TRACKBLAZER] Shop process complete. Returning up to the previous screen.")
        ButtonBack.click(game.imageUtils)
        game.wait(1.0)
    }

    /**
     * Generates a priority list of items to buy based on current state and rules.
     *
     * @return An ordered list of item names.
     */
    private fun getPriorityList(): List<String> {
        val topStats = training.statPrioritization.take(3)
        val priorityList = mutableListOf<String>()

        // 1. Top Tier Priorities (Good-Luck Charms, Hammers, Glow Sticks, Priority heals, Priority Energy/Bond).
        priorityList.add("Good-Luck Charm")
        priorityList.add("Master Cleat Hammer")
        priorityList.add("Artisan Cleat Hammer")
        priorityList.add("Glow Sticks")
        priorityList.add("Royal Kale Juice")
        priorityList.add("Grilled Carrots")
        priorityList.add("Rich Hand Cream")
        priorityList.add("Miracle Cure")

        // 2. Stats (Excluding Notepads).
        val statsOrdered = listOf("Scroll", "Manual")
        val statNamesOrdered = listOf("Speed", "Stamina", "Power", "Guts", "Wit")
        statsOrdered.forEach { type ->
            statNamesOrdered.forEach { name ->
                priorityList.add("$name $type")
            }
        }

        // 3. Energy + Mood.
        priorityList.add("Vita 65")
        priorityList.add("Vita 40")
        priorityList.add("Vita 20")
        priorityList.add("Berry Sweet Cupcake")
        priorityList.add("Plain Cupcake")

        // 4. Training Effects (Megaphones and specific Ankle Weights).
        priorityList.add("Empowering Megaphone")
        priorityList.add("Motivating Megaphone")
        topStats.forEach { stat ->
            val ankleWeight =
                when (stat) {
                    StatName.SPEED -> "Speed Ankle Weights"
                    StatName.STAMINA -> "Stamina Ankle Weights"
                    StatName.POWER -> "Power Ankle Weights"
                    StatName.GUTS -> "Guts Ankle Weights"
                    else -> null
                }
            if (ankleWeight != null) priorityList.add(ankleWeight)
        }
        priorityList.add("Coaching Megaphone")

        // 5. Heal Bad Conditions (Non-priority ones, limit 1 logic is handled in buyItems()).
        priorityList.add("Fluffy Pillow")
        priorityList.add("Pocket Planner")
        priorityList.add("Smart Scale")
        priorityList.add("Aroma Diffuser")
        priorityList.add("Practice Drills DVD")

        // 6. Training Facilities (Top 3 stats only).
        topStats.forEach { stat ->
            val trainingApp =
                when (stat) {
                    StatName.SPEED -> "Speed Training Application"
                    StatName.STAMINA -> "Stamina Training Application"
                    StatName.POWER -> "Power Training Application"
                    StatName.GUTS -> "Guts Training Application"
                    StatName.WIT -> "Wit Training Application"
                }
            priorityList.add(trainingApp)
        }

        // 7. Lowest Priority Energy.
        priorityList.add("Energy Drink MAX")
        priorityList.add("Energy Drink MAX EX")

        return priorityList
    }

    /**
     * Decrements an item's count in the internal inventory.
     *
     * @param itemName The name of the item used.
     */
    private fun useInventoryItem(itemName: String) {
        val nextInventory = currentInventory.toMutableMap()
        val count = nextInventory[itemName] ?: 0
        if (count > 0) {
            nextInventory[itemName] = count - 1
            MessageLog.i(TAG, "[TRACKBLAZER] Decremented $itemName. Remaining: ${nextInventory[itemName]}.")
        }
        currentInventory = nextInventory.toMap()
    }

    /**
     * Confirms the usage of items and closes the Training Items dialog.
     *
     * @param itemsUsedCount The number of items used during this pass to determine the animation delay.
     */
    private fun confirmAndCloseItemDialog(itemsUsedCount: Int = 1) {
        MessageLog.i(TAG, "[TRACKBLAZER] Confirming usage of $itemsUsedCount items.")
        ButtonConfirmUse.click(game.imageUtils)
        game.wait(game.dialogWaitDelay)
        ButtonUseTrainingItems.click(game.imageUtils)

        // Lengthy delay here for the animation to finish.
        // We increase the delay by a second for each additional item to be used after 3 items.
        val animationDelay = if (itemsUsedCount > 3) 5.0 + (itemsUsedCount - 3) else 5.0
        MessageLog.i(TAG, "[TRACKBLAZER] Waiting for animation to finish (Delay: $animationDelay seconds).")
        game.wait(animationDelay)

        // Finalize by closing the dialog.
        MessageLog.i(TAG, "[TRACKBLAZER] Closing training items dialog.")
        if (ButtonClose.click(game.imageUtils, tries = 50)) {
            game.wait(game.dialogWaitDelay)
        }
    }

    /**
     * Clicks the plus button for an item in the item list and updates inventory.
     *
     * @param itemName The name of the item.
     * @param entry The ScrollListEntry of the item.
     * @param logMessage The message to log when clicking.
     * @param nextInventory The current inventory map being updated during this pass.
     * @param recheck If true, captures a fresh crop of the entry to re-verify the button state.
     * @param reason Optional reason for using the item.
     * @return True if the button was clicked, false otherwise.
     */
    private fun clickItemPlusButton(itemName: String, entry: ScrollListEntry, logMessage: String, nextInventory: MutableMap<String, Int>, recheck: Boolean = false, reason: String? = null): Boolean {
        val bitmapToUse: Bitmap =
            if (recheck) {
                val source = game.imageUtils.getSourceBitmap()
                game.imageUtils.createSafeBitmap(source, entry.bbox.x, entry.bbox.y, entry.bbox.w, entry.bbox.h, "recheck item")
            } else {
                entry.bitmap
            } ?: return false

        if (ButtonSkillUp.checkDisabled(game.imageUtils, bitmapToUse) == true) return false

        val plusPoint = ButtonSkillUp.findImageWithBitmap(game.imageUtils, bitmapToUse)
        if (plusPoint != null) {
            MessageLog.i(TAG, logMessage)
            game.tap(entry.bbox.x + plusPoint.x, entry.bbox.y + plusPoint.y)

            // Update the provided inventory map.
            val count = nextInventory[itemName] ?: 0
            if (count > 0) {
                nextInventory[itemName] = count - 1
                MessageLog.i(TAG, "[TRACKBLAZER] Decremented $itemName. Remaining: ${nextInventory[itemName]}.")
            }

            return true
        }
        return false
    }

    /**
     * Handles the specialized training process for Trackblazer, including item usage.
     */
    private fun handleTrackblazerTraining() {
        MessageLog.i(TAG, "[TRACKBLAZER] Starting specialized Training process.")

        // Enter the Training screen.
        if (!ButtonTraining.click(game.imageUtils)) {
            MessageLog.e(TAG, "[ERROR] handleTrackblazerTraining:: Failed to enter Training screen.")
            return
        }
        game.wait(0.5)

        // Initial Training Analysis.
        val hasCharm = date.day >= 13 && !bUsedCharmToday && (currentInventory["Good-Luck Charm"] ?: 0) > 0
        training.analyzeTrainings(ignoreFailureChance = hasCharm)
        var trainingSelected: StatName? = training.recommendTraining()

        // Finally, perform a consolidated item usage pass after the training is finalized.
        if (date.day >= 13) {
            useItems(trainee, trainingSelected)
        }

        // Reset Whistle Check: Use if recommendations are poor.
        // We define "poor" as no training being selected or certain other conditions.
        if (date.day >= 13 && !bUsedWhistleToday && trainingSelected == null) {
            val hasWhistle = (currentInventory["Reset Whistle"] ?: 0) > 0 && !disabledItems.contains("Reset Whistle")
            if (hasWhistle) {
                MessageLog.i(TAG, "[TRACKBLAZER] No suitable training found. Using Reset Whistle.")
                if (shopList.openTrainingItemsDialog()) {
                    if (shopList.useSpecificItems(listOf("Reset Whistle"), reason = "No suitable training found.").isNotEmpty()) {
                        confirmAndCloseItemDialog(1)

                        useInventoryItem("Reset Whistle")
                        bUsedWhistleToday = true

                        // Re-analyze after shuffle.
                        MessageLog.i(TAG, "[TRACKBLAZER] Re-analyzing trainings after Reset Whistle.")
                        training.analyzeTrainings()
                        trainingSelected = training.recommendTraining(forceSelection = whistleForcesTraining)

                        // Perform another consolidated item usage pass if needed after shuffle.
                        useItems(trainee, trainingSelected)
                    } else {
                        MessageLog.i(TAG, "[TRACKBLAZER] No Reset Whistles found in inventory.")
                        ButtonClose.click(game.imageUtils)
                        game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)
                    }
                }
            } else {
                MessageLog.i(TAG, "[TRACKBLAZER] No suitable training found and no Reset Whistles in cached inventory or all are disabled.")
            }
        }

        // Final Training Execution.
        if (trainingSelected != null) {
            training.executeTraining(trainingSelected)
        } else {
            MessageLog.i(TAG, "[TRACKBLAZER] Still no suitable training found. Backing out to rest/recollect.")
            ButtonBack.click(game.imageUtils)
            game.wait(1.0)
            if (checkMainScreen()) {
                recoverEnergy()
            }
        }
    }

    /**
     * Uses race-related items (Hammers, Glow Sticks) based on the race grade and fan count.
     *
     * @param grade The grade of the detected race.
     * @param fans The number of fans awarded by the race.
     */
    private fun useRaceItems(grade: RaceGrade, fans: Int) {
        if (date.day < 13 || bUsedHammerToday) {
            if (bUsedHammerToday) {
                MessageLog.i(TAG, "[TRACKBLAZER] Already used a race item today.")
            }
            return
        }

        val masterHammerCount = if (disabledItems.contains("Master Cleat Hammer")) 0 else (currentInventory["Master Cleat Hammer"] ?: 0)
        val artisanHammerCount = if (disabledItems.contains("Artisan Cleat Hammer")) 0 else (currentInventory["Artisan Cleat Hammer"] ?: 0)
        val glowSticksCount = if (disabledItems.contains("Glow Sticks")) 0 else (currentInventory["Glow Sticks"] ?: 0)

        val hasMasterHammer = masterHammerCount > 0
        val hasArtisanHammer = artisanHammerCount > 0
        val hasGlowSticks = glowSticksCount > 0

        val hammerToUse =
            if (grade == RaceGrade.G1) {
                if (hasMasterHammer) {
                    "Master Cleat Hammer"
                } else if (hasArtisanHammer) {
                    "Artisan Cleat Hammer"
                } else {
                    null
                }
            } else if (grade == RaceGrade.G2 || grade == RaceGrade.G3) {
                if (hasArtisanHammer) "Artisan Cleat Hammer" else null
            } else {
                null
            }

        val useGlowSticks = grade == RaceGrade.G1 && fans >= 20000 && hasGlowSticks

        logRaceItemUsageReasoning(grade, fans, masterHammerCount, artisanHammerCount, glowSticksCount, hammerToUse, useGlowSticks)

        if (hammerToUse != null || useGlowSticks) {
            MessageLog.i(TAG, "[TRACKBLAZER] Suitable race items found in inventory (Hammer: $hammerToUse, Glow Sticks: $useGlowSticks). Opening Training Items dialog.")
            if (shopList.openTrainingItemsDialog()) {
                var itemsUsedCount = 0

                if (hammerToUse != null) {
                    if (shopList.useSpecificItems(listOf(hammerToUse), reason = "Race bonus for $grade.").isNotEmpty()) {
                        useInventoryItem(hammerToUse)
                        itemsUsedCount++
                    }
                }

                if (useGlowSticks) {
                    if (shopList.useSpecificItems(listOf("Glow Sticks"), reason = "Boosting fan gain for $grade.").isNotEmpty()) {
                        useInventoryItem("Glow Sticks")
                        itemsUsedCount++
                    }
                }

                if (itemsUsedCount > 0) {
                    MessageLog.i(TAG, "[TRACKBLAZER] Queued $itemsUsedCount race items for $grade ($fans fans). Confirming usage.")
                    confirmAndCloseItemDialog(itemsUsedCount)

                    bUsedHammerToday = true
                } else {
                    MessageLog.i(TAG, "[TRACKBLAZER] No suitable race items found for grade $grade.")
                    ButtonClose.click(game.imageUtils)
                    game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)
                }
            }
        } else {
            MessageLog.i(TAG, "[TRACKBLAZER] No relevant race items in cached inventory for $grade.")
        }
    }

    /**
     * Orchestrates the usage of items based on dynamic conditions and updates internal inventory.
     * Consolidates synchronization and item usage into a single pass for efficiency.
     *
     * @param trainee Reference to the trainee's state. If provided, conditional items will be used.
     * @param trainingSelected The stat name of the selected training to help with item usage (e.g. Ankle Weights).
     * @param bQuickUseOnly If true, only items marked for quick use will be used.
     * @param bDryRun If true, only logs intentions without performing any clicks.
     */
    fun manageInventoryItems(trainee: Trainee? = null, trainingSelected: StatName? = null, bQuickUseOnly: Boolean = false, bDryRun: Boolean = false) {
        if (date.day < 13 && !bDryRun) return

        MessageLog.i(TAG, "[TRACKBLAZER] Starting inventory management pass.")
        val initialEnergy = trainee?.energy ?: 0
        val initialMood = trainee?.mood ?: Mood.NORMAL
        val initialMegaphoneTurnCounter = trainee?.megaphoneTurnCounter ?: 0
        val nextInventory = currentInventory.toMutableMap()
        val currentDisabledItems = mutableSetOf<String>()
        val scannedItemsList = mutableListOf<ScannedItem>()
        var itemsUsedCount = 0
        var wasEarlyExit = false

        // To improve efficiency, we identify which items we are actually interested in based on our cached inventory.
        // If we have a cached inventory and have seen all items of interest, we can exit the scroll loop early.
        val remainingItemsOfInterest =
            if (currentInventory.isNotEmpty()) {
                currentInventory
                    .filter { (name, count) ->
                        if (count <= 0) return@filter false

                        val info = shopList.shopItems[name]
                        val isStat = info?.category == "Stats"
                        val isBad = info?.category == "Heal Bad Conditions"
                        val isQuick = info?.isQuickUsage == true
                        val isEnergy = shopList.energyItemNames.contains(name) || name == "Royal Kale Juice"
                        val isMood = name == "Berry Sweet Cupcake" || name == "Plain Cupcake"
                        val isMegaphone = name == "Empowering Megaphone" || name == "Motivating Megaphone" || name == "Coaching Megaphone"
                        val isAnkleWeight = name.contains("Ankle Weights")
                        val isCharm = name == "Good-Luck Charm"

                        // Determine if this item is actually useful right now.
                        val isUseful =
                            isStat ||
                                isBad ||
                                isQuick ||
                                (isEnergy && trainee != null && trainee.energy <= 100) ||
                                // We might want any energy item if not full.
                                (isMood && trainee != null && trainee.mood < Mood.GREAT) ||
                                (isMegaphone && trainee != null && trainingSelected != null && trainee.megaphoneTurnCounter == 0) ||
                                (isAnkleWeight && trainee != null && trainingSelected != null) ||
                                (isCharm && trainee != null && trainingSelected != null)

                        isUseful
                    }.keys
                    .toMutableSet()
            } else {
                mutableSetOf()
            }

        if (remainingItemsOfInterest.isEmpty() && bInventorySynced) {
            MessageLog.i(TAG, "[TRACKBLAZER] No items of interest found in cached inventory and already synced. Skipping scan.")
        } else if (remainingItemsOfInterest.isNotEmpty()) {
            MessageLog.i(TAG, "[TRACKBLAZER] Items of interest for this pass: ${remainingItemsOfInterest.joinToString(", ")}.")
        }

        val itemsUsedWithReasons = mutableListOf<Pair<String, String>>()
        val itemNameMapInManage = mutableMapOf<Int, String>()
        shopList.processItemsWithFallback(
            keyExtractor = { entry ->
                val name = shopList.getShopItemName(entry, ButtonSkillUp.checkDisabled(game.imageUtils, entry.bitmap) == true)
                if (name != null) itemNameMapInManage[entry.index] = name
                name
            },
        ) { entry ->
            val isDisabled = ButtonSkillUp.checkDisabled(game.imageUtils, entry.bitmap) == true
            val itemName = itemNameMapInManage[entry.index] ?: shopList.getShopItemName(entry, isDisabled)

            if (itemName != null) {
                Log.d(TAG, "[DEBUG] buyItems:: Detected item \"$itemName\" (Disabled: $isDisabled) at index ${entry.index}.")
                scannedItemsList.add(ScannedItem(entry, itemName, isDisabled))

                // Track disabled items.
                if (isDisabled) {
                    currentDisabledItems.add(itemName)
                }

                // Sync Inventory.
                val amount = shopList.getItemAmount(entry, isDisabled)
                nextInventory[itemName] = amount

                // Inline usage logic.
                if (!bDryRun) {
                    val isStat = shopList.statItemNames.contains(itemName)
                    val isBad = shopList.badConditionHealItemNames.contains(itemName)
                    val itemInfo = shopList.shopItems[itemName]
                    val isQuick = itemInfo != null && itemInfo.isQuickUsage

                    if (bQuickUseOnly) {
                        if (isQuick && !isDisabled) {
                            if (clickItemPlusButton(itemName, entry, "[TRACKBLAZER] Using quick-use item: \"$itemName\".", nextInventory)) {
                                itemsUsedCount++
                                val reason =
                                    when {
                                        isStat -> "Marked as quick-use."
                                        itemInfo?.category == "Bond" -> "Marked as quick-use."
                                        itemInfo?.category == "Get Good Conditions" -> "Acquired good condition: ${getStatusEffectName(itemName)}."
                                        else -> "Marked as quick-use."
                                    }
                                itemsUsedWithReasons.add(itemName to reason)
                            }
                        }
                    } else {
                        if (isStat && !isDisabled) {
                            var clicks = 0
                            while (true) {
                                val reason = "Marked as quick-use."
                                if (clickItemPlusButton(itemName, entry, "[TRACKBLAZER] Queuing stat item: \"$itemName\".", nextInventory, recheck = clicks > 0, reason = reason)) {
                                    itemsUsedCount++
                                    clicks++
                                    itemsUsedWithReasons.add(itemName to reason)
                                    if (clicks >= 5) break
                                    game.wait(0.2)
                                } else {
                                    break
                                }
                            }
                        } else if (isBad && !isDisabled && trainee?.currentNegativeStatuses?.isNotEmpty() == true) {
                            val reason = "Healed status effect: ${trainee.currentNegativeStatuses.joinToString(", ")}."
                            if (clickItemPlusButton(itemName, entry, "[TRACKBLAZER] Queuing bad condition item: \"$itemName\".", nextInventory, reason = reason)) {
                                itemsUsedCount++
                                itemsUsedWithReasons.add(itemName to reason)
                            }
                        } else if (isQuick && !isDisabled) {
                            val reason =
                                when {
                                    itemInfo?.category == "Bond" -> "Marked as quick-use."
                                    itemInfo?.category == "Get Good Conditions" -> "Acquired status effect: ${getStatusEffectName(itemName)}."
                                    else -> "Marked as quick-use."
                                }
                            if (clickItemPlusButton(itemName, entry, "[TRACKBLAZER] Queuing quick-use item: \"$itemName\".", nextInventory, reason = reason)) {
                                itemsUsedCount++
                                itemsUsedWithReasons.add(itemName to reason)
                            }
                        } else if (trainee != null) {
                            // Handle Energy, Mood, Ankle Weights, Charm, Megaphones, etc.
                            val reason = handleInlineUsage(trainee, itemName, entry, isDisabled, trainingSelected, nextInventory, remainingItemsOfInterest)
                            if (reason != null) {
                                itemsUsedCount++
                                itemsUsedWithReasons.add(itemName to reason)
                            }
                        }
                    }
                }

                if (remainingItemsOfInterest.contains(itemName)) {
                    remainingItemsOfInterest.remove(itemName)
                }
            } else {
                MessageLog.w(TAG, "[WARN] manageInventoryItems:: Failed to detect item name at index ${entry.index}.")
            }

            // Early exit if we've seen all items of interest.
            // We allow early exit even if not synced for the turn yet, provided we had a non-empty cache to start with.
            if (remainingItemsOfInterest.isEmpty() && (bInventorySynced || currentInventory.isNotEmpty())) {
                MessageLog.i(TAG, "[TRACKBLAZER] All items of interest processed. Exiting scan early.")
                wasEarlyExit = true
                true
            } else {
                false
            }
        }

        // Finalize Sync.
        if (!wasEarlyExit) {
            val scannedItemNames = scannedItemsList.map { it.itemName }.toSet()
            nextInventory.keys.forEach { name ->
                if (!scannedItemNames.contains(name) && (nextInventory[name] ?: 0) > 0) {
                    nextInventory[name] = 0
                }
            }
        }
        currentInventory = nextInventory.toMap()
        disabledItems = currentDisabledItems.toSet()
        bInventorySynced = true

        // Print the categorized inventory summary.
        printCurrentInventory()

        // Log reasoning for item usage decisions made during this pass.
        if (trainee != null) {
            logItemUsageReasoning(initialEnergy, initialMood, initialMegaphoneTurnCounter, trainingSelected, itemsUsedWithReasons)
        }

        if (itemsUsedCount > 0 && !bDryRun) {
            confirmAndCloseItemDialog(itemsUsedCount)
        } else if (!bDryRun) {
            if (ButtonClose.click(game.imageUtils, tries = 30)) {
                game.wait(game.dialogWaitDelay)
            }
        }
    }

    /**
     * Map item names to their specific good status effect names.
     *
     * @param itemName The name of the item.
     * @return The status effect name.
     */
    private fun getStatusEffectName(itemName: String): String {
        return when (itemName) {
            "Pretty Mirror" -> "Charming ○"
            "Reporter's Binoculars" -> "Hot Topic"
            "Master Practice Guide" -> "Practice Perfect ○"
            "Scholar's Hat" -> "Fast Learner"
            else -> "null"
        }
    }

    /**
     * Log detailed reasoning for item usage decisions.
     *
     * @param energy The initial energy of the trainee.
     * @param mood The initial mood of the trainee.
     * @param megaphoneTurnCounter The initial megaphone turn counter of the trainee.
     * @param trainingSelected The stat name of the selected training.
     * @param itemsUsedWithReasons The list of names and specific reasons for items that were used.
     */
    private fun logItemUsageReasoning(energy: Int, mood: Mood, megaphoneTurnCounter: Int, trainingSelected: StatName?, itemsUsedWithReasons: List<Pair<String, String>>) {
        val sb = StringBuilder()
        sb.appendLine("\n========== Item Usage Reasoning ==========")
        sb.appendLine("Current State: Energy=$energy%, Mood=$mood, Megaphone Turn=$megaphoneTurnCounter")
        if (trainingSelected != null) {
            val failureChance = training.trainingMap[trainingSelected]?.failureChance ?: 0
            sb.appendLine("Selected Training: $trainingSelected (Fail: $failureChance%)")
        }

        if (itemsUsedWithReasons.isEmpty()) {
            sb.appendLine("No items were used this pass.")
            // Provide context on why common items might have been skipped.
            if (trainee.energy > energyThresholdToUseEnergyItems) {
                sb.appendLine("- Energy is above $energyThresholdToUseEnergyItems% threshold.")
            }
            if (trainee.mood.ordinal > Mood.BAD.ordinal) {
                sb.appendLine("- Mood is acceptable (${trainee.mood}).")
            }
            if (trainee.megaphoneTurnCounter > 0) {
                sb.appendLine("- Megaphone is already active (${trainee.megaphoneTurnCounter} turns left).")
            }
        } else {
            sb.appendLine("Items Used:")
            sb.appendLine("")
            // Group the items used by name and reason and count them.
            val groupedItems = itemsUsedWithReasons.groupBy { it }.mapValues { it.value.size }
            groupedItems.forEach { (pair, count) ->
                val (name, reason) = pair
                sb.appendLine("- ${count}x $name: $reason")
            }
        }
        sb.appendLine("===========================================")
        MessageLog.v(TAG, sb.toString())
    }

    /**
     * Log detailed reasoning for race item usage.
     *
     * @param grade The grade of the detected race.
     * @param fans The number of fans awarded by the race.
     * @param hasMasterHammer Whether the Master Cleat Hammer is in inventory.
     * @param hasArtisanHammer Whether the Artisan Cleat Hammer is in inventory.
     * @param hasGlowSticks Whether Glow Sticks are in inventory.
     * @param hammerToUse The specific hammer chosen for use, if any.
     * @param useGlowSticks Whether Glow Sticks were chosen for use.
     */
    private fun logRaceItemUsageReasoning(grade: RaceGrade, fans: Int, masterHammerCount: Int, artisanHammerCount: Int, glowSticksCount: Int, hammerToUse: String?, useGlowSticks: Boolean) {
        val itemsFound = mutableListOf<String>()
        if (masterHammerCount > 0) itemsFound.add("${masterHammerCount}x Master Cleat Hammer")
        if (artisanHammerCount > 0) itemsFound.add("${artisanHammerCount}x Artisan Cleat Hammer")
        if (glowSticksCount > 0) itemsFound.add("${glowSticksCount}x Glow Sticks")

        val sb = StringBuilder()
        sb.appendLine("\n========== Race Item Usage Reasoning ==========")
        sb.appendLine("Race: $grade ($fans fans)")
        sb.appendLine("Inventory: ${itemsFound.joinToString(", ").ifEmpty { "None" }}")
        sb.appendLine("")

        if (hammerToUse != null) {
            sb.appendLine("- Using a $hammerToUse for $grade.")
        } else {
            if (grade == RaceGrade.G1) {
                sb.appendLine("- No Hammers available for G1 race.")
            } else if (grade == RaceGrade.G2 || grade == RaceGrade.G3) {
                sb.appendLine("- No Artisan Cleat Hammer available for $grade.")
            }
        }

        if (useGlowSticks) {
            sb.appendLine("- Using Glow Sticks: Boosting fan gain for high-fan G1 race.")
        } else if (grade == RaceGrade.G1 && glowSticksCount > 0) {
            sb.appendLine("- Skipping Glow Sticks: Fan count $fans is below 20000 threshold.")
        }

        if (hammerToUse == null && !useGlowSticks) {
            sb.appendLine("- No items will be used.")
        }

        sb.appendLine("===============================================")
        MessageLog.v(TAG, sb.toString())
    }

    /**
     * Handles usage of a specific item discovered during the scan loop.
     *
     * @param trainee Reference to the trainee's state.
     * @param itemName The name of the item detected.
     * @param entry The ScrollListEntry of the item.
     * @param isDisabled Whether the item is disabled in the UI.
     * @param trainingSelected The stat name of the selected training.
     * @param nextInventory The updated inventory map reflecting changes in this pass.
     * @param remainingItemsOfInterest The set of items we are still looking for.
     * @return The specific reason why the item was used, or null if not used.
     */
    private fun handleInlineUsage(
        trainee: Trainee,
        itemName: String,
        entry: ScrollListEntry,
        isDisabled: Boolean,
        trainingSelected: StatName?,
        nextInventory: MutableMap<String, Int>,
        remainingItemsOfInterest: Set<String>,
    ): String? {
        if (isDisabled) return null

        // Ankle Weights Check.
        if (date.day >= 13 && trainingSelected != null) {
            val neededWeight =
                when (trainingSelected) {
                    StatName.SPEED -> "Speed Ankle Weights"
                    StatName.STAMINA -> "Stamina Ankle Weights"
                    StatName.POWER -> "Power Ankle Weights"
                    StatName.GUTS -> "Guts Ankle Weights"
                    else -> ""
                }
            if (itemName == neededWeight) {
                val reason = "Boosting $trainingSelected training gains."
                if (clickItemPlusButton(itemName, entry, "[TRACKBLAZER] Queuing $itemName via inline pass.", nextInventory, reason = reason)) {
                    return reason
                }
            }
        }

        // Good-Luck Charm Check.
        val failureChance = training.trainingMap[trainingSelected]?.failureChance ?: 0
        if (date.day >= 13 && !bUsedCharmToday && failureChance >= 20 && itemName == "Good-Luck Charm") {
            val reason = "Setting training failure chance to 0%."
            if (clickItemPlusButton(itemName, entry, "[TRACKBLAZER] Queuing Good-Luck Charm via inline pass.", nextInventory, reason = reason)) {
                bUsedCharmToday = true
                return reason
            }
        }

        // Energy Items Check (Only one per pass).
        if (trainee.energy <= energyThresholdToUseEnergyItems && shopList.energyItemNames.contains(itemName)) {
            val gainMap = mapOf("Vita 65" to 65, "Vita 40" to 40, "Vita 20" to 20)
            val gain = gainMap[itemName] ?: 0
            if (trainee.energy + gain <= 100) {
                val reason = "Restored energy (current: ${trainee.energy}%) because it fell below the $energyThresholdToUseEnergyItems% threshold."
                if (clickItemPlusButton(itemName, entry, "[TRACKBLAZER] Queuing $itemName for use (Energy: ${trainee.energy}%, Gain: +$gain).", nextInventory, reason = reason)) {
                    trainee.energy += gain
                    return reason
                }
            }
        }

        // Royal Kale Juice Check.
        if (itemName == "Royal Kale Juice") {
            val hasMoodItems = nextInventory.any { (name, count) -> count > 0 && (name == "Berry Sweet Cupcake" || name == "Plain Cupcake") }
            val shouldUse =
                if (trainee.energy <= 20) {
                    true
                } else if (trainee.energy <= energyThresholdToUseEnergyItems) {
                    hasMoodItems || trainee.mood == Mood.AWFUL
                } else {
                    false
                }

            if (shouldUse) {
                val oldEnergy = trainee.energy
                val reason =
                    if (oldEnergy <= 20) {
                        "Restored energy (current: $oldEnergy%) as a last resort (below 20%)."
                    } else {
                        "Restored energy (current: $oldEnergy%) while having mood recovery items available to offset the Mood decrease."
                    }
                if (clickItemPlusButton(itemName, entry, "[TRACKBLAZER] Queuing $itemName for use (Energy: ${trainee.energy}%, Mood: ${trainee.mood}).", nextInventory, reason = reason)) {
                    trainee.energy = (trainee.energy + 100).coerceAtMost(100)
                    trainee.mood = trainee.mood.decrement()
                    return reason
                }
            }
        }

        // Mood Items Check.
        if (trainee.mood.ordinal <= Mood.BAD.ordinal && (itemName == "Berry Sweet Cupcake" || itemName == "Plain Cupcake")) {
            // Very simple inline mood: use the first one seen.
            val reason = "Recovering mood (current: ${trainee.mood})."
            if (clickItemPlusButton(itemName, entry, "[TRACKBLAZER] Queuing $itemName for mood recovery.", nextInventory, reason = reason)) {
                trainee.mood = if (itemName == "Berry Sweet Cupcake") Mood.GOOD else Mood.NORMAL
                return reason
            }
        }

        // Megaphone Check.
        val megaphoneNames = listOf("Empowering Megaphone", "Motivating Megaphone", "Coaching Megaphone")
        if (trainee.megaphoneTurnCounter == 0 && trainingSelected != null && megaphoneNames.contains(itemName)) {
            // Check if there is a better megaphone in inventory that we haven't seen yet OR that we know is disabled.
            val betterMegaphones =
                when (itemName) {
                    "Motivating Megaphone" -> listOf("Empowering Megaphone")
                    "Coaching Megaphone" -> listOf("Empowering Megaphone", "Motivating Megaphone")
                    else -> emptyList()
                }

            // We check both the updated scan result (nextInventory) AND the persistent disabledItems cache.
            // An item is considered available if it's already scanned as enabled OR if it's still in the interest set (meaning we haven't seen its current state yet).
            val hasBetterAvailable =
                betterMegaphones.any { better ->
                    (nextInventory[better] ?: 0) > 0 && (!disabledItems.contains(better) || remainingItemsOfInterest.contains(better))
                }

            if (!hasBetterAvailable) {
                val reason = "Increasing training gains for the next few turns."
                if (clickItemPlusButton(itemName, entry, "[TRACKBLAZER] Queuing best available megaphone: \"$itemName\".", nextInventory, reason = reason)) {
                    trainee.megaphoneTurnCounter =
                        when (itemName) {
                            "Empowering Megaphone" -> 2
                            "Motivating Megaphone" -> 3
                            "Coaching Megaphone" -> 4
                            else -> 0
                        }
                    return reason
                }
            }
        }

        return null
    }

    /**
     * Orchestrates the usage of items based on dynamic conditions and updates internal inventory.
     *
     * @param trainee Reference to the trainee's state.
     * @param trainingSelected The stat name of the selected training to help with item usage (e.g. ankle weights).
     */
    private fun useItems(trainee: Trainee, trainingSelected: StatName? = null) {
        if (date.day < 13) return

        val needSync = !bInventorySynced
        val hasEnergyItems =
            currentInventory.any { (name, count) -> count > 0 && shopList.energyItemNames.contains(name) && !disabledItems.contains(name) } ||
                (
                    (
                        currentInventory["Royal Kale Juice"]
                            ?: 0
                    ) > 0 &&
                        !disabledItems.contains("Royal Kale Juice")
                )
        val hasMoodItems = currentInventory.any { (name, count) -> count > 0 && (name == "Berry Sweet Cupcake" || name == "Plain Cupcake") && !disabledItems.contains(name) }
        val hasBadConditionItems = currentInventory.any { (name, count) -> count > 0 && shopList.badConditionHealItemNames.contains(name) && !disabledItems.contains(name) }
        val hasStatItems = currentInventory.any { (name, count) -> count > 0 && shopList.statItemNames.contains(name) && !disabledItems.contains(name) }

        val hasMegaphones =
            trainingSelected != null &&
                trainee.megaphoneTurnCounter == 0 &&
                currentInventory.any { (name, count) ->
                    count > 0 && (name == "Empowering Megaphone" || name == "Motivating Megaphone" || name == "Coaching Megaphone") && !disabledItems.contains(name)
                }
        val hasAnkleWeights =
            trainingSelected != null &&
                currentInventory.any { (name, count) ->
                    count > 0 &&
                        name ==
                        when (trainingSelected) {
                            StatName.SPEED -> "Speed Ankle Weights"
                            StatName.STAMINA -> "Stamina Ankle Weights"
                            StatName.POWER -> "Power Ankle Weights"
                            StatName.GUTS -> "Guts Ankle Weights"
                            else -> ""
                        } &&
                        !disabledItems.contains(name)
                }
        val failureChance = if (trainingSelected != null) training.trainingMap[trainingSelected]?.failureChance ?: 0 else 0
        val hasCharm = trainingSelected != null && !bUsedCharmToday && failureChance >= 20 && (currentInventory["Good-Luck Charm"] ?: 0) > 0 && !disabledItems.contains("Good-Luck Charm")

        val potentialUse =
            (trainee.energy <= energyThresholdToUseEnergyItems && hasEnergyItems) ||
                (trainee.mood.ordinal <= Mood.BAD.ordinal && hasMoodItems) ||
                (trainee.currentNegativeStatuses.isNotEmpty() && hasBadConditionItems) ||
                hasStatItems ||
                hasMegaphones ||
                hasAnkleWeights ||
                hasCharm

        if (needSync || potentialUse) {
            val reasons = mutableListOf<String>()
            if (needSync) reasons.add("Sync needed")
            if (trainee.energy <= energyThresholdToUseEnergyItems && hasEnergyItems) reasons.add("Low energy")
            if (trainee.mood.ordinal <= Mood.BAD.ordinal && hasMoodItems) reasons.add("Low mood")
            if (trainee.currentNegativeStatuses.isNotEmpty() && hasBadConditionItems) reasons.add("Bad conditions")
            if (hasStatItems) reasons.add("Stat items available")
            if (hasMegaphones) reasons.add("Megaphone available")
            if (hasAnkleWeights) reasons.add("Ankle weights available")
            if (hasCharm) reasons.add("Good-luck charm available")

            MessageLog.i(TAG, "[TRACKBLAZER] Opening Training Items dialog (${reasons.joinToString(", ")})...")
            if (shopList.openTrainingItemsDialog()) {
                manageInventoryItems(trainee, trainingSelected)
            }
        } else {
            MessageLog.i(TAG, "[TRACKBLAZER] Skipping Training Items dialog as no relevant items are in the cached inventory or all relevant items are disabled.")
        }
    }

    /**
     * Prints the current inventory categorized with item amounts.
     */
    fun printCurrentInventory() {
        // Group items by category from the central shopItems mapping.
        val inventoryByCategory =
            currentInventory.filter { it.value > 0 }.keys.groupBy { itemName ->
                shopList.shopItems[itemName]?.category ?: "Other"
            }

        val summary = StringBuilder("\n============== Current Inventory ==============\n")
        var hasItems = false

        // Sort categories to maintain consistent order (Stats first, then others).
        val categoryOrder = listOf("Stats", "Energy and Motivation", "Bond", "Get Good Conditions", "Heal Bad Conditions", "Training Facilities", "Training Effects", "Races")
        val sortedCategories =
            inventoryByCategory.keys.sortedWith(
                compareBy { category ->
                    val index = categoryOrder.indexOf(category)
                    if (index == -1) categoryOrder.size else index
                },
            )

        sortedCategories.forEach { category ->
            val items = inventoryByCategory[category] ?: emptyList()
            if (items.isNotEmpty()) {
                summary.append("\n[$category]\n")
                items.sorted().forEach { name ->
                    summary.append("- $name: ${currentInventory[name]}\n")
                }
                hasItems = true
            }
        }

        if (!hasItems) {
            summary.append("\nInventory is empty.\n")
        }
        summary.append("\n===============================================")
        MessageLog.v(TAG, summary.toString())
    }
}
