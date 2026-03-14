package com.steve1316.uma_android_automation.types

import android.graphics.Bitmap
import android.util.Log
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.TextUtils
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.ButtonConfirm
import com.steve1316.uma_android_automation.components.ButtonExchange
import com.steve1316.uma_android_automation.components.CheckboxDoNotShowAgain
import com.steve1316.uma_android_automation.components.CheckboxShopItem
import com.steve1316.uma_android_automation.components.LabelOnSale
import com.steve1316.uma_android_automation.utils.ScrollList
import com.steve1316.uma_android_automation.utils.ScrollListEntry

/**
 * Handles interaction with the item shop list in the Trackblazer scenario.
 *
 * @param game Reference to the bot's Game instance.
 */
class TrackblazerShopList(private val game: Game) {
	private val TAG: String = "[${MainActivity.loggerTag}]TrackblazerShopList"

	/** Mapping of shop items to their price, effect, and whether they are allowed for quick usage. */
	val shopItems: Map<String, Triple<Int, String, Boolean>> = mapOf(
		// Stats
		"Speed Notepad" to Triple(10, "Speed +3", true),
		"Stamina Notepad" to Triple(10, "Stamina +3", true),
		"Power Notepad" to Triple(10, "Power +3", true),
		"Guts Notepad" to Triple(10, "Guts +3", true),
		"Wit Notepad" to Triple(10, "Wisdom +3", true),
		"Speed Manual" to Triple(15, "Speed +7", true),
		"Stamina Manual" to Triple(15, "Stamina +7", true),
		"Power Manual" to Triple(15, "Power +7", true),
		"Guts Manual" to Triple(15, "Guts +7", true),
		"Wit Manual" to Triple(15, "Wisdom +7", true),
		"Speed Scroll" to Triple(30, "Speed +15", true),
		"Stamina Scroll" to Triple(30, "Stamina +15", true),
		"Power Scroll" to Triple(30, "Power +15", true),
		"Guts Scroll" to Triple(30, "Guts +15", true),
		"Wit Scroll" to Triple(30, "Wisdom +15", true),

		// Energy and Motivation
		"Vita 20" to Triple(35, "Energy +20", false),
		"Vita 40" to Triple(55, "Energy +40", false),
		"Vita 65" to Triple(75, "Energy +65", false),
		"Royal Kale Juice" to Triple(70, "Energy +100, Motivation -1", false),
		"Energy Drink MAX" to Triple(30, "Maximum energy +4, Energy +5", false),
		"Energy Drink MAX EX" to Triple(50, "Maximum energy +8", false),
		"Plain Cupcake" to Triple(30, "Motivation +1", true),
		"Berry Sweet Cupcake" to Triple(55, "Motivation +2", true),

		// Bond
		"Yummy Cat Food" to Triple(10, "Yayoi Akikawa's bond +5", true),
		"Grilled Carrots" to Triple(40, "All Support card bonds +5", true),

		// Get Good Conditions
		"Pretty Mirror" to Triple(150, "Get Charming ○ status effect", true),
		"Reporter's Binoculars" to Triple(150, "Get Hot Topic status effect", true),
		"Master Practice Guide" to Triple(150, "Get Practice Perfect ○ status effect", true),
		"Scholar's Hat" to Triple(280, "Get Fast Learner status effect", true),

		// Heal Bad Conditions
		"Fluffy Pillow" to Triple(15, "Heal Night Owl", true),
		"Pocket Planner" to Triple(15, "Heal Slacker", true),
		"Rich Hand Cream" to Triple(15, "Heal Skin Outbreak", true),
		"Smart Scale" to Triple(15, "Heal Slow Metabolism", true),
		"Aroma Diffuser" to Triple(15, "Heal Migraine", true),
		"Practice Drills DVD" to Triple(15, "Heal Practice Poor", true),
		"Miracle Cure" to Triple(40, "Heal all negative status effects", true),

		// Training Facilities
		"Speed Training Application" to Triple(150, "Speed Training Level +1", true),
		"Stamina Training Application" to Triple(150, "Stamina Training Level +1", true),
		"Power Training Application" to Triple(150, "Power Training Level +1", true),
		"Guts Training Application" to Triple(150, "Guts Training Level +1", true),
		"Wit Training Application" to Triple(150, "Wisdom Training Level +1", true),
		"Reset Whistle" to Triple(20, "Shuffle support card distribution", false),

		// Training Effects
		"Coaching Megaphone" to Triple(40, "Training bonus +20% for 4 turns", false),
		"Motivating Megaphone" to Triple(55, "Training bonus +40% for 3 turns", false),
		"Empowering Megaphone" to Triple(70, "Training bonus +60% for 2 turns", false),
		"Speed Ankle Weights" to Triple(50, "Speed training bonus +50%, Energy consumption +20% (One turn)", false),
		"Stamina Ankle Weights" to Triple(50, "Stamina training bonus +50%, Energy consumption +20% (One turn)", false),
		"Power Ankle Weights" to Triple(50, "Power training bonus +50%, Energy consumption +20% (One turn)", false),
		"Guts Ankle Weights" to Triple(50, "Guts training bonus +50%, Energy consumption +20% (One turn)", false),
		"Good-Luck Charm" to Triple(40, "Training failure rate set to 0% (One turn)", false),

		// Races
		"Artisan Cleat Hammer" to Triple(25, "Race bonus +20% (One turn)", false),
		"Master Cleat Hammer" to Triple(40, "Race bonus +35% (One turn)", false),
		"Glow Sticks" to Triple(15, "Race fan gain +50% (One turn)", false)
	)

