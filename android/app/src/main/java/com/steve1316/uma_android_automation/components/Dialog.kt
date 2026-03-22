/**
 * Defines Dialog components.
 *
 * A dialog is just any pop-up window on the screen. These typically have one or two buttons.
 *
 * Adding a New Dialog:
 *
 * After creating your DialogInterface object, you must add this object to the [DialogObjects.items] list. Please add it in alphabetical order for readability.
 *
 * Example usage:
 *
 * // Call the centralized handler through the campaign or game. val result: DialogHandlerResult = campaign.handleDialogs()
 *
 * // Or pass arguments via the map. campaign.handleDialogs(args = mapOf("overrideIgnoreConsecutiveRaceWarning" to true))
 *
 * // Example of how logic is implemented within a DialogHandler: open fun handleDialogs(dialog: DialogInterface? = null, args: Map<String, Any> = mapOf()): DialogHandlerResult { val dialog = dialog
 * ?: DialogUtils.getDialog(game.imageUtils) ?: return DialogHandlerResult.NoDialogDetected when (dialog.name) { "open_soon" -> { game.notificationMessage = "open_soon" MessageLog.i(TAG, "\n[DIALOG]
 * Open Soon!") dialog.close(game.imageUtils) } "continue_career" -> { dialog.close(imageUtils=game.imageUtils) MessageLog.i(TAG, "\n[DIALOG] Continue Career") } else -> { MessageLog.i(TAG,
 * "\n[DIALOG] ${dialog.name}") dialog.close(imageUtils=game.imageUtils) return DialogHandlerResult.Unhandled(dialog) } } return DialogHandlerResult.Handled(dialog) }
 */

package com.steve1316.uma_android_automation.components

import android.graphics.Bitmap
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.utils.ImageUtils
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.TextUtils
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.components.BaseComponentInterface
import com.steve1316.uma_android_automation.types.BoundingBox
import com.steve1316.uma_android_automation.utils.CustomImageUtils
import org.opencv.core.Point

/** Utility class for detecting and handling dialogs in the game. */
object DialogUtils {
    private val TAG: String = "[${MainActivity.loggerTag}]DialogUtils"

    /** List of templates used to detect the title bar gradient of a dialog. */
    private val titleGradientTemplates: List<String> =
        listOf(
            "components/dialog/dialog_title_gradient_0",
            "components/dialog/dialog_title_gradient_1",
        )

    /** List of dialogs that are considered dangerous because they may involve real-world purchases. Detection of these dialogs will cause the bot to stop immediately. */
    private val dangerousDialogs: List<DialogInterface> =
        listOf(
            DialogAgeConfirmation,
            DialogPurchaseCarats,
        )

    /**
     * Check if any dialog is currently displayed on the screen.
     *
     * @param imageUtils The CustomImageUtils instance used to find the dialog.
     * @param tries The number of times to attempt to find the image.
     * @return True if a dialog was detected, false otherwise.
     */
    fun check(imageUtils: CustomImageUtils, tries: Int = 1): Boolean {
        var loc: Point? = null
        for (template in titleGradientTemplates) {
            // Search for the dialog title gradient templates.
            loc = imageUtils.findImage(template, tries = tries, suppressError = true).first
            if (loc != null) {
                break
            }
        }
        return loc != null
    }

