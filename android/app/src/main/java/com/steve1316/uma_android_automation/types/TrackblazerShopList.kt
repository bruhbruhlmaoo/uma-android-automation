package com.steve1316.uma_android_automation.types

import android.graphics.Bitmap
import android.util.Log
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.TextUtils
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.*
import com.steve1316.uma_android_automation.utils.ScrollList
import com.steve1316.uma_android_automation.utils.ScrollListEntry
import org.opencv.core.Point
import com.steve1316.uma_android_automation.types.StatName

/** Stores a single scanned item's entry and disabled state. */
data class ScannedItem(val entry: ScrollListEntry, val isDisabled: Boolean)

/**
 * Handles interaction with the item shop list in the Trackblazer scenario.
 *
 * @param game Reference to the bot's Game instance.
 */
class TrackblazerShopList(private val game: Game) {
	private val TAG: String = "[${MainActivity.loggerTag}]TrackblazerShopList"

    val statItemNames = listOf(
        "Speed Notepad", "Stamina Notepad", "Power Notepad", "Guts Notepad", "Wit Notepad",
        "Speed Manual", "Stamina Manual", "Power Manual", "Guts Manual", "Wit Manual",
        "Speed Scroll", "Stamina Scroll", "Power Scroll", "Guts Scroll", "Wit Scroll"
    )

    val energyItemNames = listOf("Vita 65", "Vita 40", "Vita 20")
    
