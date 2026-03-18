package com.steve1316.uma_android_automation.bot.campaigns

import android.util.Log
import android.graphics.Bitmap

import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.bot.CampaignBreakpointException
import com.steve1316.uma_android_automation.bot.DialogHandlerResult
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.TextUtils
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.utils.ScrollList
import com.steve1316.uma_android_automation.utils.ScrollListEntry
import com.steve1316.uma_android_automation.types.BoundingBox
import com.steve1316.uma_android_automation.types.TrackblazerShopList
import com.steve1316.uma_android_automation.types.ScannedItem
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

	/** Tracks items that were found to be disabled during the last inventory management pass. */
	private var disabledItems: Set<String> = setOf()

	/** Flag to track when a Rival Race was won to trigger item purchase. */
	private var bWonRivalRace: Boolean = false


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
        val result: DialogHandlerResult = super.handleDialogs(dialog, args + mapOf("dialogNameToDefer" to "consecutive_race_warning"))

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

				// Clear the Rival Race win flag as the shop is already being handled.
				if (bWonRivalRace) {
					MessageLog.i(TAG, "[TRACKBLAZER] Rival Race win fallback: Shop handled via dialog.")
					bWonRivalRace = false
				}

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
                        
                        // Trust OCR as the primary source of truth if it successfully parses a number.
                        consecutiveRaceCount = ocrCount
                        counterUpdatedByOCR = true
                    } else {
                        MessageLog.w(TAG, "[TRACKBLAZER] Failed to parse consecutive race count from OCR. Counter will be incremented after race.")
                    }
                } else {
                    MessageLog.e(TAG, "[TRACKBLAZER] Failed to find ButtonOk on consecutive race warning screen. Counter will be incremented after race.")
                }

				MessageLog.i(TAG, "[TRACKBLAZER] Current consecutive race count: $consecutiveRaceCount")

				// Check if we should ignore the warning based on global settings or arguments.
				val overrideIgnoreConsecutiveRaceWarning = args["overrideIgnoreConsecutiveRaceWarning"] as? Boolean ?: false
				val forceRace = overrideIgnoreConsecutiveRaceWarning || racing.enableForceRacing || racing.ignoreConsecutiveRaceWarning

				// Edge case: if there is only 1 turn left before a mandatory race, we can safely race
				// even if it would be our 6th consecutive race.
				val turnsRemaining = game.imageUtils.determineTurnsRemainingBeforeNextGoal()
				val onlyOneTurnLeft = turnsRemaining == 1

				if (forceRace || consecutiveRaceCount < 6 || onlyOneTurnLeft) {
					when {
						forceRace -> MessageLog.i(TAG, "[TRACKBLAZER] Consecutive race warning! Forced racing enabled. Continuing...")
						onlyOneTurnLeft && consecutiveRaceCount >= 6 -> MessageLog.i(TAG, "[TRACKBLAZER] Consecutive race count $consecutiveRaceCount >= 6, but only 1 turn remains before mandatory race. Racing is safe. Continuing...")
						else -> MessageLog.i(TAG, "[TRACKBLAZER] Consecutive race count $consecutiveRaceCount < 6. Continuing with racing...")
					}
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
			if (dialogResult is DialogHandlerResult.Handled && consecutiveRaceCount >= 6 && game.imageUtils.determineTurnsRemainingBeforeNextGoal() != 1) {
				MessageLog.i(TAG, "[TRACKBLAZER] Consecutive race warning obeyed. Aborting racing.")
				return false
			}
            
			val suitableRaceResult = racing.findSuitableTrackblazerRace(consecutiveRaceCount)
			if (suitableRaceResult != null) {
				val suitableRaceLocation = suitableRaceResult.first
				val raceData = suitableRaceResult.second
				MessageLog.i(TAG, "[TRACKBLAZER] Found suitable race: ${raceData.name} (${raceData.grade}). Processing items...")
                
				// Use race-related items (Hammers, Glow Sticks).
				// Skip OP, Pre-debut, and Maiden races as hammers provide no benefit for those grades.
				if (raceData.grade == RaceGrade.G1 || raceData.grade == RaceGrade.G2 || raceData.grade == RaceGrade.G3) {
					useRaceItems(raceData.grade, raceData.fans)
				} else {
					MessageLog.i(TAG, "[TRACKBLAZER] Non-G1/G2/G3 race detected (${raceData.grade}). Skipping race item usage.")
				}
				
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

		// Buy items if we just won a Rival Race.
		if (bWonRivalRace) {
			MessageLog.i(TAG, "[TRACKBLAZER] Rival Race win detected! Checking Shop for new items...")
			bWonRivalRace = false
			game.wait(0.5)
			if (openShop()) {
				buyItems(bWonRivalRaceWin = true)
			}
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

			// Now check if we need to handle skills before finals.
			if (date.day == 72 && skillPlan.skillPlans["preFinals"]?.bIsEnabled ?: false) {
				ButtonSkills.click(game.imageUtils)
				game.wait(1.0)
				if (!handleSkillListScreen()) {
					MessageLog.w(TAG, "handleMainScreen:: handleSkillList() for Pre-Finals failed.")
				}
			}
		}

		// Check if we need to handle the skill point check this run.
		if (
			!bHasHandledSkillPointCheck &&
			enableSkillPointCheck &&
			trainee.skillPoints >= skillPointsRequired
		) {
			if (skillPlan.skillPlans["skillPointCheck"]?.bIsEnabled ?: false) {
				ButtonSkills.click(game.imageUtils)
				game.wait(1.0)
				if (!handleSkillListScreen("skillPointCheck")) {
					throw InterruptedException("handleMainScreen:: handleSkillList() for Skill Point Check failed. Stopping bot...")
				}
				bHasHandledSkillPointCheck = true
			} else {
				throw CampaignBreakpointException("Bot reached skill point check threshold. Stopping bot...")
			}
		}

		// Check if bot should stop before the finals.
		if (checkFinalsStop()) {
			throw InterruptedException(game.notificationMessage)
		}

		// Check if bot should stop at the user specified date.
		if (checkStopAtDate()) {
			throw InterruptedException(game.notificationMessage)
		}

        // Before taking any action, check for items to use.
        // This handles Stats, Energy, Mood, and Bad Conditions.
        // Training items are only available starting Turn 13 (Junior Year Early July).
        // We would have already bought some items during handleDialog() for the Shop dialog during the transition from Day 12 and Day 13.
        if (date.day >= 13) {
            useItems(trainee)
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
        handleTrackblazerTraining()
        
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

	override fun onRaceWin() {
		MessageLog.i(TAG, "[TRACKBLAZER] Rival Race win detected via post-race popup.")
		bWonRivalRace = true
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
	 *
	 * @param tries The number of scan attempts to perform to find the shop button.
     * @return True if the shop was opened successfully, false otherwise.
     */
    fun openShop(tries: Int = 5): Boolean {
        if (ButtonShopTrackblazer.click(game.imageUtils, tries = tries)) {
            game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)
			return true
        } else {
            MessageLog.e(TAG, "Unable to open the Shop due to failing to find its button.")
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
	 * @param bDryRun If true, only logs intentions without performing any clicks.
	 * @param bWonRivalRaceWin If true, indicates this process was triggered by a Rival Race win.
	 */
	fun buyItems(priorityList: List<String> = listOf(), bDryRun: Boolean = false, bWonRivalRaceWin: Boolean = false) {
		val finalPriorityList = if (priorityList.isEmpty()) getPriorityList() else priorityList

		if (bWonRivalRaceWin) {
			MessageLog.i(TAG, "[TRACKBLAZER] Buying extra items after winning a Rival Race...")
		}
		MessageLog.i(TAG, "Initiating buying process. Current inventory being checked for per-item limit of 5.")

		// Update current coins via OCR before buying.
		updateShopCoins()

		// Buy items from the shop.
		// Rule: Each item is limited to 5 in the inventory.
		// Bad Condition items except Rich Hand Cream and Miracle Cure will be limited to 1 in our current inventory.
		val nonPriorityBadConditions = listOf("Fluffy Pillow", "Pocket Planner", "Smart Scale", "Aroma Diffuser", "Practice Drills DVD")
		val filteredPriorityList = finalPriorityList.filter { itemName ->
			val itemCount = currentInventory[itemName] ?: 0
			if (nonPriorityBadConditions.contains(itemName)) {
				itemCount < 1
			} else {
				itemCount < 5
			}
		}
		
		if (bDryRun) {
			MessageLog.i(TAG, "[TEST] Dry Run: Identified items that would be bought: ${filteredPriorityList.joinToString(", ")}")
			shopList.buyItems(filteredPriorityList, shopCoins, bDryRun = true)
			return
		}
		
		val itemsBought = shopList.buyItems(filteredPriorityList, shopCoins)
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
			if (shopList.openTrainingItemsDialog()) {
				// Use items according to quick use categories and update inventory.
				manageInventoryItems(bQuickUseOnly = true)
			}
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
            MessageLog.v(TAG, "[INVENTORY] Decremented $itemName. Remaining: ${nextInventory[itemName]}")
        }
        currentInventory = nextInventory.toMap()
    }

	/**
	 * Confirms the usage of items and closes the Training Items dialog.
	 */
	private fun confirmAndCloseItemDialog() {
		MessageLog.i(TAG, "Confirming usage of items.")
		ButtonConfirmUse.click(game.imageUtils)
		game.wait(game.dialogWaitDelay)
		ButtonUseTrainingItems.click(game.imageUtils)
		// Lengthy delay here for the animation to finish.
		game.wait(5.0)

		// Finalize by closing the dialog.
		MessageLog.i(TAG, "Closing training items dialog.")
		if (ButtonClose.click(game.imageUtils, tries = 30)) {
			game.wait(game.dialogWaitDelay)
		}
	}

    /**
     * Clicks the plus button for an item in the item list and updates inventory.
     *
     * @param itemName The name of the item.
     * @param entry The ScrollListEntry of the item.
     * @param logMessage The message to log when clicking.
     * @return True if the button was clicked, false otherwise.
     */
    private fun clickItemPlusButton(itemName: String, entry: ScrollListEntry, logMessage: String): Boolean {
        if (ButtonSkillUp.checkDisabled(game.imageUtils, entry.bitmap) == true) return false

        val plusPoint = ButtonSkillUp.findImageWithBitmap(game.imageUtils, entry.bitmap)
        if (plusPoint != null) {
            MessageLog.i(TAG, logMessage)
            game.tap(entry.bbox.x + plusPoint.x, entry.bbox.y + plusPoint.y)
            useInventoryItem(itemName)
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
			MessageLog.e(TAG, "Failed to enter training screen.")
			return
		}
		game.wait(0.5)

		// Initial Training Analysis.
		training.analyzeTrainings()
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
				MessageLog.i(TAG, "[TRACKBLAZER] No suitable training found. Using Reset Whistle...")
				if (shopList.openTrainingItemsDialog()) {
					if (shopList.useSpecificItems(listOf("Reset Whistle")).isNotEmpty()) {
						confirmAndCloseItemDialog()

						useInventoryItem("Reset Whistle")
						bUsedWhistleToday = true
						
						// Re-analyze after shuffle.
						MessageLog.i(TAG, "[TRACKBLAZER] Re-analyzing trainings after Reset Whistle.")
						training.analyzeTrainings()
						trainingSelected = training.recommendTraining()
						
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

		val hasMasterHammer = (currentInventory["Master Cleat Hammer"] ?: 0) > 0 && !disabledItems.contains("Master Cleat Hammer")
		val hasArtisanHammer = (currentInventory["Artisan Cleat Hammer"] ?: 0) > 0 && !disabledItems.contains("Artisan Cleat Hammer")
		val hasGlowSticks = (currentInventory["Glow Sticks"] ?: 0) > 0 && !disabledItems.contains("Glow Sticks")

		val hammerToUse = if (grade == RaceGrade.G1) {
			if (hasMasterHammer) "Master Cleat Hammer" else if (hasArtisanHammer) "Artisan Cleat Hammer" else null
		} else if (grade == RaceGrade.G2 || grade == RaceGrade.G3) {
			if (hasArtisanHammer) "Artisan Cleat Hammer" else null
		} else null

		val useGlowSticks = grade == RaceGrade.G1 && fans >= 20000 && hasGlowSticks
		
		if (hammerToUse != null || useGlowSticks) {
			MessageLog.i(TAG, "[TRACKBLAZER] Suitable race items found in inventory (Hammer: $hammerToUse, Glow Sticks: $useGlowSticks). Opening Training Items dialog...")
			if (shopList.openTrainingItemsDialog()) {
				var anyUsed = false
				
				if (hammerToUse != null) {
					if (shopList.useSpecificItems(listOf(hammerToUse)).isNotEmpty()) {
						useInventoryItem(hammerToUse)
						anyUsed = true
					}
				}

				if (useGlowSticks) {
					if (shopList.useSpecificItems(listOf("Glow Sticks")).isNotEmpty()) {
						useInventoryItem("Glow Sticks")
						anyUsed = true
					}
				}
				
				if (anyUsed) {
					MessageLog.i(TAG, "[TRACKBLAZER] Queued race items for $grade ($fans fans). Confirming usage.")
					confirmAndCloseItemDialog()
					
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

		MessageLog.i(TAG, "[TRACKBLAZER] Starting inventory management pass...")
		val nextInventory = currentInventory.toMutableMap()
		val currentDisabledItems = mutableSetOf<String>()
        val scannedItems = mutableMapOf<String, ScannedItem>()
		var anyUsed = false

		val itemNameMapInManage = mutableMapOf<Int, String>()
		shopList.processItemsWithFallback(
			keyExtractor = { entry -> 
				val name = shopList.getShopItemName(entry, ButtonSkillUp.checkDisabled(game.imageUtils, entry.bitmap) == true)
				if (name != null) itemNameMapInManage[entry.index] = name
				name
			}
		) { entry ->
			val isDisabled = ButtonSkillUp.checkDisabled(game.imageUtils, entry.bitmap) == true
			val itemName = itemNameMapInManage[entry.index] ?: shopList.getShopItemName(entry, isDisabled)
			
			if (itemName != null) {
				MessageLog.d(TAG, "[TRACKBLAZER] Detected item \"$itemName\" (Disabled: $isDisabled) at index ${entry.index}.")
                scannedItems[itemName] = ScannedItem(entry, isDisabled)
				
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
                    val isQuick = shopList.shopItems[itemName]?.third == true

                    if (bQuickUseOnly) {
                        if (isQuick && !isDisabled) {
                            if (clickItemPlusButton(itemName, entry, "Using quick-use item: \"$itemName\".")) {
                                anyUsed = true
                            }
                        }
                    } else {
                        if (isStat && !isDisabled) {
                            var clicks = 0
                            while (true) {
                                if (clickItemPlusButton(itemName, entry, "Queuing stat item: \"$itemName\".")) {
                                    anyUsed = true
                                    clicks++
                                    if (clicks >= 5) break
                                    game.wait(0.2)
                                } else {
                                    break
                                }
                            }
                        } else if (isBad && !isDisabled) {
                            if (clickItemPlusButton(itemName, entry, "Queuing bad condition item: \"$itemName\".")) {
                                anyUsed = true
                            }
                        } else if (isQuick && !isDisabled) {
                            if (clickItemPlusButton(itemName, entry, "Queuing quick-use item: \"$itemName\".")) {
                                anyUsed = true
                            }
                        } else if (trainee != null) {
                            // Handle Energy, Mood, Ankle Weights, Charm, etc.
                            // Megaphones are NOT handled here to ensure the best one is selected after the scan.
                            if (handleInlineUsage(trainee, itemName, entry, isDisabled, trainingSelected)) {
                                anyUsed = true
                            }
                        }
                    }
                }
			} else {
				MessageLog.w(TAG, "[TRACKBLAZER] Failed to detect item name at index ${entry.index}.")
			}
			false
		}

		// Finalize Sync.
		nextInventory.keys.forEach { name ->
			if (!scannedItems.containsKey(name) && (nextInventory[name] ?: 0) > 0) {
				nextInventory[name] = 0
			}
		}
		currentInventory = nextInventory.toMap()
		disabledItems = currentDisabledItems.toSet()
		bInventorySynced = true

        // Log a summary of the inventory.
        val summary = StringBuilder("\nContents of the inventory:\n\n")
        scannedItems.forEach { (name, info) ->
			val count = currentInventory[name] ?: 0
            summary.append("$name: $count")
            if (info.isDisabled) summary.append(" (Disabled)")
            summary.append("\n")
        }
        MessageLog.i(TAG, summary.toString())

		// Perform megaphone usage AFTER the scan to ensure the best one is used if available.
		if (!bQuickUseOnly && !bDryRun && trainee != null && trainingSelected != null && trainee.megaphoneTurnCounter == 0) {
			val megaphoneUsed = shopList.useBestMegaphone()
			if (megaphoneUsed != null) {
				trainee.megaphoneTurnCounter = when (megaphoneUsed) {
					"Empowering Megaphone" -> 2
					"Motivating Megaphone" -> 3
					"Coaching Megaphone" -> 4
					else -> 0
				}
				anyUsed = true
			}
		}

        finalizeManageInventoryItems(anyUsed, bDryRun)
    }

    /**
     * Handles usage of a specific item discovered during the scan loop.
     */
    private fun handleInlineUsage(trainee: Trainee, itemName: String, entry: ScrollListEntry, isDisabled: Boolean, trainingSelected: StatName?): Boolean {
        if (isDisabled) return false
        
        // 1. Megaphone Check. (Legacy: Megaphones are now handled in manageInventoryItems)

        // 2. Ankle Weights Check.
        if (date.day >= 13 && trainingSelected != null) {
            val neededWeight = when (trainingSelected) {
                StatName.SPEED -> "Speed Ankle Weights"
                StatName.STAMINA -> "Stamina Ankle Weights"
                StatName.POWER -> "Power Ankle Weights"
                StatName.GUTS -> "Guts Ankle Weights"
                else -> ""
            }
            if (itemName == neededWeight) {
                if (clickItemPlusButton(itemName, entry, "Queuing $itemName via inline pass.")) {
                    return true
                }
            }
        }

        // 3. Good-Luck Charm Check.
        val failureChance = training.trainingMap[trainingSelected]?.failureChance ?: 0
        if (date.day >= 13 && !bUsedCharmToday && failureChance >= 20 && itemName == "Good-Luck Charm") {
            if (clickItemPlusButton(itemName, entry, "Queuing Good-Luck Charm via inline pass.")) {
                bUsedCharmToday = true
                return true
            }
        }

        // 4. Energy Items Check. (Only one per pass)
        if (trainee.energy <= 20 && shopList.energyItemNames.contains(itemName)) {
            val gainMap = mapOf("Vita 65" to 65, "Vita 40" to 40, "Vita 20" to 20)
            val gain = gainMap[itemName] ?: 0
            if (trainee.energy + gain <= 100) {
                if (clickItemPlusButton(itemName, entry, "Queuing $itemName for use (Energy: ${trainee.energy}%, Gain: +$gain).")) {
                    trainee.energy += gain
                    return true
                }
            }
        }

        // 5. Mood Items Check.
        if (trainee.mood.ordinal <= Mood.BAD.ordinal && (itemName == "Berry Sweet Cupcake" || itemName == "Plain Cupcake")) {
            // Very simple inline mood: use the first one seen.
            if (clickItemPlusButton(itemName, entry, "Queuing $itemName for mood recovery.")) {
                trainee.mood = if (itemName == "Berry Sweet Cupcake") Mood.GOOD else Mood.NORMAL 
                return true
            }
        }

        return false
    }

    private fun finalizeManageInventoryItems(anyUsed: Boolean, bDryRun: Boolean) {
        if (anyUsed && !bDryRun) {
            confirmAndCloseItemDialog()
        } else if (!bDryRun) {
            MessageLog.i(TAG, "No items were suited for use. Closing training items dialog.")
            if (ButtonClose.click(game.imageUtils, tries = 30)) {
                game.wait(game.dialogWaitDelay)
            }
        }
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
		val hasEnergyItems = currentInventory.any { (name, count) -> count > 0 && shopList.energyItemNames.contains(name) && !disabledItems.contains(name) } || ((currentInventory["Royal Kale Juice"] ?: 0) > 0 && !disabledItems.contains("Royal Kale Juice"))
		val hasMoodItems = currentInventory.any { (name, count) -> count > 0 && (name == "Berry Sweet Cupcake" || name == "Plain Cupcake") && !disabledItems.contains(name) }
		val hasBadConditionItems = currentInventory.any { (name, count) -> count > 0 && shopList.badConditionHealItemNames.contains(name) && !disabledItems.contains(name) }
		val hasStatItems = currentInventory.any { (name, count) -> count > 0 && shopList.statItemNames.contains(name) && !disabledItems.contains(name) }
		
		val hasMegaphones = trainingSelected != null && trainee.megaphoneTurnCounter == 0 && currentInventory.any { (name, count) -> count > 0 && (name == "Empowering Megaphone" || name == "Motivating Megaphone" || name == "Coaching Megaphone") && !disabledItems.contains(name) }
		val hasAnkleWeights = trainingSelected != null && currentInventory.any { (name, count) -> 
			count > 0 && name == when (trainingSelected) {
				StatName.SPEED -> "Speed Ankle Weights"
				StatName.STAMINA -> "Stamina Ankle Weights"
				StatName.POWER -> "Power Ankle Weights"
				StatName.GUTS -> "Guts Ankle Weights"
				else -> ""
			} && !disabledItems.contains(name)
		}
		val failureChance = if (trainingSelected != null) training.trainingMap[trainingSelected]?.failureChance ?: 0 else 0
		val hasCharm = trainingSelected != null && !bUsedCharmToday && failureChance >= 20 && (currentInventory["Good-Luck Charm"] ?: 0) > 0 && !disabledItems.contains("Good-Luck Charm")

		val potentialUse = (trainee.energy <= 20 && hasEnergyItems) || 
						   (trainee.mood.ordinal <= Mood.BAD.ordinal && hasMoodItems) ||
						   hasBadConditionItems || hasStatItems || hasMegaphones || hasAnkleWeights || hasCharm

		if (needSync || potentialUse) {
			val reasons = mutableListOf<String>()
			if (needSync) reasons.add("Sync needed")
			if (trainee.energy <= 20 && hasEnergyItems) reasons.add("Low energy")
			if (trainee.mood.ordinal <= Mood.BAD.ordinal && hasMoodItems) reasons.add("Low mood")
			if (hasBadConditionItems) reasons.add("Bad conditions")
			if (hasStatItems) reasons.add("Stat items available")
			if (hasMegaphones) reasons.add("Megaphone available")
			if (hasAnkleWeights) reasons.add("Ankle weights available")
			if (hasCharm) reasons.add("Good-luck charm available")

			MessageLog.i(TAG, "Opening Training Items dialog (${reasons.joinToString(", ")})...")
			if (shopList.openTrainingItemsDialog()) {
				manageInventoryItems(trainee, trainingSelected)
			}
		} else {
			MessageLog.i(TAG, "Skipping Training Items dialog as no relevant items are in the cached inventory or all relevant items are disabled.")
		}
	}

	/**
	 * Start debug tests for Trackblazer here.
	 *
	 * @return True if any tests were run. False otherwise.
	 */
	override fun startTests(): Boolean {
		var bDidAnyTestsRun = super.startTests()

        val fnMap: Map<String, () -> Unit> = mapOf(
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
	 * Debug test for Trackblazer race selection logic.
	 */
	fun startTrackblazerRaceSelectionTest() {
		MessageLog.i(TAG, "\n[TEST] Now beginning Trackblazer race selection test.")

		val sourceBitmap = game.imageUtils.getSourceBitmap()

		// If on Main Screen, navigate to the Race List screen first.
		if (checkMainScreen()) {
			MessageLog.i(TAG, "[TEST] Currently on Main Screen. Navigating to Race List...")
			if (!ButtonRaces.click(game.imageUtils, sourceBitmap = sourceBitmap) && !ButtonRaceDayRace.click(game.imageUtils, sourceBitmap = sourceBitmap)) {
				MessageLog.e(TAG, "[TEST] Failed to click Races button.")
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

			MessageLog.i(TAG, "[TEST] Currently on Race List screen. Calling findSuitableTrackblazerRace...")
			val result = racing.findSuitableTrackblazerRace(consecutiveRaceCount)

			if (result != null) {
				val (point, raceData) = result
				MessageLog.i(TAG, "[TEST] Selection Finalized: ${raceData.name} (${raceData.grade}) at (${point.x}, ${point.y})")
			} else {
				MessageLog.i(TAG, "[TEST] findSuitableTrackblazerRace returned null. No suitable races found.")
			}
		} else {
			MessageLog.e(TAG, "[TEST] Not on Main Screen or Race List screen. Ending test.")
		}
	}

	/**
	 * Debug test for Trackblazer inventory sync logic.
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
				MessageLog.e(TAG, "[TEST] Failed to open Training Items dialog.")
			}
		} else if (ButtonClose.check(game.imageUtils)) {
			// Assume we are already in some dialog, possibly training items.
			MessageLog.i(TAG, "[TEST] Close button detected. Assuming Training Items dialog is open. Calling manageInventoryItems...")
			manageInventoryItems(bQuickUseOnly = true, bDryRun = true)
		} else {
			MessageLog.e(TAG, "[TEST] Not on Main Screen or in a dialog. Ending test.")
		}
	}

	/**
	 * Debug test for Trackblazer buying process logic.
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
			MessageLog.e(TAG, "[TEST] Shop not detected. Ending test.")
		}
	}
}
