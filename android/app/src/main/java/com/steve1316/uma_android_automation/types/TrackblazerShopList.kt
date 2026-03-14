package com.steve1316.uma_android_automation.types

import android.graphics.Bitmap
import android.util.Log
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.TextUtils
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.CheckboxShopItem
import com.steve1316.uma_android_automation.utils.ScrollList
import com.steve1316.uma_android_automation.utils.ScrollListEntry

/**
 * Handles interaction with the item shop list in the Trackblazer scenario.
 *
 * @param game Reference to the bot's Game instance.
 */
class TrackblazerShopList(private val game: Game) {
	private val TAG: String = "[${MainActivity.loggerTag}]TrackblazerShopList"

	/** Mapping of shop items to their price and effect. */
	val shopItems: Map<String, Pair<Int, String>> = mapOf(
		// Stats
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

		// Energy and Motivation
		"Vita 20" to Pair(35, "Energy +20"),
		"Vita 40" to Pair(55, "Energy +40"),
		"Vita 65" to Pair(75, "Energy +65"),
		"Royal Kale Juice" to Pair(70, "Energy +100, Motivation -1"),
		"Energy Drink MAX" to Pair(30, "Maximum energy +4, Energy +5"),
		"Energy Drink MAX EX" to Pair(50, "Maximum energy +8"),
		"Plain Cupcake" to Pair(30, "Motivation +1"),
		"Berry Sweet Cupcake" to Pair(55, "Motivation +2"),

		// Bond
		"Yummy Cat Food" to Pair(10, "Yayoi Akikawa's bond +5"),
		"Grilled Carrots" to Pair(40, "All Support card bonds +5"),

		// Get Good Conditions
		"Pretty Mirror" to Pair(150, "Get Charming ○ status effect"),
		"Reporter's Binoculars" to Pair(150, "Get Hot Topic status effect"),
		"Master Practice Guide" to Pair(150, "Get Practice Perfect ○ status effect"),
		"Scholar's Hat" to Pair(280, "Get Fast Learner status effect"),

		// Heal Bad Conditions
		"Fluffy Pillow" to Pair(15, "Heal Night Owl"),
		"Pocket Planner" to Pair(15, "Heal Slacker"),
		"Rich Hand Cream" to Pair(15, "Heal Skin Outbreak"),
		"Smart Scale" to Pair(15, "Heal Slow Metabolism"),
		"Aroma Diffuser" to Pair(15, "Heal Migraine"),
		"Practice Drills DVD" to Pair(15, "Heal Practice Poor"),
		"Miracle Cure" to Pair(40, "Heal all negative status effects"),

		// Training Facilities
		"Speed Training Application" to Pair(150, "Speed Training Level +1"),
		"Stamina Training Application" to Pair(150, "Stamina Training Level +1"),
		"Power Training Application" to Pair(150, "Power Training Level +1"),
		"Guts Training Application" to Pair(150, "Guts Training Level +1"),
		"Wit Training Application" to Pair(150, "Wisdom Training Level +1"),
		"Reset Whistle" to Pair(20, "Shuffle support card distribution"),

		// Training Effects
		"Coaching Megaphone" to Pair(40, "Training bonus +20% for 4 turns"),
		"Motivating Megaphone" to Pair(55, "Training bonus +40% for 3 turns"),
		"Empowering Megaphone" to Pair(70, "Training bonus +60% for 2 turns"),
		"Speed Ankle Weights" to Pair(50, "Speed training bonus +50%, Energy consumption +20% (One turn)"),
		"Stamina Ankle Weights" to Pair(50, "Stamina training bonus +50%, Energy consumption +20% (One turn)"),
		"Power Ankle Weights" to Pair(50, "Power training bonus +50%, Energy consumption +20% (One turn)"),
		"Guts Ankle Weights" to Pair(50, "Guts training bonus +50%, Energy consumption +20% (One turn)"),
		"Good-Luck Charm" to Pair(40, "Training failure rate set to 0% (One turn)"),

		// Races
		"Artisan Cleat Hammer" to Pair(25, "Race bonus +20% (One turn)"),
		"Master Cleat Hammer" to Pair(40, "Race bonus +35% (One turn)"),
		"Glow Sticks" to Pair(15, "Race fan gain +50% (One turn)")
	)

	/**
	 * Scrolls through the Shop list.
	 *
	 * @return True if the scrolling process finished normally, false otherwise.
	 */
	fun scrollShop(): Boolean {
		val list: ScrollList = ScrollList.create(game) ?: return false

		var lastSeenName: String? = null
		return list.process { _, entry: ScrollListEntry ->
			val itemName = getShopItemName(entry.bitmap)
			if (itemName != null) {
				if (itemName == lastSeenName) {
					// Skip duplicate read of the same item. Items in this list are never back to back.
					false
				} else {
					lastSeenName = itemName
					MessageLog.v(TAG, "Detected Shop Item: \"$itemName\" at index ${entry.index}.")
					false
				}
			} else {
				// Item already purchased or not detected.
				MessageLog.v(TAG, "Shop Item already purchased or not detected at index ${entry.index}.")
				false
			}
		}
	}

	/**
	 * Extracts the item name from a cropped Shop entry bitmap.
	 *
	 * @param bitmap A bitmap of a single cropped Shop entry.
	 * @return The item name if detected, NULL otherwise.
	 */
	fun getShopItemName(bitmap: Bitmap): String? {
		// Find the item's checkbox to use as a reference point.
		val checkboxPoint = CheckboxShopItem.findImageWithBitmap(game.imageUtils, bitmap)
		if (checkboxPoint == null) {
			Log.d(TAG, "Failed to find checkbox for this Shop Item.")
			return null
		}

		val nameBBox = BoundingBox(
			x = game.imageUtils.relX(checkboxPoint.x, -680),
			y = game.imageUtils.relY(checkboxPoint.y, -75),
			w = game.imageUtils.relWidth(525),
			h = game.imageUtils.relHeight(50)
		)

		val croppedName = game.imageUtils.createSafeBitmap(bitmap, nameBBox, "ShopItemName") ?: return null

		val detectedText = game.imageUtils.performOCROnRegion(
			croppedName,
			0,
			0,
			croppedName.width,
			croppedName.height,
			useThreshold = true,
			useGrayscale = true,
			scale = 2.0,
			ocrEngine = "mlkit",
			debugName = "ShopItemNameOCR"
		)

		if (detectedText.isEmpty()) {
			MessageLog.w(TAG, "Parsed empty string for Shop Item Name.")
			return null
		}

		// Perform fuzzy matching against known shop item names.
		return TextUtils.matchStringInList(detectedText, shopItems.keys.toList(), threshold = 0.8) ?: detectedText
	}
}
