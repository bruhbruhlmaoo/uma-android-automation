package com.steve1316.uma_android_automation.types

import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.components.ButtonSkillUp
import com.steve1316.uma_android_automation.types.Aptitude
import com.steve1316.uma_android_automation.types.BoundingBox
import com.steve1316.uma_android_automation.types.RunningStyle
import com.steve1316.uma_android_automation.types.SkillData
import com.steve1316.uma_android_automation.types.SkillType
import com.steve1316.uma_android_automation.types.TrackDistance
import com.steve1316.uma_android_automation.types.TrackSurface
import org.opencv.core.Point
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Represents a single skill entry in a [SkillList].
 *
 * This class acts as a doubly linked list node, with references to both its direct upgrade and downgraded versions.
 *
 * @param game Reference to the bot's [Game] instance.
 * @param campaign Reference to the current [Campaign] instance.
 * @param skillData The [SkillData] instance containing static skill information.
 * @param bIsObtained Whether this entry has been purchased.
 * @param bIsVirtual Whether this entry is considered a virtual entry in the skill list. Virtual entries are in-place upgrades to skills that currently exist in the list. Since these in-place upgrades
 *    do not appear in the list until all previous versions have been purchased, we consider them to be "virtual" entries.
 * @param prev A pointer to this skill's downgrade [SkillListEntry].
 * @param next A pointer to this skill's upgrade [SkillListEntry].
 */