    /**
     * Get the title bar text of any dialog currently on the screen.
     *
     * @param imageUtils The CustomImageUtils instance used to find the dialog.
     * @param bitmap Optional bitmap to use when looking for a dialog. If not specified, a screenshot will be taken and used instead.
     * @return The text of the dialog's title bar if one was found, else NULL.
     */
    fun getTitle(imageUtils: CustomImageUtils, bitmap: Bitmap? = null): String? {
        val bitmap: Bitmap = bitmap ?: imageUtils.getSourceBitmap()
        var templateBitmap: Bitmap? = null
        var titleLocation: Point? = null
        for (template in titleGradientTemplates) {
            // Find the location of the title gradient in the given bitmap.
            titleLocation =
                imageUtils.findImageWithBitmap(
                    template,
                    sourceBitmap = bitmap,
                    suppressError = true,
                )
            if (titleLocation != null) {
                // Retrieve the template bitmap to calculate the bounding box.
                templateBitmap = imageUtils.getTemplateBitmap(template.substringAfterLast('/'), "images/" + template.substringBeforeLast('/'))
                break
            }
        }

        // Return null if the title location could not be determined.
        if (titleLocation == null) {
            return null
        }

        // Return null if we failed to find the template bitmap for calculations.
        if (templateBitmap == null) {
            return null
        }

        // Calculate the top-left coordinates and the bounding box of the title.
        val x = titleLocation.x - (templateBitmap.width / 2.0)
        val y = titleLocation.y - (templateBitmap.height / 2.0)

        val bbox =
            BoundingBox(
                imageUtils.relX(x, 0),
                imageUtils.relY(y, 0),
                imageUtils.relWidth((SharedData.displayWidth - (x * 2)).toInt()),
                imageUtils.relHeight(templateBitmap.height),
            )

        // Perform OCR on the identified title region.
        val text: String =
            imageUtils.performOCROnRegion(
                bitmap,
                bbox.x,
                bbox.y,
                bbox.w,
                bbox.h,
                useThreshold = true,
                useGrayscale = true,
                scale = 1.0,
                ocrEngine = "mlkit",
                debugName = "dialogTitle",
            )

        if (text == "") {
            return null
        }

        // Perform fuzzy matching against known dialog titles.
        val match: String? = TextUtils.matchStringInList(text, DialogObjects.items.map { it.title })

        // Attempt to find known titles with different fonts if detection fails.
        if (match == null) {
            val croppedBitmap: Bitmap? =
                imageUtils.createSafeBitmap(
                    bitmap,
                    bbox.x,
                    bbox.y,
                    bbox.w,
                    bbox.h,
                    "Dialog::getTitle cropped",
                )
            if (croppedBitmap == null) {
                return null
            }

            // Check for the "Trophy Won" dialog as a special case.
            if (LabelTrophyWonDialogTitle.check(imageUtils, sourceBitmap = croppedBitmap)) {
                return DialogTrophyWon.title
            }

            MessageLog.e(TAG, "[ERROR] getTitle:: Failed to match any dialogs to the extracted title: $text")
            return null
        }

        return match
    }

    /**
     * Stop the bot if the specified dialog is in the hardcoded [dangerousDialogs] list.
     *
     * Some dialogs are dangerous because they could lead to real-world purchases. This function throws an InterruptedException to immediately stop the bot if a dangerous dialog is detected.
     *
     * @param dialog The dialog to check.
     */
    private fun handleDangerousDialogs(dialog: DialogInterface) {
        if (dialog in dangerousDialogs) {
            throw InterruptedException("Stopping bot due to a dangerous dialog: ${dialog.name}")
        }
    }

    /**
     * Detect and return the [DialogInterface] currently visible on the screen.
     *
     * @param imageUtils The CustomImageUtils instance used to find the dialog.
     * @param bitmap Optional bitmap to use when looking for a dialog. If not specified, a screenshot will be taken and used instead.
     * @return The [DialogInterface] if one was found, else NULL.
     */
    fun getDialog(imageUtils: CustomImageUtils, bitmap: Bitmap? = null): DialogInterface? {
        val bitmap: Bitmap = bitmap ?: imageUtils.getSourceBitmap()
        val title: String = getTitle(imageUtils, bitmap) ?: return null

        // Filter the list of known dialogs for any matches by title.
        val matches: List<DialogInterface> = DialogObjects.items.filter { it.title == title }

        if (matches.isEmpty()) {
            // Throw an exception if getTitle returns a title that is not in our known list.
            throw IllegalStateException("getTitle returned an invalid title: $title")
        }

        if (matches.size == 1) {
            // Handle dangerous dialogs before returning the single match.
            handleDangerousDialogs(matches[0])
            return matches[0]
        }

        // Handle duplicates by checking if the dialog's buttons match what is on screen.
        if (matches.size > 1) {
            for (dialog in matches) {
                if (dialog.buttons.all { button -> button.check(imageUtils, sourceBitmap = bitmap) }) {
                    handleDangerousDialogs(dialog)
                    return dialog
                }
            }
        }

        MessageLog.e(TAG, "[ERROR] getDialog:: Multiple dialogs match the detected title ($title). However, we failed to match any of them to the buttons in the dialog on the screen.")
        return null
    }
}

/** Define the key components and functions for interacting with Dialogs. */
interface DialogInterface {
    /** Log tag used for this dialog. */
    val TAG: String

