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
		super.handleTrainingEvent()
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
			MessageLog.i(TAG, "[TRACKBLAZER] Checking for suitable races...")
			// We need to enter the race list to check for predictions and grades.
			if (!ButtonRaces.click(game.imageUtils)) {
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
	}
}