	private var isShopOnSale: Boolean = false
	private var hasClickedDoNotShowAgain: Boolean = false

	/**
	 * Scrolls through the Shop list.
	 *
	 * @return True if the scrolling process finished normally, false otherwise.
	 */
	fun scrollShop(): Boolean {
		val list: ScrollList = ScrollList.create(game) ?: return false

		// Check if the shop is on sale only once per scroll process.
		isShopOnSale = LabelOnSale.check(game.imageUtils)
        MessageLog.i(TAG, "Shop is on sale: $isShopOnSale")

		var lastSeenName: String? = null
		return list.process { _, entry: ScrollListEntry ->
			val itemName = getShopItemName(entry.bitmap)
			if (itemName != null) {
				if (itemName == lastSeenName) {
					// Skip duplicate read of the same item. Items in this list are never back to back.
					false
				} else {
					lastSeenName = itemName
					val itemPrice = getShopItemPrice(itemName, entry.bitmap)
					MessageLog.v(TAG, "Detected Shop Item: \"$itemName\" with price $itemPrice at index ${entry.index}.")
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

	/**
	 * Extracts the item price from a cropped Shop entry bitmap, either from the original price from the
	 * mapping or the discounted price via OCR if there is a sale.
	 *
	 * @param itemName The name of the item.
	 * @param bitmap A bitmap of a single cropped Shop entry.
	 * @return The item price if detected, or original price otherwise.
	 */
	fun getShopItemPrice(itemName: String, bitmap: Bitmap): Int {
		val originalPrice = shopItems[itemName]?.first ?: 0

		// Use the flag to handle discounted prices instead of repeated checks.
		if (!isShopOnSale) {
			return originalPrice
		}

		// Find the item's checkbox to use as a reference point.
		val checkboxPoint = CheckboxShopItem.findImageWithBitmap(game.imageUtils, bitmap) ?: return originalPrice

		// The user provided offsets: (915, 1075) for checkbox center -> (510, 1060) for price region.
		// dx = 510 - 915 = -405, dy = 1060 - 1075 = -15. Size 100x60.
		val priceBBox = BoundingBox(
			x = game.imageUtils.relX(checkboxPoint.x, -405),
			y = game.imageUtils.relY(checkboxPoint.y, -15),
			w = game.imageUtils.relWidth(100),
			h = game.imageUtils.relHeight(60)
		)

		val croppedPrice = game.imageUtils.createSafeBitmap(bitmap, priceBBox, "ShopItemPrice") ?: return originalPrice

		val detectedText = game.imageUtils.performOCROnRegion(
			croppedPrice,
			0,
			0,
			croppedPrice.width,
			croppedPrice.height,
			useThreshold = true,
			useGrayscale = true,
			scale = 2.0,
			ocrEngine = "mlkit",
			debugName = "ShopItemPriceOCR"
		)

		if (detectedText.isEmpty()) {
			MessageLog.w(TAG, "Parsed empty string for Shop Item Price.")
			return originalPrice
		}

		// Remove non-numeric characters and parse.
		val cleanedText = detectedText.replace(Regex("[^0-9]"), "")
		return cleanedText.toIntOrNull() ?: originalPrice
	}

	/**
	 * Buys items from the Shop based on a priority list and currency amount.
	 *
	 * @param priorityList An ordered list of item names to buy.
	 * @param currentCoins The current amount of Shop Coins available.
	 * @return True if the buying process finished normally, false otherwise.
	 */
	fun buyItems(priorityList: List<String>, currentCoins: Int): Boolean {
		if (priorityList.isEmpty()) {
			MessageLog.i(TAG, "Priority list is empty. No items to buy.")
			return true
		}

		val list: ScrollList = ScrollList.create(game) ?: return false
		var remainingCoins = currentCoins
		val itemsBought = mutableSetOf<String>()

		list.process { _, entry: ScrollListEntry ->
			val itemName = getShopItemName(entry.bitmap)
			if (itemName != null && priorityList.contains(itemName) && !itemsBought.contains(itemName)) {
				val price = getShopItemPrice(itemName, entry.bitmap)
				if (remainingCoins > price) {
					MessageLog.i(TAG, "Selecting \"$itemName\" for $price coins.")
					game.tap(entry.bbox.centerX.toDouble(), entry.bbox.centerY.toDouble())
					remainingCoins -= price
					itemsBought.add(itemName)
				}
			}

			// Early exit if we've bought all items in the priority list.
			itemsBought.size == priorityList.size
		}

		if (itemsBought.isNotEmpty()) {
			MessageLog.i(TAG, "Confirming purchase of ${itemsBought.size} items.")
			ButtonConfirm.click(game.imageUtils)
            game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)

            // Handle "Do not show again" checkbox if found.
            if (!hasClickedDoNotShowAgain) {
                if (CheckboxDoNotShowAgain.click(game.imageUtils)) {
                    MessageLog.i(TAG, "Successfully clicked \"Do not show again\" checkbox.")
                    hasClickedDoNotShowAgain = true
                    game.wait(0.5, skipWaitingForLoading = true)
                }
            }

            // Final exchange confirmation.
            ButtonExchange.click(game.imageUtils)
            game.wait(game.dialogWaitDelay)
            return true
		}

		return true
	}
}