    /** A unique name used to identify this dialog. */
    val name: String

    /** The on-screen title of the dialog. Multiple dialogs may have the same title. */
    val title: String

    /** List of all the button components within the dialog. If there is a button used to close the dialog, then it MUST be the first entry in this list. */
    val buttons: List<BaseComponentInterface>

    /** The button used primarily to close the dialog. If not specified, the first button in the buttons list will be used. */
    val closeButton: BaseComponentInterface?

    /** The button typically used to accept the dialog. If there is only one button in the dialog, then this may be set to that button. */
    val okButton: BaseComponentInterface?

    /**
     * Close the dialog by clicking the close button.
     *
     * If no close button is specified, then the first button in the [buttons] list is treated as the close button and is clicked.
     *
     * @param imageUtils A reference to a CustomImageUtils instance.
     * @param tries The number of attempts when searching for the button.
     * @return True if the close button was found and clicked.
     */
    fun close(imageUtils: CustomImageUtils, tries: Int = 1): Boolean {
        if (closeButton == null) {
            return buttons.getOrNull(0)?.click(imageUtils = imageUtils, tries = tries) ?: false
        }
        return closeButton?.click(imageUtils = imageUtils, tries = tries) ?: false
    }

    /**
     * Close the dialog by clicking the OK button.
     *
     * If no OK button is defined for this dialog, then the [close] function is called instead.
     *
     * @param imageUtils A reference to a CustomImageUtils instance.
     * @param tries The number of attempts when searching for the button.
     * @return True if the OK button was found and clicked.
     */
    fun ok(imageUtils: CustomImageUtils, tries: Int = 1): Boolean {
        if (okButton == null) {
            return if (buttons.size == 1) {
                close(imageUtils = imageUtils, tries = tries)
            } else {
                false
            }
        }
        return okButton?.click(imageUtils = imageUtils, tries = tries) ?: false
    }
}

