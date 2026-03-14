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
import com.steve1316.uma_android_automation.components.CheckboxShopItem

import com.steve1316.uma_android_automation.components.ButtonHomeFansInfo
import com.steve1316.uma_android_automation.components.ButtonShopTrackblazer
import com.steve1316.uma_android_automation.components.ButtonTrainingItems
import com.steve1316.uma_android_automation.components.DialogInterface
import com.steve1316.uma_android_automation.components.DialogExchangeComplete
import com.steve1316.uma_android_automation.components.DialogConfirmUse

import org.opencv.core.Point

class Trackblazer(game: Game) : Campaign(game) {
	private var tutorialDisabled = false
	private var bIsFinals: Boolean = false

    /** Representation of the item shop list along with the mapping of items to their price and effect. */
	private val shopList: TrackblazerShopList = TrackblazerShopList(game)

    var shopCoins: Int = 0
    var currentInventory: Map<String, Int> = mapOf()

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
        if (result !is DialogHandlerResult.Unhandled) {
            return result
        }

        when (result.dialog.name) {
            "exchange_complete" -> result.dialog.click(game.imageUtils)
            "confirm_use" -> result.dialog.click(game.imageUtils)
            else -> {
                Log.w(TAG, "[DIALOG] Unknown dialog \"${result.dialog.name}\" detected so it will not be handled.")
                return DialogHandlerResult.Unhandled(result.dialog)
            }
        }

        game.wait(0.5)
        return DialogHandlerResult.Handled(result.dialog)
    }

	/**
	 * Handles training events specific to the Trackblazer campaign.
	 */
	override fun handleTrainingEvent() {
		super.handleTrainingEvent()
	}

	/**
	 * Handles race events specific to the Trackblazer campaign.
	 *
	 * @param isScheduledRace Whether the race is a scheduled one.
	 * @return True if a race event was handled, false otherwise.
	 */
	override fun handleRaceEvents(isScheduledRace: Boolean): Boolean {
		return super.handleRaceEvents(isScheduledRace)
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
	 * Scrolls through the Shop list.
	 */
	fun scrollShop() {
		shopList.scrollShop()
	}
}

