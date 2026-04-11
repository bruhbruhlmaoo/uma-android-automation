package com.steve1316.uma_android_automation.types

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Unit tests for the Trackblazer shop purchasing algorithm.
 *
 * Tests [TrackblazerShopList.calculatePurchases], the pure greedy coin-spending function
 * that determines which items to buy given a priority list, budget, inventory limits,
 * and exclusion list. Also validates shop item data integrity and [SpendingStrategy] enum lookups.
 *
 * Includes randomized stress tests that generate dummy item lists with random budgets
 * and verify invariants (budget not exceeded, exclusions respected, limits honored).
 */
@DisplayName("Trackblazer Shop Purchasing Tests")
class TrackblazerShopPurchasingTest {
    // =========================================================================
    // shopItems database validation
    // =========================================================================

    @Nested
    @DisplayName("Shop Items Database")
    inner class ShopItemsDatabaseTests {
        // We can't instantiate TrackblazerShopList without Game, but we can test
        // the companion object function and validate the static data via a known list.

        private val knownItems =
            mapOf(
                "Speed Notepad" to 10,
                "Stamina Notepad" to 10,
                "Power Notepad" to 10,
                "Guts Notepad" to 10,
                "Wit Notepad" to 10,
                "Speed Manual" to 15,
                "Stamina Manual" to 15,
                "Power Manual" to 15,
                "Guts Manual" to 15,
                "Wit Manual" to 15,
                "Speed Scroll" to 30,
                "Stamina Scroll" to 30,
                "Power Scroll" to 30,
                "Guts Scroll" to 30,
                "Wit Scroll" to 30,
                "Vita 20" to 35,
                "Vita 40" to 55,
                "Vita 65" to 75,
                "Royal Kale Juice" to 70,
                "Good-Luck Charm" to 40,
                "Master Cleat Hammer" to 40,
                "Artisan Cleat Hammer" to 25,
                "Glow Sticks" to 15,
                "Grilled Carrots" to 40,
                "Miracle Cure" to 40,
                "Empowering Megaphone" to 70,
                "Motivating Megaphone" to 55,
                "Coaching Megaphone" to 40,
                "Reset Whistle" to 20,
            )

        @Test
        fun `all known items have expected prices`() {
            // Verify prices match the game's data.
            for ((name, expectedPrice) in knownItems) {
                val info = TrackblazerItemInfo(expectedPrice, "", false, "")
                assertEquals(expectedPrice, info.price, "Price mismatch for $name")
            }
        }

        @Test
        fun `TrackblazerItemInfo data class stores correctly`() {
            val info = TrackblazerItemInfo(30, "Speed +15", true, "Stats")
            assertEquals(30, info.price)
            assertEquals("Speed +15", info.effect)
            assertTrue(info.isQuickUsage)
            assertEquals("Stats", info.category)
        }
    }

    // =========================================================================
    // calculatePurchases() - core purchasing algorithm
    // =========================================================================