/** Store the list of all dialog objects and a mapping of them for easy access. */
object DialogObjects {
	/** List of all [DialogInterface] objects. */
    val items: List<DialogInterface> = listOf(
        DialogAccountLink,                  // Title Screen
        DialogAgeConfirmation,              // Anywhere (ALWAYS THROW ERROR)
        DialogAgendaDetails,                // Career
        DialogAutoFill,                     // Career (Unity Cup)
        DialogAutoSelect,                   // Career Selection
        DialogAllRewardsEarned,             // Career (event only)
        DialogBonusUmamusumeDetails,        // Career -> Career Profile dialog
        DialogBorrowCard,                   // Career Selection
        DialogBorrowCardConfirmation,       // Career Selection
        DialogCareer,                       // Career
        DialogCareerComplete,               // Career
        DialogCareerEventDetails,           // Card details
        DialogCareerProfile,                // Career
        DialogChoices,                      // Career (training event effects)
        DialogCompleteCareer,               // Career (yes this is different from above...)
        DialogConcertSkipConfirmation,      // Career
        DialogConfirmAutoSelect,            // Career Selection
        DialogConfirmExchange,              // Main Screen
        DialogConfirmRestoreRP,             // Team Trials
        DialogConfirmUse,                   // Career (Trackblazer)
        DialogConnectionError,              // Anywhere
        DialogConsecutiveRaceWarning,       // Career
        DialogContinueCareer,               // Main Screen
        DialogDailySale,                    // Team Trials, Special Events, Daily Races
        DialogDateChanged,                  // Anywhere
        DialogDisplaySettings,              // Anywhere
        DialogDownloadError,                // Title Screen (only?)
        DialogEpithet,                      // Career End
        DialogEpithets,                     // Career DialogMenu -> Epithets button
        DialogExchangeComplete,             // Career (Trackblazer)
        DialogExternalLink,                 // Main Screen
        DialogFans,                         // Career DialogGoals
        DialogFeaturedCards,                // Career
        DialogFinalConfirmation,            // Career Selection
        DialogFollowTrainer,                // Career
        DialogGiveUp,                       // Career
        DialogGoalNotReached,               // Career
        DialogGoals,                        // Career
        DialogHelpAndGlossary,              // Anywhere (from options dialog)
        DialogInfirmary,                    // Career
        DialogInsufficientFans,             // Career
        DialogItemsSelected,                // Team Trials, Special Events, Daily Races
        DialogLog,                          // Career
        DialogMenu,                         // Career
        DialogMoodEffect,                   // Career
        DialogMyAgendas,                    // Career
        DialogNoRetries,                    // Career
        DialogNotices,                      // Main Screen
        DialogOpenSoon,                     // Shop (only when clicking inactive daily sales button)
        DialogOptions,                      // Anywhere
        DialogOverwrite,                    // Career -> Agenda
        DialogPerks,                        // Career -> Career Profile dialog
        DialogPlacing,                      // Career -> DialogTryAgain
        DialogPresents,                     // Main Screen (i think?)
        DialogPurchaseAlarmClock,           // Career
        DialogPurchaseCarats,               // Anywhere (ALWAYS THROW ERROR)
        DialogPurchaseDailyRaceTicket,      // Daily Races
        DialogRaceDetails,                  // Daily Races, Special Events, and Career
        DialogRacePlayback,                 // Career
        DialogRaceRecommendations,          // Career
        DialogRecreation,                   // Career
        DialogRegistrationComplete,         // Anywhere
        DialogRequestFulfilled,             // Transfer Requests
        DialogRest,                         // Career
        DialogRestAndRecreation,            // Career
        DialogRewardsCollected,             // Main Screen, Special Events
        DialogRunners,                      // Career -> Race screens
        DialogScheduleRace,                 // Career -> Agenda
        DialogScheduleCancellation,         // Career -> Agenda
        DialogScheduledRaceAvailable,       // Career
        DialogScheduledRaces,               // Career
        DialogScheduleSettings,             // Career
        DialogSessionError,                 // Anywhere
        DialogShop,                         // Career (Trackblazer)
        DialogSkillDetails,                 // Anywhere
        DialogSkillListConfirmation,        // Career
        DialogSkillListConfirmExit,         // Career
        DialogSkillsLearned,                // Career
        DialogSongAcquired,                 // Career
        DialogSparkDetails,                 // Career (legacy uma details)
        DialogSparks,                       // Career -> Career Profile dialog
        DialogSpecialMissions,              // Main Screen, Special Events
        DialogStrategy,                     // Race Screen
        DialogStoryUnlocked,                // Main Screen, end of career
        DialogTeamInfo,                     // Career (Unity Cup)
        DialogTrophyWon,                    // Career
        DialogTryAgain,                     // Career
        DialogUmamusumeClass,               // Career
        DialogUmamusumeDetails,             // Career
        DialogUnityCupAvailable,            // Career (Unity Cup)
        DialogUnityCupConfirmation,         // Career (Unity Cup)
        DialogUnlockRequirements,           // Race Screen
        DialogUnmetRequirements,
        DialogViewStory,                    // Main Screen, end of career
    )

    /** Mapping of each [DialogInterface]'s name to the interface object. */
    val map: Map<String, DialogInterface> = items.associateBy { it.name }
}

// =========================
//      DIALOG OBJECTS
// =========================

/** This dialog also has an "Account Link" button, but we never want to allow the bot to click that, so we won't add it. */
object DialogAccountLink : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogAccountLink"
    override val name: String = "account_link"
    override val title: String = "Account Link"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonLater,
        )
}

/** This dialog has two different OK buttons: ButtonEnter and ButtonOk. However since we never want to handle those buttons, we won't even add them in here. */
object DialogAgeConfirmation : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogAgeConfirmation"
    override val name: String = "age_confirmation"
    override val title: String = "Age Confirmation"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
        )
}

object DialogAgendaDetails : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogAgendaDetails"
    override val name: String = "agenda_details"
    override val title: String = "Agenda Details"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogAutoFill : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogAutoFill"
    override val name: String = "auto_fill"
    override val title: String = "Auto-Fill"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonEditTeam
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
            ButtonEditTeam,
        )
}

object DialogAutoSelect : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogAutoSelect"
    override val name: String = "auto_select"
    override val title: String = "Auto-Select"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonOk
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonOk,
            Checkbox,
        )
}

