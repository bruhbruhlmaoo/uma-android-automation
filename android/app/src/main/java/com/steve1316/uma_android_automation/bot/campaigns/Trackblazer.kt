package com.steve1316.uma_android_automation.bot.campaigns

import android.util.Log
import android.graphics.Bitmap

import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.bot.DialogHandlerResult
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.TextUtils
import com.steve1316.uma_android_automation.utils.ScrollList
import com.steve1316.uma_android_automation.utils.ScrollListEntry
import com.steve1316.uma_android_automation.types.BoundingBox
import com.steve1316.uma_android_automation.types.TrackblazerShopList
import com.steve1316.uma_android_automation.types.DateYear
import com.steve1316.uma_android_automation.types.DateMonth
import com.steve1316.uma_android_automation.types.DatePhase
import com.steve1316.uma_android_automation.components.*
import com.steve1316.uma_android_automation.bot.Racing.RaceData
import com.steve1316.uma_android_automation.types.RaceGrade
import com.steve1316.uma_android_automation.types.StatName
import com.steve1316.uma_android_automation.types.Mood
import com.steve1316.uma_android_automation.types.Trainee
import com.steve1316.uma_android_automation.types.FanCountClass
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

import org.opencv.core.Point

class Trackblazer(game: Game) : Campaign(game) {
	private var tutorialDisabled = false
	private var bIsFinals: Boolean = false

    /** Representation of the item shop list along with the mapping of items to their price and effect. */
	private val shopList: TrackblazerShopList = TrackblazerShopList(game)

    var shopCoins: Int = 0
    var currentInventory: Map<String, Int> = mapOf()

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

    /** Tracks whether we have already updated trainee information this turn. */
    private var bHasUpdatedThisTurn: Boolean = false

    /** Tracks whether the inventory has been synced at least once or needs a re-sync. */
    private var bInventorySynced: Boolean = false