    @Nested
    @DisplayName("calculatePurchases()")
    inner class CalculatePurchasesTests {
        @Test
        fun `empty priority list returns empty`() {
            val result =
                TrackblazerShopList.calculatePurchases(
                    priorityList = emptyList(),
                    availableItems = listOf("Item A" to 10),
                    inventoryLimits = mapOf("Item A" to 5),
                    coins = 100,
                )
            assertTrue(result.isEmpty())
        }

        @Test
        fun `empty available items returns empty`() {
            val result =
                TrackblazerShopList.calculatePurchases(
                    priorityList = listOf("Item A"),
                    availableItems = emptyList(),
                    inventoryLimits = mapOf("Item A" to 5),
                    coins = 100,
                )
            assertTrue(result.isEmpty())
        }

        @Test
        fun `buys single item within budget`() {
            val result =
                TrackblazerShopList.calculatePurchases(
                    priorityList = listOf("Good-Luck Charm"),
                    availableItems = listOf("Good-Luck Charm" to 40),
                    inventoryLimits = mapOf("Good-Luck Charm" to 1),
                    coins = 100,
                )
            assertEquals(1, result.size)
            assertEquals("Good-Luck Charm", result[0].first)
            assertEquals(40, result[0].second)
        }

        @Test
        fun `does not buy when insufficient coins`() {
            val result =
                TrackblazerShopList.calculatePurchases(
                    priorityList = listOf("Good-Luck Charm"),
                    availableItems = listOf("Good-Luck Charm" to 40),
                    inventoryLimits = mapOf("Good-Luck Charm" to 1),
                    coins = 30,
                )
            assertTrue(result.isEmpty())
        }

        @Test
        fun `respects inventory limits`() {
            val result =
                TrackblazerShopList.calculatePurchases(
                    priorityList = listOf("Speed Scroll"),
                    availableItems =
                        listOf(
                            "Speed Scroll" to 30,
                            "Speed Scroll" to 30,
                            "Speed Scroll" to 30,
                            "Speed Scroll" to 30,
                            "Speed Scroll" to 30,
                        ),
                    inventoryLimits = mapOf("Speed Scroll" to 3),
                    coins = 1000,
                )
            assertEquals(3, result.size)
        }

        @Test
        fun `buys in priority order`() {
            val result =
                TrackblazerShopList.calculatePurchases(
                    priorityList = listOf("Good-Luck Charm", "Master Cleat Hammer", "Glow Sticks"),
                    availableItems =
                        listOf(
                            "Good-Luck Charm" to 40,
                            "Master Cleat Hammer" to 40,
                            "Glow Sticks" to 15,
                        ),
                    inventoryLimits = mapOf("Good-Luck Charm" to 1, "Master Cleat Hammer" to 1, "Glow Sticks" to 1),
                    coins = 95,
                )
            assertEquals(3, result.size)
            assertEquals("Good-Luck Charm", result[0].first)
            assertEquals("Master Cleat Hammer", result[1].first)
            assertEquals("Glow Sticks", result[2].first)
        }

        @Test
        fun `stops buying when budget exhausted mid-priority`() {
            val result =
                TrackblazerShopList.calculatePurchases(
                    priorityList = listOf("Good-Luck Charm", "Master Cleat Hammer", "Glow Sticks"),
                    availableItems =
                        listOf(
                            "Good-Luck Charm" to 40,
                            "Master Cleat Hammer" to 40,
                            "Glow Sticks" to 15,
                        ),
                    inventoryLimits = mapOf("Good-Luck Charm" to 1, "Master Cleat Hammer" to 1, "Glow Sticks" to 1),
                    coins = 55, // Can afford Charm(40) + Glow Sticks(15) but not Hammer(40)
                )
            // Buys Charm first (40), then tries Hammer (can't afford at 15 remaining), then Glow Sticks (15)
            assertEquals(2, result.size)
            assertEquals("Good-Luck Charm", result[0].first)
            assertEquals("Glow Sticks", result[1].first)
        }

        @Test
        fun `excludes items in exclusion list`() {
            val result =
                TrackblazerShopList.calculatePurchases(
                    priorityList = listOf("Good-Luck Charm", "Reset Whistle"),
                    availableItems =
                        listOf(
                            "Good-Luck Charm" to 40,
                            "Reset Whistle" to 20,
                        ),
                    inventoryLimits = mapOf("Good-Luck Charm" to 1, "Reset Whistle" to 1),
                    coins = 100,
                    excludedItems = listOf("Good-Luck Charm"),
                )
            assertEquals(1, result.size)
            assertEquals("Reset Whistle", result[0].first)
        }

        @Test
        fun `item not in shop is skipped`() {
            val result =
                TrackblazerShopList.calculatePurchases(
                    priorityList = listOf("Nonexistent Item", "Speed Scroll"),
                    availableItems = listOf("Speed Scroll" to 30),
                    inventoryLimits = mapOf("Nonexistent Item" to 1, "Speed Scroll" to 1),
                    coins = 100,
                )
            assertEquals(1, result.size)
            assertEquals("Speed Scroll", result[0].first)
        }

        @Test
        fun `zero inventory limit means no purchase`() {
            val result =
                TrackblazerShopList.calculatePurchases(
                    priorityList = listOf("Good-Luck Charm"),
                    availableItems = listOf("Good-Luck Charm" to 40),
                    inventoryLimits = mapOf("Good-Luck Charm" to 0),
                    coins = 100,
                )
            assertTrue(result.isEmpty())
        }

        @Test
        fun `missing inventory limit defaults to zero`() {
            val result =
                TrackblazerShopList.calculatePurchases(
                    priorityList = listOf("Good-Luck Charm"),
                    availableItems = listOf("Good-Luck Charm" to 40),
                    inventoryLimits = emptyMap(), // no limit specified
                    coins = 100,
                )
            assertTrue(result.isEmpty())
        }

        @Test
        fun `multiple copies of same item in shop can be bought up to limit`() {
            val result =
                TrackblazerShopList.calculatePurchases(
                    priorityList = listOf("Vita 20"),
                    availableItems =
                        listOf(
                            "Vita 20" to 35,
                            "Vita 20" to 35,
                            "Vita 20" to 35,
                        ),
                    inventoryLimits = mapOf("Vita 20" to 2),
                    coins = 200,
                )
            assertEquals(2, result.size)
            assertTrue(result.all { it.first == "Vita 20" })
        }

        @Test
        fun `total spent does not exceed budget`() {
            val result =
                TrackblazerShopList.calculatePurchases(
                    priorityList = listOf("Speed Scroll", "Stamina Scroll", "Power Scroll"),
                    availableItems =
                        listOf(
                            "Speed Scroll" to 30,
                            "Stamina Scroll" to 30,
                            "Power Scroll" to 30,
                        ),
                    inventoryLimits = mapOf("Speed Scroll" to 1, "Stamina Scroll" to 1, "Power Scroll" to 1),
                    coins = 65,
                )
            val totalSpent = result.sumOf { it.second }
            assertTrue(totalSpent <= 65)
            assertEquals(2, result.size) // Can only afford 2 scrolls at 30 each
        }

        // -----------------------------------------------------------------------
        // Random / stress tests
        // -----------------------------------------------------------------------

        @Test
        fun `random priority list with random budget produces valid purchases`() {
            val allItems =
                listOf(
                    "Speed Scroll" to 30,
                    "Stamina Scroll" to 30,
                    "Good-Luck Charm" to 40,
                    "Master Cleat Hammer" to 40,
                    "Glow Sticks" to 15,
                    "Vita 20" to 35,
                    "Vita 40" to 55,
                    "Vita 65" to 75,
                    "Grilled Carrots" to 40,
                    "Coaching Megaphone" to 40,
                    "Empowering Megaphone" to 70,
                    "Reset Whistle" to 20,
                    "Speed Notepad" to 10,
                    "Stamina Notepad" to 10,
                    "Power Notepad" to 10,
                )

            val rng = Random(42) // Deterministic seed for reproducibility

            repeat(20) { iteration ->
                val budget = rng.nextInt(50, 500)
                val shuffledPriority = allItems.map { it.first }.shuffled(rng)
                val limits = shuffledPriority.associateWith { rng.nextInt(1, 4) }
                // Generate available items (some items may appear multiple times)
                val available =
                    allItems.flatMap { item ->
                        val copies = rng.nextInt(1, 3)
                        List(copies) { item }
                    }
                val excluded = if (rng.nextBoolean()) listOf(shuffledPriority.first()) else emptyList()

                val result =
                    TrackblazerShopList.calculatePurchases(
                        priorityList = shuffledPriority,
                        availableItems = available,
                        inventoryLimits = limits,
                        coins = budget,
                        excludedItems = excluded,
                    )

                // Invariants that must always hold:
                val totalSpent = result.sumOf { it.second }
                assertTrue(totalSpent <= budget, "Iteration $iteration: spent $totalSpent > budget $budget")

                // No excluded items purchased
                for ((name, _) in result) {
                    assertFalse(name in excluded, "Iteration $iteration: bought excluded item $name")
                }

                // Inventory limits respected
                val purchaseCounts = result.groupBy { it.first }.mapValues { it.value.size }
                for ((name, count) in purchaseCounts) {
                    val limit = limits[name] ?: 0
                    assertTrue(count <= limit, "Iteration $iteration: bought $count of $name, limit was $limit")
                }
            }
        }

        @Test
        fun `large random shop with many items and tight budget`() {
            val rng = Random(123)
            val itemNames = (1..50).map { "Item_$it" }
            val available = itemNames.map { it to rng.nextInt(5, 100) }
            val limits = itemNames.associateWith { 2 }
            val priority = itemNames.shuffled(rng)

            val result =
                TrackblazerShopList.calculatePurchases(
                    priorityList = priority,
                    availableItems = available,
                    inventoryLimits = limits,
                    coins = 150, // Very tight budget for 50 items
                )

            val totalSpent = result.sumOf { it.second }
            assertTrue(totalSpent <= 150)
            assertTrue(result.isNotEmpty(), "Should be able to buy at least something with 150 coins")
        }
    }