object DialogAllRewardsEarned : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogAllRewardsEarned"
    override val name: String = "all_rewards_earned"
    override val title: String = "ALL REWARDS EARNED!"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogBonusUmamusumeDetails : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogBonusUmamusumeDetails"
    override val name: String = "bonus_umamusume_details"
    override val title: String = "Bonus Umamusume Details"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogBorrowCard : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogBorrowCard"
    override val name: String = "borrow_card"
    override val title: String = "Borrow Card"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogBorrowCardConfirmation : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogBorrowCardConfirmation"
    override val name: String = "borrow_card_confirmation"
    override val title: String = "Confirmation"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonOk
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
            ButtonOk,
        )
}

object DialogCareer : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogCareer"
    override val name: String = "career"
    override val title: String = "Career"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogCareerComplete : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogCareerComplete"
    override val name: String = "career_complete"
    override val title: String = "Career Complete"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonEditTeam
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonToHome,
            ButtonClose,
            ButtonEditTeam,
        )

    // This dialog is unique in that there are two versions of it.
    // The dialog's close button can be one of two different buttons:
    // "To Home" and "Close"
    override fun close(imageUtils: CustomImageUtils, tries: Int): Boolean {
        if (ButtonToHome.click(imageUtils = imageUtils, tries = tries)) {
            return true
        }

        return ButtonClose.click(imageUtils = imageUtils, tries = tries)
    }
}

object DialogChoices : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogChoices"
    override val name: String = "choices"
    override val title: String = "Choices"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogCompleteCareer : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogCompleteCareer"
    override val name: String = "complete_career"
    override val title: String = "Complete Career"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonFinish
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonFinish,
        )
}

object DialogConcertSkipConfirmation : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogConcertSkipConfirmation"
    override val name: String = "concert_skip_confirmation"
    override val title: String = "Confirmation"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonOk
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonOk,
            Checkbox,
        )
}

object DialogConfirmAutoSelect : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogConfirmAutoSelect"
    override val name: String = "confirm_auto_select"
    override val title: String = "Confirm Auto-Select"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonOk
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonOk,
            Checkbox,
        )
}

object DialogConfirmExchange : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogConfirmExchange"
    override val name: String = "confirm_exchange"
    override val title: String = "Confirm Exchange"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogConfirmUse : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogConfirmUse"
    override val name: String = "confirm_use"
    override val title: String = "Confirm Use"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonUseTrainingItems
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonUseTrainingItems,
        )
}

object DialogConnectionError : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogConnectionError"
    override val name: String = "connection_error"
    override val title: String = "Connection Error"
    override val closeButton = null
    override val okButton = ButtonRetry
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonTitleScreen,
            ButtonRetry,
        )

    // This dialog is unique in that there are two versions of it.
    // The dialog can have either a single button ("Title Screen") or
    // two buttons ("Title Screen" and "Retry").
    override fun ok(imageUtils: CustomImageUtils, tries: Int): Boolean {
        if (ButtonRetry.click(imageUtils = imageUtils, tries = tries)) {
            return true
        }

        return ButtonTitleScreen.click(imageUtils = imageUtils, tries = tries)
    }
}

object DialogConsecutiveRaceWarning : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogConsecutiveRaceWarning"
    override val name: String = "consecutive_race_warning"
    override val title: String = "Warning"
    override val closeButton = null
    override val okButton = ButtonOk
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonOk,
        )
}

object DialogContinueCareer : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogContinueCareer"
    override val name: String = "continue_career"
    override val title: String = "Continue Career"
    override val closeButton = null
    override val okButton = ButtonResume
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonResume,
        )
}

object DialogConfirmRestoreRP : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogConfirmRestoreRP"
    override val name: String = "confirm_restore_rp"
    override val title: String = "Confirm"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonRestore
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonNo,
            ButtonRestore,
        )
}

object DialogDailySale : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogDailySale"
    override val name: String = "daily_sale"
    override val title: String = "Daily Sale"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonShop
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonShop,
        )
}

object DialogDateChanged : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogDateChanged"
    override val name: String = "date_changed"
    override val title: String = "Date Changed"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonOk,
        )
}

object DialogDisplaySettings : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogDisplaySettings"
    override val name: String = "display_settings"
    override val title: String = "Display Settings"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonOk
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonOk,
        )
}

object DialogDownloadError : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogDownloadError"
    override val name: String = "download_error"
    override val title: String = "Download Error"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonRetry
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonTitleScreen,
            ButtonRetry,
        )
}

object DialogEpithet : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogEpithet"
    override val name: String = "epithet"
    override val title: String = "Epithet"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonConfirmExclamation,
            Checkbox,
        )
}