    /**
     * Detects and handles any dialog popups.
     *
     * To prevent the bot moving too fast, we add a 500ms delay to the
     * exit of this function whenever we close the dialog.
     * This gives the dialog time to close since there is a very short
     * animation that plays when a dialog closes.
     *
     * @param dialog An optional dialog to evaluate. This allows chaining
     * dialog handler calls for improved performance.
     *
     * @return A pair of a boolean and a nullable DialogInterface.
     * The boolean is true when a dialog has been handled by this function.
     * The DialogInterface is the detected dialog, or NULL if no dialogs were found.
     */
    override fun handleDialogs(dialog: DialogInterface?, args: Map<String, Any>): DialogHandlerResult {
        val result: DialogHandlerResult = super.handleDialogs(dialog, args)

		// Extract dialog name if result has one.
		val dialogName = when (result) {
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
        val detectedDialog = when (result) {
			is DialogHandlerResult.Handled -> result.dialog
			is DialogHandlerResult.Unhandled -> result.dialog
			is DialogHandlerResult.Deferred -> result.dialog
			else -> DialogUtils.getDialog(game.imageUtils)
		} ?: return DialogHandlerResult.NoDialogDetected

        when (detectedDialog.name) {
            "exchange_complete" -> detectedDialog.ok(game.imageUtils)
            "confirm_use" -> detectedDialog.ok(game.imageUtils)
            "shop" -> {
                // Once it gets to Junior Year Early July, the shop will be unlocked for use.
                // But the date update has not happened yet, so we need to check for the previous date instead.
				if (date.year == DateYear.JUNIOR && date.month == DateMonth.JUNE && date.phase == DatePhase.LATE) {
					MessageLog.i(TAG, "Shop unlocked! Initiating the first time buying process...")
				} else {
					MessageLog.i(TAG, "Shop discount detected! Initiating buying process...")
				}
                
                detectedDialog.ok(game.imageUtils)
                game.wait(game.dialogWaitDelay)
                buyItems()
            }
            "consecutive_race_warning" -> {
                val okButtonLocation: Point? = ButtonOk.find(game.imageUtils).first
                
                if (okButtonLocation != null) {
                    val ocrText = game.imageUtils.performOCRFromReference(
                        okButtonLocation,
                        offsetX = -560,
                        offsetY = -525,
                        width = game.imageUtils.relWidth(690),
                        height = game.imageUtils.relHeight(50),
                        useThreshold = true,
                        useGrayscale = true,
                        scale = 2.0,
                        ocrEngine = "mlkit",
                        debugName = "TrackblazerConsecutiveRaceOCR"
                    )
                    
                    MessageLog.i(TAG, "[TRACKBLAZER] OCR text from consecutive warning: \"$ocrText\"")
                    
                    // Regex: This will put you at ([0-9]+) consecutive races.
                    val match = Regex("""([0-9]+)""").find(ocrText)
                    val ocrCount = match?.groups?.get(1)?.value?.toInt() ?: -1
                    
                    if (ocrCount != -1) {
                        MessageLog.i(TAG, "[TRACKBLAZER] OCR detected a count of $ocrCount consecutive races.")
                        
                        // Discrepancy logic: if (OCR - (actual + 1)) >= 3 then treat it as 1 (error/reset) else trust OCR.
                        val expectedCount = consecutiveRaceCount + 1
                        val diff = Math.abs(ocrCount - expectedCount)
                        
                        if (diff >= 3) {
                            MessageLog.w(TAG, "[TRACKBLAZER] Large discrepancy in consecutive race count (OCR=$ocrCount, Expected=$expectedCount). Ignoring OCR and incrementing internal counter.")
                            consecutiveRaceCount = expectedCount
                        } else {
                            consecutiveRaceCount = ocrCount
                        }
                        counterUpdatedByOCR = true
                    } else {
                        MessageLog.w(TAG, "[TRACKBLAZER] Failed to parse consecutive race count from OCR. Counter will be incremented after race.")
                    }
                } else {
                    MessageLog.e(TAG, "[TRACKBLAZER] Failed to find ButtonOk on consecutive race warning screen. Counter will be incremented after race.")
                }

                MessageLog.i(TAG, "[TRACKBLAZER] Current consecutive race count: $consecutiveRaceCount")

                // Handle the dialog based on the threshold.
                racing.raceRepeatWarningCheck = true

                if (consecutiveRaceCount < 6) {
                    MessageLog.i(TAG, "[TRACKBLAZER] Consecutive race count $consecutiveRaceCount < 6. Continuing with racing...")
                    detectedDialog.ok(game.imageUtils)
                    game.wait(1.0, skipWaitingForLoading = true)
                } else {
                    // At 6 consecutive races and above, we start losing 30 stats in addition to the mood down and injury.
                    MessageLog.i(TAG, "[TRACKBLAZER] Consecutive race count $consecutiveRaceCount >= 6. Aborting racing...")
                    racing.encounteredRacingPopup = false
                    racing.clearRacingRequirementFlags()
                    detectedDialog.close(game.imageUtils)
                }
                return DialogHandlerResult.Handled(detectedDialog)
            }
            else -> {
                Log.w(TAG, "[DIALOG] Unknown dialog \"${detectedDialog.name}\" detected so it will not be handled.")
                return DialogHandlerResult.Unhandled(detectedDialog)
            }
        }

        game.wait(0.5)
        return DialogHandlerResult.Handled(detectedDialog)
    }

	/**
	 * Handles training events specific to the Trackblazer campaign.
	 */
	override fun handleTrainingEvent() {
		MessageLog.i(TAG, "[TRACKBLAZER] Running handleTrainingEvent().")
		if (!tutorialDisabled) {
			tutorialDisabled = if (IconUnityCupTutorialHeader.check(game.imageUtils)) {
				// If the tutorial is detected, select the second option to close it.
				MessageLog.i(TAG, "[TRACKBLAZER] Detected tutorial for Trackblazer. Closing it now...")
				val trainingOptionLocations: ArrayList<Point> = IconTrainingEventHorseshoe.findAll(game.imageUtils)
				if (trainingOptionLocations.size >= 2) {
					game.tap(trainingOptionLocations[1].x, trainingOptionLocations[1].y, IconTrainingEventHorseshoe.template.path)
					true
				} else {
					MessageLog.w(TAG, "[TRACKBLAZER] Could not find training options to dismiss tutorial.")
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

	/**
	 * Handles race events specific to the Trackblazer campaign.
	 *
	 * @param isScheduledRace Whether the race is a scheduled one.
	 * @return True if a race event was handled, false otherwise.
	 */
	override fun handleRaceEvents(isScheduledRace: Boolean): Boolean {
		counterUpdatedByOCR = false
        
		// If it's not a scheduled race, we need to apply Trackblazer-specific filtering.
		if (!isScheduledRace) {
			val sourceBitmap = game.imageUtils.getSourceBitmap()

			// Check if we're at a mandatory race screen first (IconRaceDayRibbon or IconGoalRibbon).
			// If we are, we should treat it as a mandatory race and NOT an extra race.
			if (IconRaceDayRibbon.check(game.imageUtils, sourceBitmap = sourceBitmap) || IconGoalRibbon.check(game.imageUtils, sourceBitmap = sourceBitmap)) {
				MessageLog.i(TAG, "[TRACKBLAZER] Mandatory race ribbon detected. Processing as mandatory race...")
				return super.handleRaceEvents(true)
			}

			MessageLog.i(TAG, "[TRACKBLAZER] Checking for suitable races...")
			// We need to enter the race list to check for predictions and grades.
			// Try both standard Races button and the Race Day variant.
			if (!ButtonRaces.click(game.imageUtils, sourceBitmap = sourceBitmap) && !ButtonRaceDayRace.click(game.imageUtils, sourceBitmap = sourceBitmap)) {
				MessageLog.e(TAG, "[TRACKBLAZER] Failed to click Races button.")
				return false
			}
			game.wait(1.0)
            
			// Handle any consecutive race warning dialogs that might pop up after clicking "Races".
			val dialogResult = handleDialogs(args = mapOf("overrideIgnoreConsecutiveRaceWarning" to true))
			if (dialogResult is DialogHandlerResult.Handled && consecutiveRaceCount >= 6) {
				MessageLog.i(TAG, "[TRACKBLAZER] Consecutive race warning obeyed. Aborting racing.")
				return false
			}
            
			val suitableRaceLocation = racing.findSuitableTrackblazerRace(consecutiveRaceCount)
			if (suitableRaceLocation != null) {
				MessageLog.i(TAG, "[TRACKBLAZER] Found suitable race. Proceeding...")
				game.tap(suitableRaceLocation.x, suitableRaceLocation.y, "SuitableTrackblazerRace", ignoreWaiting = true)
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
		}
		return result
	}

    /**
     * Handles the main screen logic for Trackblazer, prioritizing racing.
     *
     * @return True if an action was performed, false otherwise.
     */
    override fun handleMainScreen(): Boolean {
        if (!checkMainScreen()) {
            return false
        }

        // Update date first.
        val dateChanged = date.update(game.imageUtils, isOnMainScreen = true)
        if (!dateChanged) {
            MessageLog.e(TAG, "Failed to update date on main screen.")
            return false
        }

        // Handle turn-start updates if the date changed or we haven't updated yet this turn.
        if (dateChanged || !bHasUpdatedThisTurn) {
            // Reset daily usage flags.
            bUsedWhistleToday = false
            bUsedCharmToday = false
            bUsedHammerToday = false

            // Update trainee information using parallel processing with shared screenshot.
            val sourceBitmap = game.imageUtils.getSourceBitmap()
            val skillPointsLocation = LabelStatTableHeaderSkillPoints.findImageWithBitmap(game.imageUtils, sourceBitmap = sourceBitmap)
            
            // No need to check for racing requirements if it is the Summer.
            val latch = if (date.isSummer()) CountDownLatch(8) else CountDownLatch(9)
            MessageLog.disableOutput = true
            
            // First update current stats and spin up 5 Threads for each stat here.
            trainee.updateStats(game.imageUtils, sourceBitmap, skillPointsLocation, latch)
            
            // Thread 6: Update skill points.
            Thread {
                try { trainee.updateSkillPoints(game.imageUtils, sourceBitmap, skillPointsLocation) }
                catch (e: Exception) { MessageLog.e(TAG, "Error in updateSkillPoints thread: ${e.stackTraceToString()}") }
                finally { latch.countDown() }
            }.apply { isDaemon = true }.start()
            
            // Thread 7: Update mood.
            Thread {
                try { trainee.updateMood(game.imageUtils, sourceBitmap) }
                catch (e: Exception) { MessageLog.e(TAG, "Error in updateMood thread: ${e.stackTraceToString()}") }
                finally { latch.countDown() }
            }.apply { isDaemon = true }.start()

            if (!date.isSummer()) {
                // Thread 8: Check racing requirements.
                Thread {
                    try { racing.checkRacingRequirements(sourceBitmap) }
                    catch (e: Exception) { MessageLog.e(TAG, "Error in checkRacingRequirements thread: ${e.stackTraceToString()}") }
                    finally { latch.countDown() }
                }.apply { isDaemon = true }.start()
            }

            // Thread 8/9: Update energy.
            Thread {
                try { trainee.updateEnergy(game.imageUtils) }
                catch (e: Exception) { MessageLog.e(TAG, "Error in updateEnergy thread: ${e.stackTraceToString()}") }
                finally { latch.countDown() }
            }.apply { isDaemon = true }.start()
            
            try {
                latch.await(10, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                MessageLog.e(TAG, "Turn start updates timed out.")
            } finally {
                MessageLog.disableOutput = false
            }

            // Log the updates.
            MessageLog.i(TAG, "[TRACKBLAZER] Turn start updates complete for $date.")
            MessageLog.i(TAG, "[TRAINEE] Stats: ${trainee.getStatsString()}, Mood: ${trainee.mood}, Energy: ${trainee.energy}%")
            if (trainee.bHasUpdatedAptitudes) {
                trainee.logDetailedPlayerInfo()
            }

            // Update the fan count class.
            val fanCountClass: FanCountClass? = getFanCountClass(sourceBitmap)
            if (fanCountClass != null) {
                trainee.fanCountClass = fanCountClass
            }

            bHasUpdatedThisTurn = true
        }

        // Flag on whether to race or train.
        val sourceBitmap = game.imageUtils.getSourceBitmap()
        val bIsScheduledRaceDay = LabelScheduledRace.check(game.imageUtils, sourceBitmap = sourceBitmap)
        val bIsMandatoryRaceDay = IconRaceDayRibbon.check(game.imageUtils, sourceBitmap = sourceBitmap)
        var needToRace = bIsMandatoryRaceDay || bIsScheduledRaceDay
        
        // Summer Training: Train during July and August in Classic/Senior.
        if (date.isSummer()) {
            MessageLog.i(TAG, "[TRACKBLAZER] It is Summer. Prioritizing training.")
        } else if (date.bIsFinaleSeason && date.day >= 73) {
            // Finale: Train during the final 3 turns (Qualifier, Semifinal, Finals).
            MessageLog.i(TAG, "[TRACKBLAZER] It is the Finale. Prioritizing training.")
        } else {
            // Otherwise, we want to race if possible.
            if (!needToRace && racing.checkEligibilityToStartExtraRacingProcess()) {
                needToRace = true
            }
        }

        if (needToRace) {
            MessageLog.i(TAG, "[TRACKBLAZER] Decision made to race. Entering race events...")
            if (handleRaceEvents(isScheduledRace = bIsScheduledRaceDay)) {
                // Turn is over if we raced.
                bHasUpdatedThisTurn = false
                if (trainee.megaphoneTurnCounter > 0) {
                    trainee.megaphoneTurnCounter--
                    MessageLog.i(TAG, "[TRACKBLAZER] Megaphone duration reduced after racing. Turns remaining: ${trainee.megaphoneTurnCounter}")
                }
                return true
            } else {
                MessageLog.i(TAG, "[TRACKBLAZER] Race events returned false. Falling back to training...")
                ButtonBack.click(game.imageUtils)
                game.wait(0.5)
            }
        }

        // Fallback to training if not racing.
        MessageLog.i(TAG, "[TRACKBLAZER] Decision made to train.")
        // Turn is over, reset the update flag so we update next time the date changes.
        // Also decrement the megaphone turn counter.
        bHasUpdatedThisTurn = false
        if (trainee.megaphoneTurnCounter > 0) {
            trainee.megaphoneTurnCounter--
            MessageLog.i(TAG, "[TRACKBLAZER] Megaphone duration reduced. Turns remaining: ${trainee.megaphoneTurnCounter}")
        }
        
        return true
    }

	/**
	 * Checks for campaign-specific conditions.
	 *
	 * @return True if a condition was handled, false otherwise.
	 */
	override fun checkCampaignSpecificConditions(): Boolean {
		return false
	}

	/**
	 * Opens the fans dialog.
	 */
	override fun openFansDialog() {
		MessageLog.d(TAG, "Opening fans dialog for Trackblazer...")
		ButtonHomeFansInfo.click(game.imageUtils, region = game.imageUtils.regionBottomHalf, tries = 10)
		bHasTriedCheckingFansToday = true
		game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)
	}

    /**
     * Opens the Shop.
     */
    fun openShop() {
        if (ButtonShopTrackblazer.click(game.imageUtils)) {
            game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)
        } else {
            MessageLog.e(TAG, "Unable to open the Shop due to failing to find its button.")
        }
    }

    /**
     * Reads the Shop Coins amount via OCR and updates our internal count.
     * If it fails to read the Shop Coins, it will return the last known Shop Coins amount.
     *
     * @return The Shop Coins amount.
     */
	fun updateShopCoins(): Int {
        MessageLog.i(TAG, "Updating current amount of Shop Coins...")
		val (trainingItemsButtonLocation, sourceBitmap) = ButtonTrainingItems.find(game.imageUtils)
        if (trainingItemsButtonLocation == null) {
            MessageLog.e(TAG, "Failed to find Training Items button.")
            return shopCoins
        }
		val coinText = game.imageUtils.performOCROnRegion(
			sourceBitmap,
			game.imageUtils.relX(trainingItemsButtonLocation.x, -37),
			game.imageUtils.relY(trainingItemsButtonLocation.y, 84),
			game.imageUtils.relWidth(175),
			game.imageUtils.relHeight(60),
			useThreshold = false,
			useGrayscale = true,
			scale = 1.0,
			ocrEngine = "mlkit",
			debugName = "ShopCoins"
		)

		return try {
			val cleanedText = coinText.replace(Regex("[^0-9]"), "")
			if (cleanedText.isEmpty()) {
				MessageLog.w(TAG, "Parsed empty string for Shop Coins.")
				shopCoins
			} else {
				shopCoins = cleanedText.toInt()
				MessageLog.i(TAG, "We have $shopCoins Shop Coins.")
				shopCoins
			}
		} catch (e: NumberFormatException) {
			MessageLog.e(TAG, "Failed to parse Shop Coins from OCR text: \"$coinText\".")
			shopCoins
		}
	}

	/**
	 * Starts the process to buy items from the shop.
	 *
	 * @param priorityList An ordered list of item names to buy. Defaults to an empty list.
	 */
	fun buyItems(priorityList: List<String> = listOf()) {
		if (priorityList.isEmpty()) {
			MessageLog.i(TAG, "Priority list is empty. No items to buy.")
			return
		}

		// Update current coins via OCR before buying.
		updateShopCoins()

		// Buy items from the shop.
		val itemsBought = shopList.buyItems(priorityList, shopCoins)
		if (itemsBought.isNotEmpty()) {
			// Update internal inventory.
			val nextInventory = currentInventory.toMutableMap()
			itemsBought.forEach { itemName ->
				nextInventory[itemName] = (nextInventory[itemName] ?: 0) + 1
			}
			currentInventory = nextInventory.toMap()

			// Handle "Exchange Complete" dialog.
			if (handleDialogs(DialogExchangeComplete) is DialogHandlerResult.Handled) {
				MessageLog.i(TAG, "Successfully handled \"Exchange Complete\" dialog.")
			}

			// Update internal coins count via OCR after purchase.
			updateShopCoins()

			// Open "Training Items" dialog and use items.
			if (ButtonTrainingItems.click(game.imageUtils)) {
				game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)

				// Use items according to quick use categories.
				shopList.quickUseItems()

				// Handle the "Confirm Use" dialog.
				handleDialogs(DialogConfirmUse)

				// Exit the dialogs.
				if (ButtonClose.click(game.imageUtils)) game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)
				if (ButtonBack.click(game.imageUtils)) game.wait(game.dialogWaitDelay)
			}
		}
    /**
     * Generates a priority list of items to buy based on current state and rules.
     *
     * @return An ordered list of item names.
     */
    private fun getPriorityList(): List<String> {
        val topStats = training.statPrioritization.take(3)
        val priorityList = mutableListOf<String>()

        // 1. Top Tier Priorities (Good-Luck Charms, Hammers, Glow Sticks, Priority heals, Priority Energy/Bond)
        priorityList.add("Good-Luck Charm")
        priorityList.add("Master Cleat Hammer")
        priorityList.add("Artisan Cleat Hammer")
        priorityList.add("Glow Sticks")
        priorityList.add("Royal Kale Juice")
        priorityList.add("Grilled Carrots")
        priorityList.add("Rich Hand Cream")
        priorityList.add("Miracle Cure")

        // 2. Stats (Excluding Notepads)
        val statsOrdered = listOf("Scroll", "Manual")
        val statNamesOrdered = listOf("Speed", "Stamina", "Power", "Guts", "Wit")
        statsOrdered.forEach { type ->
            statNamesOrdered.forEach { name ->
                priorityList.add("$name $type")
            }
        }

        // 3. Energy + Mood
        priorityList.add("Vita 65")
        priorityList.add("Vita 40")
        priorityList.add("Vita 20")
        priorityList.add("Berry Sweet Cupcake")
        priorityList.add("Plain Cupcake")

        // 4. Training Effects (Megaphones and specific Ankle Weights)
        priorityList.add("Empowering Megaphone")
        priorityList.add("Motivating Megaphone")
        topStats.forEach { stat ->
            val ankleWeight = when (stat) {
                StatName.SPEED -> "Speed Ankle Weights"
                StatName.STAMINA -> "Stamina Ankle Weights"
                StatName.POWER -> "Power Ankle Weights"
                StatName.GUTS -> "Guts Ankle Weights"
                else -> null
            }
            if (ankleWeight != null) priorityList.add(ankleWeight)
        }
        priorityList.add("Coaching Megaphone")

        // 5. Heal Bad Conditions (Non-priority ones, limit 1 logic is handled in buyItems())
        priorityList.add("Fluffy Pillow")
        priorityList.add("Pocket Planner")
        priorityList.add("Smart Scale")
        priorityList.add("Aroma Diffuser")
        priorityList.add("Practice Drills DVD")

        // 6. Training Facilities (Top 3 stats only)
        topStats.forEach { stat ->
            val trainingApp = when (stat) {
                StatName.SPEED -> "Speed Training Application"
                StatName.STAMINA -> "Stamina Training Application"
                StatName.POWER -> "Power Training Application"
                StatName.GUTS -> "Guts Training Application"
                StatName.WIT -> "Wit Training Application"
            }
            priorityList.add(trainingApp)
        }

        // 7. Lowest Priority Energy
        priorityList.add("Energy Drink MAX")
        priorityList.add("Energy Drink MAX EX")

        return priorityList
    }

}