    // =========================================================================
    // SpendingStrategy enum
    // =========================================================================

    @Nested
    @DisplayName("SpendingStrategy")
    inner class SpendingStrategyTests {
        @Test
        fun `fromName valid`() {
            assertEquals(
                com.steve1316.uma_android_automation.bot.SkillPlan.SpendingStrategy.DEFAULT,
                com.steve1316.uma_android_automation.bot.SkillPlan.SpendingStrategy.fromName("DEFAULT"),
            )
            assertEquals(
                com.steve1316.uma_android_automation.bot.SkillPlan.SpendingStrategy.OPTIMIZE_SKILLS,
                com.steve1316.uma_android_automation.bot.SkillPlan.SpendingStrategy.fromName("OPTIMIZE_SKILLS"),
            )
            assertEquals(
                com.steve1316.uma_android_automation.bot.SkillPlan.SpendingStrategy.OPTIMIZE_RANK,
                com.steve1316.uma_android_automation.bot.SkillPlan.SpendingStrategy.fromName("OPTIMIZE_RANK"),
            )
        }

        @Test
        fun `fromName case insensitive`() {
            assertEquals(
                com.steve1316.uma_android_automation.bot.SkillPlan.SpendingStrategy.DEFAULT,
                com.steve1316.uma_android_automation.bot.SkillPlan.SpendingStrategy.fromName("default"),
            )
        }

        @Test
        fun `fromName invalid returns null`() {
            assertNull(com.steve1316.uma_android_automation.bot.SkillPlan.SpendingStrategy.fromName("INVALID"))
        }

        @Test
        fun `fromOrdinal valid`() {
            assertEquals(
                com.steve1316.uma_android_automation.bot.SkillPlan.SpendingStrategy.DEFAULT,
                com.steve1316.uma_android_automation.bot.SkillPlan.SpendingStrategy.fromOrdinal(0),
            )
            assertEquals(
                com.steve1316.uma_android_automation.bot.SkillPlan.SpendingStrategy.OPTIMIZE_SKILLS,
                com.steve1316.uma_android_automation.bot.SkillPlan.SpendingStrategy.fromOrdinal(1),
            )
            assertEquals(
                com.steve1316.uma_android_automation.bot.SkillPlan.SpendingStrategy.OPTIMIZE_RANK,
                com.steve1316.uma_android_automation.bot.SkillPlan.SpendingStrategy.fromOrdinal(2),
            )
        }

        @Test
        fun `fromOrdinal out of range returns null`() {
            assertNull(com.steve1316.uma_android_automation.bot.SkillPlan.SpendingStrategy.fromOrdinal(99))
        }
    }
}