// This is the dialog opened from the Epithets button in DialogMenu.
object DialogEpithets : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogEpithets"
    override val name: String = "epithets"
    override val title: String = "Epithets"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogExchangeComplete : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogExchangeComplete"
    override val name: String = "exchange_complete"
    override val title: String = "Exchange Complete"
    override val closeButton: BaseComponentInterface = ButtonClose
    override val okButton: BaseComponentInterface = ButtonConfirmUse
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
            ButtonConfirmUse,
        )
}

object DialogExternalLink : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogExternalLink"
    override val name: String = "external_link"
    override val title: String = "External Link"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonOk
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonOk,
        )
}

object DialogFans : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogFans"
    override val name: String = "fans"
    override val title: String = "Fans"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogFeaturedCards : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogFeaturedCards"
    override val name: String = "featured_cards"
    override val title: String = "Featured Cards"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogFinalConfirmation : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogFinalConfirmation"
    override val name: String = "final_confirmation"
    override val title: String = "Final Confirmation"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonStartCareer
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonStartCareer,
        )
}

object DialogFollowTrainer : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogFollowTrainer"
    override val name: String = "follow_trainer"
    override val title: String = "Follow Trainer"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonFollow
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonFollow,
        )
}

object DialogGiveUp : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogGiveUp"
    override val name: String = "give_up"
    override val title: String = "Give Up"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonGiveUp
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonGiveUp,
        )
}

object DialogGoalNotReached : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogGoalNotReached"
    override val name: String = "goal_not_reached"
    override val title: String = "Goal Not Reached"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonRace
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonRace,
        )
}

object DialogGoals : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogGoals"
    override val name: String = "goals"
    override val title: String = "Goals"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogHelpAndGlossary : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogHelpAndGlossary"
    override val name: String = "help_and_glossary"
    override val title: String = "Help & Glossary"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogInfirmary : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogInfirmary"
    override val name: String = "infirmary"
    override val title: String = "Infirmary"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonOk
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonOk,
            Checkbox,
        )
}

object DialogInsufficientFans : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogInsufficientFans"
    override val name: String = "insufficient_fans"
    override val title: String = "Insufficient Fans"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonRace
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonRace,
        )
}

object DialogItemsSelected : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogItemsSelected"
    override val name: String = "items_selected"
    override val title: String = "Items Selected"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonRaceExclamationShiftedUp
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonRaceExclamationShiftedUp,
        )
}

object DialogLog : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogLog"
    override val name: String = "log"
    override val title: String = "Log"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogMenu : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogMenu"
    override val name: String = "menu"
    override val title: String = "Menu"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
            ButtonOptions,
            ButtonSaveAndExit,
            ButtonGiveUp,
        )
}

object DialogMoodEffect : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogMoodEffect"
    override val name: String = "mood_effect"
    override val title: String = "Mood Effect"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogMyAgendas : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogMyAgendas"
    override val name: String = "my_agendas"
    override val title: String = "My Agendas"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogNoRetries : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogNoRetries"
    override val name: String = "no_retries"
    override val title: String = "No Retries"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonEndCareer,
        )
}

object DialogNotices : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogNotices"
    override val name: String = "notices"
    override val title: String = "Notices"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogOpenSoon : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogOpenSoon"
    override val name: String = "open_soon"
    override val title: String = "Open Soon!"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogCareerEventDetails : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogCareerEventDetails"
    override val name: String = "career_event_details"
    override val title: String = "Career Event Details"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogCareerProfile : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogCareerProfile"
    override val name: String = "career_profile"
    override val title: String = "Career Profile"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogOptions : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogOptions"
    override val name: String = "options"
    override val title: String = "Options"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonSave
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonSave,
        )
}

object DialogOverwrite : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogOverwrite"
    override val name: String = "overwrite"
    override val title: String = "Overwrite"
    override val closeButton = null
    override val okButton: ComponentInterface = ButtonOverwrite
    override val buttons: List<ComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonOverwrite,
        )
}

object DialogPerks : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogPerks"
    override val name: String = "perks"
    override val title: String = "Perks"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogPlacing : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogPlacing"
    override val name: String = "placing"
    override val title: String = "Placing"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogPresents : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogPresents"
    override val name: String = "presents"
    override val title: String = "Presents"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonCollectAll
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
            ButtonCollectAll,
        )
}

