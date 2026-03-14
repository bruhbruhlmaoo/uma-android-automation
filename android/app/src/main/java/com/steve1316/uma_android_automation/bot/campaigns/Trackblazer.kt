package com.steve1316.uma_android_automation.bot.campaigns

import android.util.Log
import android.graphics.Bitmap

import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.bot.DialogHandlerResult
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.utils.MessageLog

import com.steve1316.uma_android_automation.components.ButtonHomeFansInfo
import com.steve1316.uma_android_automation.components.ButtonShopTrackblazer
import com.steve1316.uma_android_automation.components.ButtonTrainingItems
import com.steve1316.uma_android_automation.components.DialogInterface

import org.opencv.core.Point

class Trackblazer(game: Game) : Campaign(game) {
	private var tutorialDisabled = false
	private var bIsFinals: Boolean = false

    /** Mapping of shop items to their price and effect. */
	private val shopItems: Map<String, Pair<Int, String>> = mapOf(
		// Stats.
		"Speed Notepad" to Pair(10, "Speed +3"),
		"Stamina Notepad" to Pair(10, "Stamina +3"),
		"Power Notepad" to Pair(10, "Power +3"),
		"Guts Notepad" to Pair(10, "Guts +3"),
		"Wit Notepad" to Pair(10, "Wisdom +3"),
		"Speed Manual" to Pair(15, "Speed +7"),
		"Stamina Manual" to Pair(15, "Stamina +7"),
		"Power Manual" to Pair(15, "Power +7"),
		"Guts Manual" to Pair(15, "Guts +7"),
		"Wit Manual" to Pair(15, "Wisdom +7"),
		"Speed Scroll" to Pair(30, "Speed +15"),
		"Stamina Scroll" to Pair(30, "Stamina +15"),
		"Power Scroll" to Pair(30, "Power +15"),
		"Guts Scroll" to Pair(30, "Guts +15"),
		"Wit Scroll" to Pair(30, "Wisdom +15"),

		// Energy and Motivation.
		"Vita 20" to Pair(35, "Energy +20"),
		"Vita 40" to Pair(55, "Energy +40"),
		"Vita 65" to Pair(75, "Energy +65"),
		"Royal Kale Juice" to Pair(70, "Energy +100, Motivation -1"),
		"Energy Drink MAX" to Pair(30, "Maximum energy +4, Energy +5"),
		"Energy Drink MAX EX" to Pair(50, "Maximum energy +8"),
		"Plain Cupcake" to Pair(30, "Motivation +1"),
		"Berry Sweet Cupcake" to Pair(55, "Motivation +2"),

		// Bond.
		"Yummy Cat Food" to Pair(10, "Yayoi Akikawa's bond +5"),
		"Grilled Carrots" to Pair(40, "All Support card bonds +5"),

		// Get Good Conditions.
		"Pretty Mirror" to Pair(150, "Get Charming ○ status effect"),
		"Reporter's Binoculars" to Pair(150, "Get Hot Topic status effect"),
		"Master Practice Guide" to Pair(150, "Get Practice Perfect ○ status effect"),
		"Scholar's Hat" to Pair(280, "Get Fast Learner status effect"),

		// Heal Bad Conditions.
		"Fluffy Pillow" to Pair(15, "Heal Night Owl"),
		"Pocket Planner" to Pair(15, "Heal Slacker"),
		"Rich Hand Cream" to Pair(15, "Heal Skin Outbreak"),
		"Smart Scale" to Pair(15, "Heal Slow Metabolism"),
		"Aroma Diffuser" to Pair(15, "Heal Migraine"),
		"Practice Drills DVD" to Pair(15, "Heal Practice Poor"),
		"Miracle Cure" to Pair(40, "Heal all negative status effects"),

		// Training Facilities.
		"Speed Training Application" to Pair(150, "Speed Training Level +1"),
		"Stamina Training Application" to Pair(150, "Stamina Training Level +1"),
		"Power Training Application" to Pair(150, "Power Training Level +1"),
		"Guts Training Application" to Pair(150, "Guts Training Level +1"),
		"Wit Training Application" to Pair(150, "Wisdom Training Level +1"),
		"Reset Whistle" to Pair(20, "Shuffle support card distribution"),

		// Training Effects.
		"Coaching Megaphone" to Pair(40, "Training bonus +20% for 4 turns"),
		"Motivating Megaphone" to Pair(55, "Training bonus +40% for 3 turns"),
		"Empowering Megaphone" to Pair(70, "Training bonus +60% for 2 turns"),
		"Speed Ankle Weights" to Pair(50, "Speed training bonus +50%, Energy consumption +20% (One turn)"),
		"Stamina Ankle Weights" to Pair(50, "Stamina training bonus +50%, Energy consumption +20% (One turn)"),
		"Power Ankle Weights" to Pair(50, "Power training bonus +50%, Energy consumption +20% (One turn)"),
		"Guts Ankle Weights" to Pair(50, "Guts training bonus +50%, Energy consumption +20% (One turn)"),
		"Good-Luck Charm" to Pair(40, "Training failure rate set to 0% (One turn)"),

		// Races.
		"Artisan Cleat Hammer" to Pair(25, "Race bonus +20% (One turn)"),
		"Master Cleat Hammer" to Pair(40, "Race bonus +35% (One turn)"),
		"Glow Sticks" to Pair(15, "Race fan gain +50% (One turn)")
	)

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
        game.wait(0.5)
        return super.handleDialogs(dialog, args)
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
}