    val badConditionHealItemNames = listOf(
        "Fluffy Pillow", "Pocket Planner", "Rich Hand Cream", "Smart Scale", "Aroma Diffuser", "Practice Drills DVD", "Miracle Cure"
    )

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
		"Energy Drink MAX" to Triple(30, "Maximum energy +4, Energy +5", true),
		"Energy Drink MAX EX" to Triple(50, "Maximum energy +8", true),
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
		val itemNameMap = mutableMapOf<Int, String>()
		return processItemsWithFallback(
			keyExtractor = { entry -> 
				val name = getShopItemName(entry, ButtonSkillUp.checkDisabled(game.imageUtils, entry.bitmap) == true)
				if (name != null) itemNameMap[entry.index] = name
				name
			}
		) { entry ->
			val isDisabled = ButtonSkillUp.checkDisabled(game.imageUtils, entry.bitmap) == true
			val itemName = itemNameMap[entry.index] ?: getShopItemName(entry, isDisabled)
			if (itemName != null) {
				val itemPrice = getShopItemPrice(itemName, entry.bitmap)
				MessageLog.v(TAG, "Detected Shop Item: \"$itemName\" with price $itemPrice at index ${entry.index}.")
			}
			false
		}
	}

	/**
	 * Extracts the item name from a cropped Shop entry bitmap.
	 *
	 * @param bitmap A bitmap of a single cropped Shop entry.
	 * @return The item name if detected, NULL otherwise.
	 */
	fun getShopItemName(entry: ScrollListEntry, isDisabled: Boolean = false): String? {
        val bitmap = entry.bitmap
		// Find the item's checkbox to use as a reference point.
		var refPoint = CheckboxShopItem.findImageWithBitmap(game.imageUtils, bitmap)
		
		if (refPoint == null) {
            if (entry.refX != null && entry.refY != null) {
                refPoint = Point(entry.refX.toDouble(), entry.refY.toDouble())
                if (game.debugMode) MessageLog.d(TAG, "Using preserved reference point for item name detection: $refPoint")
            } else {
                // Fallback: Try to find the plus button if the checkbox isn't there (e.g., in the inventory dialog).
                refPoint = ButtonSkillUp.findImageWithBitmap(game.imageUtils, bitmap)
                if (refPoint != null && game.debugMode) {
                    MessageLog.d(TAG, "Using plus button as reference for item name detection.")
                }
            }
		}

		if (refPoint == null) {
			if (game.debugMode) MessageLog.d(TAG, "Failed to find any reference point (checkbox or plus button) for this Item.")
			return null
		}

		val nameBBox = BoundingBox(
			x = game.imageUtils.relX(refPoint.x, -850).coerceAtLeast(0),
			y = game.imageUtils.relY(refPoint.y, -75).coerceAtLeast(0),
			w = game.imageUtils.relWidth(750),
			h = game.imageUtils.relHeight(60)
		)

		val croppedName = game.imageUtils.createSafeBitmap(bitmap, nameBBox, "ShopItemName")
        if (croppedName == null) {
            if (game.debugMode) MessageLog.d(TAG, "Failed to crop name region for reference point at $refPoint.")
            return null
        }

		var detectedText = ""
        
        if (!isDisabled) {
            detectedText = game.imageUtils.performOCROnRegion(
                croppedName,
                0,
                0,
                croppedName.width,
                croppedName.height,
                useThreshold = true,
                useGrayscale = true,
                scale = 2.0,
                ocrEngine = "mlkit",
                debugName = "ShopItemNameOCR_Threshold"
            )
        }

        if (detectedText.isEmpty()) {
            if (game.debugMode && !isDisabled) MessageLog.d(TAG, "Threshold OCR failed for $refPoint, trying without thresholding...")
            detectedText = game.imageUtils.performOCROnRegion(
                croppedName,
                0,
                0,
                croppedName.width,
                croppedName.height,
                useThreshold = false,
                useGrayscale = true,
                scale = 2.0,
                ocrEngine = "mlkit",
                debugName = "ShopItemNameOCR_NoThreshold"
            )
        }

		if (detectedText.isEmpty()) {
			if (game.debugMode) MessageLog.w(TAG, "Parsed empty string for Shop Item Name at $refPoint after both OCR passes.")
			return null
		}

		// Perform fuzzy matching against known shop item names.
		val matchedName = TextUtils.matchStringInList(detectedText, shopItems.keys.toList(), threshold = 0.8)
        if (matchedName == null) {
            if (game.debugMode) MessageLog.d(TAG, "Failed to match text \"$detectedText\" to any known item.")
        }
        return matchedName ?: detectedText
	}

    /**
     * Extracts the item amount from a cropped Shop entry bitmap.
     *
     * @param entry The ScrollListEntry of the item.
     * @param isDisabled Whether the item's plus button is disabled.
     * @return The detected amount of the item on-hand.
     */
    fun getItemAmount(entry: ScrollListEntry, isDisabled: Boolean): Int {
        val bitmap = entry.bitmap
        // Use the plus button as a reference point for the amount detection.
        val refPoint = ButtonSkillUp.findImageWithBitmap(game.imageUtils, bitmap) ?: return 1
        
        val amountText = game.imageUtils.performOCROnRegion(
            bitmap,
            game.imageUtils.relX(refPoint.x, -585),
            game.imageUtils.relY(refPoint.y, -15),
            game.imageUtils.relWidth(55),
            game.imageUtils.relHeight(50),
            useThreshold = true,
            useGrayscale = true,
            scale = 2.0,
            ocrEngine = "mlkit",
            debugName = "ItemAmountOCR"
        )
        
        return try {
            val cleanedText = amountText.replace(Regex("[^0-9]"), "")
            if (cleanedText.isEmpty()) {
                1
            } else {
                cleanedText.toInt().coerceIn(0, 5)
            }
        } catch (e: NumberFormatException) {
            1
        }
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
	 * @param bDryRun If true, only logs intentions without performing any clicks.
	 * @return A list of successfully purchased items.
	 */
	fun buyItems(priorityList: List<String>, currentCoins: Int, bDryRun: Boolean = false): List<String> {
		if (priorityList.isEmpty()) {
			MessageLog.i(TAG, "Priority list is empty. No items to buy.")
			return emptyList()
		}

		// Step 1: Pre-scan Phase.
		// Scan the entire shop to log each item and its price, and to identify what is available.
		MessageLog.d(TAG, "[SHOP] Beginning process of scanning shop items...")
		val availableInShop = mutableMapOf<String, Int>()
		val itemNameMap = mutableMapOf<Int, String>()
		processItemsWithFallback(
			keyExtractor = { entry -> 
				val name = getShopItemName(entry, ButtonSkillUp.checkDisabled(game.imageUtils, entry.bitmap) == true)
				if (name != null) itemNameMap[entry.index] = name
				name
			}
		) { entry ->
			val isDisabled = ButtonSkillUp.checkDisabled(game.imageUtils, entry.bitmap) == true
			val itemName = itemNameMap[entry.index] ?: getShopItemName(entry, isDisabled)
			if (itemName != null) {
				val price = getShopItemPrice(itemName, entry.bitmap)
				MessageLog.d(TAG, "\t$itemName: $price coins")
				availableInShop[itemName] = price
			}
			false
		}

		// Step 2: Calculation & Summary.
		// Determine which items from the priority list are available and affordable.
		val itemsToBuy = mutableListOf<String>()
		var remainingCoinsAfterProposed = currentCoins
		for (item in priorityList) {
			val price = availableInShop[item]
			if (price != null && remainingCoinsAfterProposed >= price && item !in itemsToBuy) {
				itemsToBuy.add(item)
				remainingCoinsAfterProposed -= price
			}
		}
		
		// Log the summary of proposed purchases.
        MessageLog.i(TAG, "============== Items To Buy ==============")
        if (itemsToBuy.isEmpty()) {
            MessageLog.i(TAG, "[SHOP] No items from priority list are available or affordable. Aborting...")
            // Exit early if not a dry run.
            if (!bDryRun) {
                MessageLog.i(TAG, "==========================================")
                return emptyList()
            }
        }

        for (name in itemsToBuy) {
            MessageLog.i(TAG, "\t$name: ${availableInShop[name]} coins")
        }
        val totalCost = currentCoins - remainingCoinsAfterProposed
        MessageLog.i(TAG, "\n\tTOTAL: $totalCost / $currentCoins coins with $remainingCoinsAfterProposed left over coins")
        MessageLog.i(TAG, "==========================================")

		if (bDryRun) {
            // Return early for the test.
			return itemsToBuy
		}

        // Step 3: Purchasing Phase.
		// Re-process the list to click on the selected items.
		val itemsBought = mutableSetOf<String>()
		val itemNameMapInPurchase = mutableMapOf<Int, String>()
		processItemsWithFallback(
			keyExtractor = { entry -> 
				val name = getShopItemName(entry, ButtonSkillUp.checkDisabled(game.imageUtils, entry.bitmap) == true)
				if (name != null) itemNameMapInPurchase[entry.index] = name
				name
			}
		) { entry ->
			val isDisabled = ButtonSkillUp.checkDisabled(game.imageUtils, entry.bitmap) == true
			val itemName = itemNameMapInPurchase[entry.index] ?: getShopItemName(entry, isDisabled)
			if (itemName != null && itemName in itemsToBuy && itemName !in itemsBought) {
				MessageLog.i(TAG, "Selecting \"$itemName\" for ${availableInShop[itemName]} coins.")
				game.tap(entry.bbox.cx.toDouble(), entry.bbox.cy.toDouble())
				itemsBought.add(itemName)
			}
			// Early exit if we've bought all items in the proposed list.
			itemsBought.size == itemsToBuy.size
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
			return itemsBought.toList()
		}

		return emptyList()
	}

	/**
	 * Iterates through the bought items in the inventory and uses them immediately if they belong to
	 * targeted categories and are eligible for use.
	 */
	fun quickUseItems() {
		MessageLog.i(TAG, "Determining if any items can be used right away.")
		var anyUsed = false

		val itemNameMapInQuickUse = mutableMapOf<Int, String>()
		processItemsWithFallback(
			keyExtractor = { entry -> 
				val name = getShopItemName(entry, ButtonSkillUp.checkDisabled(game.imageUtils, entry.bitmap) == true)
				if (name != null) itemNameMapInQuickUse[entry.index] = name
				name
			}
		) { entry ->
			val isDisabled = ButtonSkillUp.checkDisabled(game.imageUtils, entry.bitmap) == true
			val itemName = itemNameMapInQuickUse[entry.index] ?: getShopItemName(entry, isDisabled)
			if (itemName != null && shopItems[itemName]?.third == true) {
				// Check if the item's "+" button is disabled.
				if (!isDisabled) {
					val plusButtonPoint = ButtonSkillUp.findImageWithBitmap(game.imageUtils, entry.bitmap)
					if (plusButtonPoint != null) {
						MessageLog.i(TAG, "Using item: \"$itemName\".")
						game.tap(entry.bbox.x + plusButtonPoint.x, entry.bbox.y + plusButtonPoint.y)
						anyUsed = true
					}
				}
			}
			false
		}

		if (anyUsed) {
			ButtonConfirmUse.click(game.imageUtils)
		} else {
			ButtonClose.click(game.imageUtils)
		}
	}

    /**
     * Uses specific items by their names and returns the list of names for items that were successfully used.
     *
     * @param itemNames The names of the items to use.
     * @param bUseAll If true, attempts to use all items in the list. If false, stops after the first successful usage.
     * @param scannedItems Optional map of pre-scanned items to use instead of performing a new scan.
     * @return A list of names of the items used.
     */
    fun useSpecificItems(itemNames: List<String>, bUseAll: Boolean = false, scannedItems: Map<String, ScannedItem>? = null): List<String> {
        val usedItems = mutableListOf<String>()
        if (itemNames.isEmpty()) return usedItems

        if (scannedItems != null) {
            MessageLog.d(TAG, "[TRACKBLAZER] Using pre-scanned items for specific item usage.")
            for (name in itemNames) {
                if (usedItems.contains(name)) continue
                
                val info = scannedItems[name]
                if (info != null && !info.isDisabled) {
                    val plusButtonPoint = ButtonSkillUp.findImageWithBitmap(game.imageUtils, info.entry.bitmap)
                    if (plusButtonPoint != null) {
                        MessageLog.i(TAG, "Queuing specific item for use: \"$name\" (from pre-scanned).")
                        game.tap(info.entry.bbox.x + plusButtonPoint.x, info.entry.bbox.y + plusButtonPoint.y)
                        usedItems.add(name)
                        
                        if (!bUseAll) return usedItems
                    }
                }
            }
            return usedItems
        }

		val itemNameMapInUseSpecific = mutableMapOf<Int, String>()
        processItemsWithFallback(
			keyExtractor = { entry -> 
				val name = getShopItemName(entry, ButtonSkillUp.checkDisabled(game.imageUtils, entry.bitmap) == true)
				if (name != null) itemNameMapInUseSpecific[entry.index] = name
				name
			}
		) { entry ->
            val isDisabled = ButtonSkillUp.checkDisabled(game.imageUtils, entry.bitmap) == true
            val name = itemNameMapInUseSpecific[entry.index] ?: getShopItemName(entry, isDisabled)
            if (name != null && itemNames.contains(name) && !usedItems.contains(name)) {
                // Check if the item's "+" button is disabled.
                if (!isDisabled) {
                    val plusButtonPoint = ButtonSkillUp.findImageWithBitmap(game.imageUtils, entry.bitmap)
                    if (plusButtonPoint != null) {
                        MessageLog.i(TAG, "Queuing specific item for use: \"$name\".")
                        game.tap(entry.bbox.x + plusButtonPoint.x, entry.bbox.y + plusButtonPoint.y)
                        usedItems.add(name)
                        
                        // If we only wanted to use one item, we are done.
                        if (!bUseAll) return@processItemsWithFallback true
                    }
                }
            }
            false
        }

        return usedItems
    }

    /**
     * Finds and uses the highest value Megaphone available in the inventory.
     *
     * @param scannedItems Optional map of pre-scanned items.
     * @return The item name of the megaphone used, or NULL if none were used.
     */
    fun useBestMegaphone(scannedItems: Map<String, ScannedItem>? = null): String? {
        val megaphonePriority = listOf("Empowering Megaphone", "Motivating Megaphone", "Coaching Megaphone")
        val used = useSpecificItems(megaphonePriority, bUseAll = false, scannedItems = scannedItems)
        return used.firstOrNull()
    }

    /**
     * Uses the Ankle Weights corresponding to the specified stat.
     *
     * @param stat The stat to use ankle weights for.
     * @param scannedItems Optional map of pre-scanned items.
     * @return True if ankle weights were queued.
     */
    fun useAnkleWeights(stat: StatName, scannedItems: Map<String, ScannedItem>? = null): Boolean {
        val itemName = when (stat) {
            StatName.SPEED -> "Speed Ankle Weights"
            StatName.STAMINA -> "Stamina Ankle Weights"
            StatName.POWER -> "Power Ankle Weights"
            StatName.GUTS -> "Guts Ankle Weights"
            else -> return false
        }
        return useSpecificItems(listOf(itemName), scannedItems = scannedItems).isNotEmpty()
    }

    /**
     * Opens the Training Items dialog from the current screen.
     */
    fun openTrainingItemsDialog(): Boolean {
        if (ButtonTrainingItems.click(game.imageUtils)) {
            game.wait(game.dialogWaitDelay, skipWaitingForLoading = true)
            return true
        }
        MessageLog.e(TAG, "Failed to open Training Items dialog.")
        return false
    }

    /**
     * Extracts item entries from a non-scrollable list using template matching for plus buttons.
     *
     * @param sourceBitmap The source bitmap to scan.
     * @return A list of pseudo-ScrollListEntry objects.
     */
    private fun getEntriesNonScrollable(sourceBitmap: Bitmap): List<ScrollListEntry> {
        MessageLog.d(TAG, "[TRACKBLAZER] Scanning full screen for plus buttons...")
        val plusButtons = ButtonSkillUp.findAll(game.imageUtils, sourceBitmap = sourceBitmap)
        MessageLog.d(TAG, "[TRACKBLAZER] Found ${plusButtons.size} plus buttons.")
        plusButtons.sortBy { it.y }

        return plusButtons.mapIndexed { index, point ->
            val entryHeight = game.imageUtils.relHeight(220)
            val entryY = (point.y - (entryHeight / 2)).toInt().coerceIn(0, sourceBitmap.height - entryHeight)
            val entryBBox = BoundingBox(x = 0, y = entryY, w = sourceBitmap.width, h = entryHeight)
            val entryBitmap = game.imageUtils.createSafeBitmap(sourceBitmap, entryBBox, "PseudoEntry_$index")

            ScrollListEntry(
                index = index,
                bitmap = entryBitmap ?: sourceBitmap,
                bbox = entryBBox,
                refX = point.x.toInt(),
                refY = (point.y - entryY).toInt()
            )
        }
    }

    /**
     * Uses the Good-Luck Charm if available.
     *
     * @param scannedItems Optional map of pre-scanned items.
     * @return True if the charm was queued.
     */
    fun useGoodLuckCharm(scannedItems: Map<String, ScannedItem>? = null): Boolean {
        return useSpecificItems(listOf("Good-Luck Charm"), scannedItems = scannedItems).isNotEmpty()
    }

    /**
     * Processes items in a dialog, handling both scrollable and non-scrollable cases.
     *
     * @param keyExtractor Optional callback to extract a unique key for each entry.
     * @param callback The callback to execute for each entry. Return true to stop.
     * @return True if the process completed successfully.
     */
    fun processItemsWithFallback(keyExtractor: ((ScrollListEntry) -> String?)? = null, callback: (ScrollListEntry) -> Boolean): Boolean {
        val sourceBitmap = game.imageUtils.getSourceBitmap()

        // Step 1: Attempt ScrollList detection first.
        MessageLog.d(TAG, "[TRACKBLAZER] Attempting ScrollList detection...")

        val list = if (ButtonConfirmUse.check(game.imageUtils, sourceBitmap = sourceBitmap)) {
            // Training Items dialog detected.
            ScrollList.create(
                game,
                bitmap = sourceBitmap,
                listTopLeftComponent = IconDialogScrollListTopLeft,
                listBottomRightComponent = IconDialogScrollListBottomRight
            )
        } else {
            ScrollList.create(game, bitmap = sourceBitmap)
        }

        if (list != null) {
            // Always check if the shop is on sale.
            isShopOnSale = LabelOnSale.check(game.imageUtils, sourceBitmap = sourceBitmap)

            val result = list.process(keyExtractor = keyExtractor) { _, entry -> callback(entry) }
            
            // If ScrollList processing was successful (it found the list and potentially scrolled), we are done.
            if (result) return true
        }

        // Step 2: Fallback to non-scrollable case if ScrollList failed or found nothing.
        // This is a safety measure for small dialogs where ScrollList corner detection might fail
        // but plus buttons are clearly visible.
        MessageLog.d(TAG, "[TRACKBLAZER] ScrollList detection failed or empty. Falling back to non-scrollable entry detection...")
        val nonScrollableEntries = getEntriesNonScrollable(sourceBitmap)
        if (nonScrollableEntries.isNotEmpty()) {
            MessageLog.d(TAG, "[TRACKBLAZER] Using non-scrollable entry detection.")
            // Maintain localized processedKeys for the non-scrollable fallback if needed.
            val processedKeys = mutableSetOf<String>()

            for (entry in nonScrollableEntries) {
                if (keyExtractor != null) {
                    val key = keyExtractor(entry)
                    if (key != null) {
                        if (processedKeys.contains(key)) {
                            continue
                        }
                        processedKeys.add(key)
                    }
                }

                val found = callback(entry)
                if (!found && game.debugMode) {
                    MessageLog.d(TAG, "[TRACKBLAZER] No item detected in non-scrollable entry at index ${entry.index}.")
                }
                if (found) break
            }
            return true
        }

        MessageLog.e(TAG, "[TRACKBLAZER] Failed to detect shop list or any items.")
        return false
    }
}