/**
 * If the player has 0 carats, then this dialog shows a "Purchase Carats" button instead of ButtonOk. We don't even want to humor this as an option, so that button will not be added.
 *
 * The other, less scary option is it will have a ButtonOk button which will attempt to buy a clock using carats. Again, we don't want to even give the bot a chance to do this, so we just won't even
 * add that button in here.
 */
object DialogPurchaseAlarmClock : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogPurchaseAlarmClock"
    override val name: String = "purchase_alarm_clock"
    override val title: String = "Purchase Alarm Clock"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
        )
}

object DialogPurchaseCarats : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogPurchaseCarats"
    override val name: String = "purchase_carats"
    override val title: String = "Purchase Carats"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogPurchaseDailyRaceTicket : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogPurchaseDailyRaceTicket"
    override val name: String = "purchase_daily_race_ticket"
    override val title: String = "Purchase Daily Race Ticket"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonOk
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonOk,
        )
}

object DialogRaceDetails : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogRaceDetails"
    override val name: String = "race_details"
    override val title: String = "Race Details"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonRace,
            ButtonRaceExclamation,
        )

    // This dialog is unique in that there are three variants of it.
    // The normal race details dialog has a "Race!" button whereas
    // the career version just has a "Race" button. There is also
    // an informational version that only has a "Close" button.
    override fun ok(imageUtils: CustomImageUtils, tries: Int): Boolean {
        if (ButtonRaceExclamation.click(imageUtils = imageUtils, tries = tries)) {
            return true
        }

        if (ButtonRace.click(imageUtils = imageUtils, tries = tries)) {
            return true
        }

        return ButtonClose.click(imageUtils = imageUtils, tries = tries)
    }
}

object DialogRacePlayback : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogRacePlayback"
    override val name: String = "race_playback"
    override val title: String = "Race Playback"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonOk
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonOk,
            Checkbox,
            RadioLandscape,
            RadioPortrait,
        )
}

object DialogRaceRecommendations : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogRaceRecommendations"
    override val name: String = "race_recommendations"
    override val title: String = "Race Recommendations"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonConfirm
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonConfirm,
            ButtonRaceRecommendationsCenterStage,
            ButtonRaceRecommendationsPathToFame,
            ButtonRaceRecommendationsForgeYourOwnPath,
            Checkbox,
        )
}

object DialogRecreation : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogRecreation"
    override val name: String = "recreation"
    override val title: String = "Recreation"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonOk
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonOk,
            Checkbox,
        )
}

object DialogRegistrationComplete : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogRegistrationComplete"
    override val name: String = "registration_complete"
    override val title: String = "Registration Complete"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogRequestFulfilled : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogRequestFulfilled"
    override val name: String = "request_fulfilled"
    override val title: String = "REQUEST FULFILLED"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogRest : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogRest"
    override val name: String = "rest"
    override val title: String = "Rest"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonOk
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonOk,
            Checkbox,
        )
}

object DialogRestAndRecreation : DialogInterface {
    // This one doesn't have a checkbox to not ask again for some reason.
    override val TAG: String = "[${MainActivity.loggerTag}]DialogRestAndRecreation"
    override val name: String = "rest_and_recreation"
    override val title: String = "Rest & Recreation"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonOk
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonOk,
        )
}

object DialogRewardsCollected : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogRewardsCollected"
    override val name: String = "rewards_collected"
    override val title: String = "Rewards Collected"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogRunners : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogRunners"
    override val name: String = "runners"
    override val title: String = "Runners"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogScheduleRace : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogScheduleRace"
    override val name: String = "schedule_race"
    override val title: String = "Schedule Race"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<ComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogScheduleCancellation : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogScheduleCancellation"
    override val name: String = "schedule_cancellation"
    override val title: String = "Schedule Cancellation"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<ComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogScheduledRaceAvailable : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogScheduledRaceAvailable"
    override val name: String = "scheduled_race_available"
    override val title: String = "Scheduled Race Available"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonRace
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
            ButtonRace,
        )
}

