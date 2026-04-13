package com.steve1316.uma_android_automation.types

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [Trainee.Companion.Stats], the container for a trainee's five stat values.
 *
 * Covers default initialization, per-stat mutation via [setStat], serialization to
 * [IntArray] and [Map], and string formatting.
 */
@DisplayName("Trainee.Stats Tests")
class TraineeStatsTest {
    @Nested
    @DisplayName("Defaults")
    inner class DefaultTests {
        @Test
        fun `default stats are all -1`() {
            val stats = Trainee.Companion.Stats()
            assertEquals(-1, stats.speed)
            assertEquals(-1, stats.stamina)
            assertEquals(-1, stats.power)
            assertEquals(-1, stats.guts)
            assertEquals(-1, stats.wit)
        }
    }

    @Nested
    @DisplayName("setStat")
    inner class SetStatTests {
        @Test
        fun `setStat updates target stat and leaves others unchanged`() {
            val stats = Trainee.Companion.Stats()
            stats.setStat(StatName.SPEED, 100)
            assertEquals(100, stats.speed)
            assertEquals(-1, stats.stamina)
            assertEquals(-1, stats.power)
            assertEquals(-1, stats.guts)
            assertEquals(-1, stats.wit)
        }
    }

    @Nested
    @DisplayName("toIntArray")
    inner class ToIntArrayTests {
        @Test
        fun `toIntArray returns correct order`() {
            val stats = Trainee.Companion.Stats(speed = 10, stamina = 20, power = 30, guts = 40, wit = 50)
            assertArrayEquals(intArrayOf(10, 20, 30, 40, 50), stats.toIntArray())
        }
    }

    @Nested
    @DisplayName("asMap")
    inner class AsMapTests {
        @Test
        fun `asMap returns all 5 stat names`() {
            val stats = Trainee.Companion.Stats(speed = 100, stamina = 200, power = 300, guts = 400, wit = 500)
            val map = stats.asMap()
            assertEquals(5, map.size)
            assertEquals(100, map[StatName.SPEED])
            assertEquals(200, map[StatName.STAMINA])
            assertEquals(300, map[StatName.POWER])
            assertEquals(400, map[StatName.GUTS])
            assertEquals(500, map[StatName.WIT])
        }
    }

    @Nested
    @DisplayName("toString")
    inner class ToStringTests {
        @Test
        fun `toString formats correctly`() {
            val stats = Trainee.Companion.Stats(speed = 10, stamina = 20, power = 30, guts = 40, wit = 50)
            assertEquals("Spd=10, Sta=20, Pow=30, Gut=40, Wit=50", stats.toString())
        }
    }
}