class SkillListEntry(
    private val game: Game,
    private val campaign: Campaign,
    val skillData: SkillData,
    var bIsObtained: Boolean = false,
    var bIsVirtual: Boolean = false,
    // Pointers for linked-list style navigation.
    var prev: SkillListEntry? = null,
    var next: SkillListEntry? = null,
) {
    companion object {
        private val TAG: String = "[${MainActivity.loggerTag}]SkillListEntry"

        /** Maps Trainee aptitudes to multipliers for evaluation point calculations. */
        private val EVALUATION_POINT_APTITUDE_RATIO_MAP: Map<Aptitude, Double> =
            mapOf(
                Aptitude.S to 1.1,
                Aptitude.A to 1.1,
                Aptitude.B to 0.9,
                Aptitude.C to 0.9,
                Aptitude.D to 0.8,
                Aptitude.E to 0.8,
                Aptitude.F to 0.8,
                Aptitude.G to 0.7,
            )

        /** The set of valid discount percentages (0% to 40%) used in the game. */
        private val DISCOUNT_VALUES: List<Double> =
            listOf(
                0.0,
                0.1,
                0.2,
                0.3,
                0.35,
                0.4,
            )
    }

    /** The skill name (from [skillData]). */
    val name: String = skillData.name

    /** The absolute lowest price this skill can reach in the game (at the maximum 40% discount). */
    private val minScreenPrice: Int = (skillData.cost * (1.0 - DISCOUNT_VALUES.last())).roundToInt()

    /**
     * The maximum possible price displayed on the screen for this skill.
     *
     * For "in-place" skills (like status boosts), this is just the base cost. For "multi-entry" skills (like gold skills), the price includes all unpurchased prerequisites. Since lower-tier versions
     * are never more expensive than their upgrades, doubling the base cost provides a safe boundary for OCR validation.
     */
    private val maxScreenPrice: Int = if (skillData.bIsInPlace) skillData.cost else skillData.cost * 2

    /**
     * The current price of the skill as it is shown in the game.
     *
     * NOTE: updateScreenPrice() must be called after the upgrade chain is fully linked.
     */
    var screenPrice: Int = maxScreenPrice

    /**
     * A snapshot of the screen price as it was originally detected by OCR.
     *
     * We use this as a consistent baseline when calculating price reductions as related skills are purchased, preventing compounding errors.
     */
    private var originalScreenPrice: Int = screenPrice

    /** The actual price of just this skill, ignoring previous version prices. */
    val price: Int
        get() = calculatePrice()

    /** [price] but without any discounts applied. */
    val rawPrice: Int
        get() = ceil(price.toDouble() / (1.0 - discount)).toInt()

    /** The current discount percentage / 100. */
    val discount: Double
        get() = calculateDiscount()

    /** The amount of rank gained upon purchasing this skill. */
    val evaluationPoints: Int
        get() = calculateEvaluationPoints()

    /** The ratio of rank to [price]. */
    val evaluationPointRatio: Double
        get() = calculateEvaluationPointRatio()

    /** Whether this skill is available for purchase. */
    val bIsAvailable: Boolean
        get() = !bIsObtained && !bIsVirtual

    /** Whether this skill is a unique skill inherited from a legacy Uma Musume. */
    val bIsInheritedUnique: Boolean = skillData.bIsInheritedUnique

    /** Whether this is a negative (purple icon) skill. */
    val bIsNegative: Boolean = skillData.bIsNegative

    /** Whether this skill can be upgraded in-place. */
    val bIsInPlace: Boolean = skillData.bIsInPlace

    /**
     * The [RunningStyle] associated with this skill.
     *
     * If no [RunningStyle] applies, then this value will be null.
     */
    val runningStyle: RunningStyle? = skillData.runningStyle

    /**
     * The [TrackDistance] associated with this skill.
     *
     * If no [TrackDistance] applies, then this value will be null.
     */
    val trackDistance: TrackDistance? = skillData.trackDistance

    /**
     * The [TrackSurface] associated with this skill.
     *
     * If no [TrackSurface] applies, then this value will be null.
     */
    val trackSurface: TrackSurface? = skillData.trackSurface

    /**
     * The inferred [RunningStyle] types associated with this skill.
     *
     * Can be empty if none apply.
     */
    val inferredRunningStyles: List<RunningStyle> = skillData.inferredRunningStyles

    /** The community ranking of this skill where lower values are better. */
    val communityTier: Int? = skillData.communityTier

    /** The base price of this skill without any discounts applied from [SkillData]. */
    val baseCost: Int = skillData.cost

    init {
        // Update linked list pointers if they were passed in.
        val prev: SkillListEntry? = prev
        if (prev != null) {
            prev.next = this
        }

        val next: SkillListEntry? = next
        if (next != null) {
            next.prev = this
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Rounds a discount value to the nearest predetermined valid value.
     *
     * Until hint level 3, discount value increases by 10%. After this point, value only increases by 5% up to a max of 40%.
     *
     * @return The rounded discount value.
     */
    private fun Double.roundDiscount(): Double {
        return DISCOUNT_VALUES.minBy { abs(it - this) }
    }

    /**
     * Calculates the discount amount based on the current price.
     *
     * @return The discount as a float between 0.0 and 1.0. The discount is rounded to one of the valid discount amounts. See: [Double.roundDiscount]
     */
    private fun calculateDiscount(): Double {
        if (screenPrice <= 0) {
            return 0.0
        }

        val res: Double = 1.0 - (price.toDouble() / skillData.cost.toDouble())
        return res.roundDiscount()
    }

    /**
     * Calculates the effective price of this specific skill level in the upgrade chain.
     *
     * For skills that are "separate entries" in the list (not in-place), the price we see on the screen includes the cost of all previous unpurchased versions. To get the "standalone" price of just
     * this upgrade, we subtract the cost of its immediate predecessor.
     *
     * @return The calculated standalone price.
     */
    private fun calculatePrice(): Int {
        val prev: SkillListEntry = prev ?: return screenPrice

        // If this skill and its downgrade are separate entries in the list, we must
        // calculate our specific cost by subtracting the predecessor's price.
        // This ensures that `calculateDiscount` reflects only our specific hint level.
        if (!bIsInPlace && !prev.bIsObtained && !prev.bIsVirtual) {
            val adjustedPrice: Int = (screenPrice - prev.price).coerceIn(0, 500)
            return adjustedPrice
        }

        return screenPrice
    }

    /**
     * Gets the evaluation point modifier for any running style requirements.
     *
     * Activation conditions for skills often require the uma to be in a specific [RunningStyle].
     *
     * @return If this skill's activation conditions require a specific [RunningStyle], then we return the evaluation point modifier for that style. If there are no such conditions, then we return
     *    null.
     */
    private fun getRunningStyleAptitudeEvaluationModifier(): Double? {
        val runningStyle: RunningStyle = runningStyle ?: return null
        val aptitude: Aptitude = campaign.trainee.checkRunningStyleAptitude(runningStyle)
        return EVALUATION_POINT_APTITUDE_RATIO_MAP[aptitude]
    }

    /**
     * Gets the evaluation point modifier for any track distance requirements.
     *
     * Activation conditions for skills often require the track to be of a specific [TrackDistance].
     *
     * @return If this skill's activation conditions require a specific [TrackDistance], then we return the evaluation point modifier for that distance. If there are no such conditions, then we return
     *    null.
     */
    private fun getTrackDistanceAptitudeEvaluationModifier(): Double? {
        val trackDistance: TrackDistance = trackDistance ?: return null
        val aptitude: Aptitude = campaign.trainee.checkTrackDistanceAptitude(trackDistance)
        return EVALUATION_POINT_APTITUDE_RATIO_MAP[aptitude]
    }

    /**
     * Gets the evaluation point modifier for any track surface requirements.
     *
     * Activation conditions for skills often require the track to be of a specific [TrackSurface].
     *
     * @return If this skill's activation conditions require a specific [TrackSurface], then we return the evaluation point modifier for that surface. If there are no such conditions, then we return
     *    null.
     */
    private fun getTrackSurfaceAptitudeEvaluationModifier(): Double? {
        val trackSurface: TrackSurface = trackSurface ?: return null
        val aptitude: Aptitude = campaign.trainee.checkTrackSurfaceAptitude(trackSurface)
        return EVALUATION_POINT_APTITUDE_RATIO_MAP[aptitude]
    }

    /**
     * Calculates the total evaluation points (rank gain) awarded for this skill.
     *
     * This function accounts for whether previous versions have been purchased. If a prerequisite version is still available in the list (not yet bought), its evaluation points are added to this
     * one's total. It also applies the [Trainee]'s current aptitude modifiers (running style, distance, or surface) if the skill has specific activation conditions.
     *
     * @return The total rank points gained upon purchase.
     */
    private fun calculateEvaluationPoints(): Int {
        var res: Int = skillData.evalPt

        val prev: SkillListEntry? = prev
        if (prev != null && prev.bIsAvailable) {
            res += prev.evaluationPoints
        }

        // Apply an aptitude-based multiplier if the skill relies on the Trainee's performance
        // in a specific style, distance, or surface.
        val modifier: Double = getRunningStyleAptitudeEvaluationModifier() ?: getTrackDistanceAptitudeEvaluationModifier() ?: getTrackSurfaceAptitudeEvaluationModifier() ?: 1.0

        return (res * modifier).roundToInt()
    }

    /**
     * Calculates the ratio of evaluation points to the price of this skill.
     *
     * @return The ratio of evaluation points to price.
     */
    private fun calculateEvaluationPointRatio(): Double {
        return evaluationPoints.toDouble() / screenPrice.toDouble()
    }

    /**
     * Clamps a value to a valid range for the screen price of this skill.
     *
     * The [screenPrice] of a skill can only ever be within a specific range of values based on the discounts and purchased downgrade versions.
     *
     * @param value The value to clamp.
     * @return The clamped value.
     */
    private fun clampValueForScreenPrice(value: Int): Int {
        if (value in minScreenPrice..maxScreenPrice) {
            return value
        }

        val prev: SkillListEntry? = prev
        if (bIsInPlace || prev == null) {
            return skillData.cost
        }

        return if (prev.bIsObtained) skillData.cost else skillData.cost * 2
    }

    /**
     * Updates the displayed price and baseline reference for this skill.
     *
     * If this is an "in-place" skill (where purchasing version 1 reveals version 2), this method also synchronizes the price of the next version in the chain to ensure its discount stays consistent
     * with this one.
     *
     * @param value The new screen price detected or calculated.
     */
    fun updateScreenPrice(value: Int) {
        val clampedValue: Int = clampValueForScreenPrice(value)
        screenPrice = clampedValue
        originalScreenPrice = clampedValue

        // Synchronize the price of the next version if this is an in-place upgrade chain.
        if (bIsInPlace) {
            val next: SkillListEntry = next ?: return
            val nextBaseCost: Int = next.baseCost
            next.updateScreenPrice(((1.0 - discount) * nextBaseCost.toDouble()).roundToInt())
        }
    }

    /**
     * Handler for when a downgraded version of this skill has been purchased.
     *
     * For skills that are in-place upgradable:
     * - When these skills are purchased, the next level of the skill replaces the current version in the skill list. This entry's [bIsVirtual] is set to false as it now physically exists in the UI
     *   list. We also synchronize the price using the previous version's discount.
     *
     * For skills that are not in-place upgradable:
     * - When these skills are purchased, any upgraded versions of the skill that exist separately in the list will have their [screenPrice] reduced by the price of the purchased skill, as they now
     *   only cost the "top-up" amount.
     *
     * @param entry The downgraded [SkillListEntry] that was just bought.
     */
    fun onDowngradePurchased(entry: SkillListEntry) {
        if (bIsInPlace) {
            // For in-place upgrades (like status boosts), buying the previous version
            // makes this version physically appear in the list.
            bIsVirtual = false
            val prev: SkillListEntry? = prev
            if (prev != null) {
                // Keep our price in sync with the predecessor's discount.
                screenPrice = (skillData.cost.toDouble() * (1.0 - prev.discount)).roundToInt()
            }
        } else {
            // For separate entries (like gold skills), buying the base skill reduces
            // the gold skill's total price since we only need to pay the "top-up" difference.
            screenPrice = (originalScreenPrice - entry.price).coerceIn(0, 500)

            // Continue propagating the state change forward through the chain.
            val next: SkillListEntry? = next
            next?.onDowngradePurchased(this)
        }
    }

    /**
     * Handles the automatic purchase of lower versions when an upgrade is bought.
     *
     * @param entry The upgraded [SkillListEntry] that was purchased.
     */
    fun onUpgradePurchased(entry: SkillListEntry) {
        // Buying an upgrade automatically completes all lower versions in the chain.
        bIsObtained = true

        // For in-place chains, the upgraded version replaces this one in the list,
        // so this specific version becomes "virtual" (no longer physically present).
        if (bIsInPlace) {
            bIsVirtual = true
        }

        // Continue propagating the state change backward through the chain.
        val prev: SkillListEntry? = prev
        prev?.onUpgradePurchased(this)
    }

    /**
     * Propagates the effects of selling a predecessor (reverting state) through the chain.
     *
     * NOTE: This is for internal state management only, as skills cannot be sold back to the game.
     *
     * @param entry The downgraded [SkillListEntry] that was "sold".
     */
    fun onDowngradeSold(entry: SkillListEntry) {
        // In-place versions cannot be reverted individually; they rely on the predecessor.
        if (bIsInPlace) {
            return
        }

        // Mark as no longer bought or physically present in the list.
        bIsVirtual = false
        bIsObtained = false

        // Restore our original baseline price.
        screenPrice = originalScreenPrice

        // Propagate the state change forward through the chain.
        val next: SkillListEntry? = next
        next?.onDowngradeSold(this)
    }

    /**
     * Returns the lowest available "real" version of this skill.
     *
     * This is useful when the bot wants a specific upgrade (like ◎) but it isn't in the list yet. This method finds the most basic version currently available (like ×) so the bot can buy it and start
     * the upgrade chain.
     *
     * @return The first non-virtual [SkillListEntry] in the downgrade chain, or null if none.
     */
    fun getFirstAvailableDowngrade(): SkillListEntry? {
        var entry: SkillListEntry? = prev
        // Traverse backwards until we find a version that physically exists in the UI.
        while (entry != null) {
            if (entry.bIsAvailable) {
                return entry
            }
            entry = entry.prev
        }

        return null
    }

    /**
     * Returns an ordered list of all prerequisite versions for this skill.
     *
     * @return A list of [SkillListEntry] objects from the lowest version up to but excluding this one.
     */
    fun getDowngrades(): List<SkillListEntry> {
        val result: MutableList<SkillListEntry> = mutableListOf()
        var entry: SkillListEntry? = this
        while (entry != null) {
            // Exclude this specific instance from the results.
            if (entry != this) {
                result.add(entry)
            }
            entry = entry.prev
        }
        // Reverse the list so it flows from the lowest version to highest.
        return result.reversed().toList()
    }

    /**
     * Returns a list of all of this skill's downgraded entry names.
     *
     * @return The ordered list of this skill's downgraded version names.
     */
    fun getDowngradeNames(): List<String> {
        return getDowngrades().map { it.name }
    }

    /**
     * Returns a subset of downgrades starting from a specific version up to this one.
     *
     * This acts as a linked list "slicing" function.
     *
     * @param name The name of the [SkillListEntry] where the slice should begin.
     * @return A list of entries starting from the matched name up to this instance. Returns an empty list if no match is found.
     */
    fun getDowngradesUntil(name: String): List<SkillListEntry> {
        val result: MutableList<SkillListEntry> = mutableListOf(this)

        if (this.name == name) {
            return result.toList()
        }

        val downgrades: List<SkillListEntry> = getDowngrades()
        // Iterate backwards from the highest current downgrade back to the start.
        for (entry in downgrades.reversed()) {
            result.add(entry)
            if (entry.name == name) {
                return result.toList()
            }
        }

        return emptyList()
    }

    /**
     * Returns just the entry names from [getDowngradesUntil].
     *
     * This is effectively a custom linked list slicing function.
     *
     * @param name The stopping point for the list of downgrades.
     * @return The list of downgraded entry names from the stopping point to the current entry.
     */
    fun getDowngradeNamesUntil(name: String): List<String> {
        return getDowngradesUntil(name).map { it.name }
    }

    /**
     * Returns an ordered list of all higher-tier versions for this skill.
     *
     * @return A list of [SkillListEntry] objects from the immediate upgrade to the highest available version.
     */
    fun getUpgrades(): List<SkillListEntry> {
        val result: MutableList<SkillListEntry> = mutableListOf()
        var entry: SkillListEntry? = this
        while (entry != null) {
            // Exclude this specific instance from the results.
            if (entry != this) {
                result.add(entry)
            }
            entry = entry.next
        }
        return result.toList()
    }

    /**
     * Returns a list of all of this skill's upgraded entry names.
     *
     * @return The ordered list of this skill's upgraded version names.
     */
    fun getUpgradeNames(): List<String> {
        return getUpgrades().map { it.name }
    }

    /**
     * Returns a subset of upgrades from this version up to a specific higher version.
     *
     * This acts as a linked list "slicing" function.
     *
     * @param lastName The name of the [SkillListEntry] where the slice should end.
     * @return A list of entries from this instance up to the matched name. Returns an empty list if no match is found.
     */
    fun getUpgradesUntil(lastName: String): List<SkillListEntry> {
        val result: MutableList<SkillListEntry> = mutableListOf(this)

        if (name == lastName) {
            return result.toList()
        }

        val upgrades: List<SkillListEntry> = getUpgrades()
        // Traverse forward through upgrades until the target is reached.
        for (entry in upgrades) {
            result.add(entry)
            if (entry.name == lastName) {
                return result.toList()
            }
        }

        return emptyList()
    }

    /**
     * Returns just the entry names from [getUpgradesUntil].
     *
     * This is effectively a custom linked list slicing function.
     *
     * @param name The stopping point for the list of upgrades.
     * @return The list of upgraded entry names from the current entry to the stopping point.
     */
    fun getUpgradeNamesUntil(name: String): List<String> {
        return getUpgradesUntil(name).map { it.name }
    }

    /**
     * Returns an ordered list of every version in this skill's entire upgrade chain.
     *
     * @return A complete list ranging from the lowest base skill to the highest upgrade.
     */
    fun getVersions(): List<SkillListEntry> {
        return getDowngrades() + this + getUpgrades()
    }

    /**
     * Returns the ordered list of all entry names in this skill's upgrade chain.
     *
     * @return The ordered list of skill names.
     */
    fun getVersionNames(): List<String> {
        return getVersions().map { it.name }
    }

    /**
     * Returns a (slightly) user-friendly string of this class's key properties.
     *
     * @return A formatted string containing this instance's information.
     */
    override fun toString(): String {
        val evaluationPointRatioString: String = "%.2f".format(evaluationPointRatio)
        return "{" +
            "name: \"${name}\", " +
            "virtual: $bIsVirtual, " +
            "obtained: $bIsObtained, " +
            "price: $price ($screenPrice), " +
            "discount: ${(discount * 100).roundToInt()}%, " +
            "evalPt: $evaluationPoints ($evaluationPointRatioString / pt)" +
            "}"
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Purchases this skill and triggers updates for related versions.
     *
     * Upon purchasing a skill, all other versions in the same upgrade chain are updated to reflect the new state:
     * - Downregulated versions are marked as [bIsObtained].
     * - In-place upgrades are swapped (current becomes virtual, next becomes available).
     * - Multi-entry upgrades have their [screenPrice] adjusted.
     *
     * @param skillUpLocation The screen location ([Point]) of the Skill Up (+) button. If provided, the bot will physically tap the button.
     */
    fun buy(skillUpLocation: Point? = null) {
        if (skillUpLocation != null) {
            game.tap(
                skillUpLocation.x,
                skillUpLocation.y,
                ButtonSkillUp.template.path,
            )
        }

        bIsObtained = true

        // Update all related versions in the upgrade chain.
        val prev: SkillListEntry? = prev
        prev?.onUpgradePurchased(this)

        val next: SkillListEntry? = next
        next?.onDowngradePurchased(this)
    }

    /**
     * Reverts the purchase state of this skill and propagates the change forward.
     *
     * NOTE: This method is used for internal simulations or resetting state; players cannot literally sell skills back in the game.
     */
    fun sell() {
        if (!bIsObtained) {
            return
        }

        bIsObtained = false

        // Propagate the state change forward to update prices of higher versions.
        val next: SkillListEntry? = next
        next?.onDowngradeSold(this)
    }
}