object DialogScheduledRaces : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogScheduledRaces"
    override val name: String = "scheduled_races"
    override val title: String = "Scheduled Races"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogScheduleSettings : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogScheduleSettings"
    override val name: String = "schedule_settings"
    override val title: String = "Schedule Settings"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonSaveSchedule
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonSaveSchedule,
        )
}

object DialogSessionError : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogSessionError"
    override val name: String = "session_error"
    override val title: String = "Session Error"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonTitleScreen,
        )
}

object DialogShop : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogShop"
    override val name: String = "shop"
    override val title: String = "Shop"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonShop
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
            ButtonShop,
        )
}

object DialogSkillDetails : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogSkillDetails"
    override val name: String = "skill_details"
    override val title: String = "Skill Details"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogSkillListConfirmation : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogSkillListConfirmation"
    override val name: String = "skill_list_confirmation"
    override val title: String = "Confirmation"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonLearn
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonLearn,
        )
}

object DialogSkillListConfirmExit : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogSkillListConfirmExit"
    override val name: String = "skill_list_confirm_exit"
    override val title: String = "Confirm"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonOk
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonOk,
        )
}

object DialogSkillsLearned : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogSkillsLearned"
    override val name: String = "skills_learned"
    override val title: String = "Skills Learned"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogSongAcquired : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogSongAcquired"
    override val name: String = "song_acquired"
    override val title: String = "Song Acquired"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogSparkDetails : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogSparkDetails"
    override val name: String = "spark_details"
    override val title: String = "Spark Details"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogSparks : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogSparks"
    override val name: String = "sparks"
    override val title: String = "Sparks"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogSpecialMissions : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogSpecialMissions"
    override val name: String = "special_missions"
    override val title: String = "Special Missions"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonCollectAll
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonOk,
            ButtonCollectAll,
        )
}

object DialogStrategy : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogStrategy"
    override val name: String = "strategy"
    override val title: String = "Strategy"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonConfirm
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonConfirm,
            ButtonRaceStrategyFront,
            ButtonRaceStrategyPace,
            ButtonRaceStrategyLate,
            ButtonRaceStrategyEnd,
        )
}

object DialogStoryUnlocked : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogStoryUnlocked"
    override val name: String = "story_unlocked"
    override val title: String = "Story Unlocked"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonToHome,
        )
}

object DialogTeamInfo : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogTeamInfo"
    override val name: String = "team_info"
    override val title: String = "Team Info"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonEditTeam
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
            ButtonEditTeam,
        )
}

object DialogTrophyWon : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogTrophyWon"
    override val name: String = "trophy_won"
    override val title: String = "TROPHY WON!"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogTryAgain : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogTryAgain"
    override val name: String = "try_again"
    override val title: String = "Try Again"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonTryAgain
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonTryAgain,
        )
}

object DialogUmamusumeClass : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogUmamusumeClass"
    override val name: String = "umamusume_class"
    override val title: String = "Umamusume Class"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogUmamusumeDetails : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogUmamusumeDetails"
    override val name: String = "umamusume_details"
    override val title: String = "Umamusume Details"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogUnityCupAvailable : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogUnityCupAvailable"
    override val name: String = "unity_cup_available"
    override val title: String = "Unity Cup Available"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogUnityCupConfirmation : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogUnityCupConfirmation"
    override val name: String = "unity_cup_confirmation"
    override val title: String = "Confirmation"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonBeginShowdown
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonBeginShowdown,
        )
}

object DialogUnlockRequirements : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogUnlockRequirements"
    override val name: String = "unlock_requirements"
    override val title: String = "Unlock Requirements"
    override val closeButton = null
    override val okButton = null
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonClose,
        )
}

object DialogUnmetRequirements : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogUnmetRequirements"
    override val name: String = "unmet_requirements"
    override val title: String = "Unmet Requirements"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonRace
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonRace,
        )
}

object DialogViewStory : DialogInterface {
    override val TAG: String = "[${MainActivity.loggerTag}]DialogViewStory"
    override val name: String = "view_story"
    override val title: String = "View Story"
    override val closeButton = null
    override val okButton: BaseComponentInterface = ButtonOk
    override val buttons: List<BaseComponentInterface> =
        listOf(
            ButtonCancel,
            ButtonOk,
            RadioLandscape,
            RadioPortrait,
            RadioVoiceOff,
        )
}
