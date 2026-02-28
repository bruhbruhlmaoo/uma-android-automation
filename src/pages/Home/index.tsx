import * as Application from "expo-application"
import MessageLog from "../../components/MessageLog"
import { useContext, useEffect, useState } from "react"
import { BotStateContext } from "../../context/BotStateContext"
import { useSettings } from "../../context/SettingsContext"
import { logWithTimestamp, logErrorWithTimestamp } from "../../lib/logger"
import { DeviceEventEmitter, StyleSheet, View, NativeModules } from "react-native"
import { Snackbar } from "react-native-paper"
import { MessageLogContext } from "../../context/MessageLogContext"
import { useTheme } from "../../context/ThemeContext"
import CustomButton from "../../components/CustomButton"
import { Text } from "../../components/ui/text"
import { AlertDialog, AlertDialogAction, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "../../components/ui/alert-dialog"
import { Ionicons } from "@expo/vector-icons"
import { Tooltip, TooltipContent, TooltipTrigger } from "../../components/ui/tooltip"
import PageHeader from "../../components/PageHeader"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"

const styles = StyleSheet.create({
    root: {
        flex: 1,
        flexDirection: "column",
        alignItems: "center",
        paddingHorizontal: 10,
        paddingVertical: 10,
    },
    contentContainer: {
        flex: 1,
        width: "100%",
        flexDirection: "column",
    },
    button: {
        width: 100,
    },
})

/**
 * The main Home page of the application.
 * Displays the Start/Stop button for the bot, a message log, and handles bot lifecycle events including settings persistence and readiness checks.
 */
const Home = () => {
    usePerformanceLogging("Home")
    const { StartModule } = NativeModules

    const { isDark, colors } = useTheme()
    const [isRunning, setIsRunning] = useState<boolean>(false)
    const [showNotReadyDialog, setShowNotReadyDialog] = useState<boolean>(false)
    const [snackbarOpen, setSnackbarOpen] = useState<boolean>(false)
    const [snackbarMessage, setSnackbarMessage] = useState<string>("")
    const [deviceMetrics, setDeviceMetrics] = useState<{ width: number; height: number; dpi: number } | null>(null)
    const [unsupportedReason, setUnsupportedReason] = useState<string | null>(null)

    const bsc = useContext(BotStateContext)
    const mlc = useContext(MessageLogContext)
    const { saveSettings } = useSettings()

    const pulseAnim = useRef(new Animated.Value(1)).current

    useEffect(() => {
        let animation: Animated.CompositeAnimation | null = null

        if (unsupportedReason) {
            // Pulsate the icon to grab attention when there's an unsupported device.
            animation = Animated.loop(
                Animated.sequence([
                    Animated.timing(pulseAnim, {
                        toValue: 1.25,
                        duration: 700,
                        useNativeDriver: true,
                    }),
                    Animated.timing(pulseAnim, {
                        toValue: 1,
                        duration: 700,
                        useNativeDriver: true,
                    }),
                ])
            )
            animation.start()
        } else {
            pulseAnim.setValue(1)
        }

        return () => {
            animation?.stop()
        }
    }, [unsupportedReason])

    useEffect(() => {
        const mediaProjectionSubscription = DeviceEventEmitter.addListener("MediaProjectionService", (data) => {
            setIsRunning(data["message"] === "Running")
        })

        const botServiceSubscription = DeviceEventEmitter.addListener("BotService", (data) => {
            if (data["message"] === "Running") {
                mlc.setMessageLog([])
            }
        })

        getVersion()
        fetchDeviceMetrics()

        return () => {
            mediaProjectionSubscription.remove()
            botServiceSubscription.remove()
        }
    }, [])

    /**
     * Fetch device metrics from NativeModule.
     */
    const fetchDeviceMetrics = async () => {
        try {
            const metrics = await StartModule.getDeviceDimensions()
            setDeviceMetrics(metrics)

            const { width, height, dpi } = metrics
            const isWidth1080 = Math.min(width, height) === 1080
            const is1920 = Math.max(width, height) === 1920
            const is2340 = Math.max(width, height) === 2340

            if (!isWidth1080 || (!is1920 && !is2340)) {
                setUnsupportedReason(`unsupported resolution: ${width}x${height}`)
            } else if (is1920 && dpi !== 240) {
                setUnsupportedReason(`unsupported DPI: ${dpi} (expected 240)`)
            } else if (is2340 && dpi !== 450) {
                setUnsupportedReason(`unsupported DPI: ${dpi} (expected 450)`)
            } else {
                setUnsupportedReason(null)
            }
        } catch (error) {
            logErrorWithTimestamp("[Home] Failed to fetch device dimensions:", error)
        }
    }

    /**
     * Grab the program name and version.
     */
    const getVersion = () => {
        const appName = Application.applicationName || "App"
        var version = Application.nativeApplicationVersion || "0.0.0"
        version += " (" + (Application.nativeBuildVersion || "0") + ")"
        logWithTimestamp(`Android app ${appName} version is ${version}`)
        bsc.setAppName(appName)
        bsc.setAppVersion(version)
    }

    /**
     * Handles the button press for starting or stopping the bot.
     */
    const handleButtonPress = async () => {
        if (isRunning) {
            StartModule.stop()
        } else if (bsc.readyStatus) {
            // Save settings before starting the bot.
            // Also has the added benefit of only writing to the SQLite database when the bot is started instead of every time the settings are changed.
            logWithTimestamp("[Home] Saving settings before starting bot...")
            try {
                await saveSettings()
                logWithTimestamp("[Home] Settings saved successfully, starting bot...")
            } catch (error) {
                logErrorWithTimestamp("[Home] Failed to save settings:", error)
                setSnackbarMessage(`Failed to save settings before starting: ${error}`)
                setSnackbarOpen(true)
            }
            StartModule.start()
        } else {
            setShowNotReadyDialog(true)
        }
    }

    return (
        <View style={styles.root}>
            <PageHeader
                title=""
                showHomeButton={false}
                style={{ width: "100%" }}
                centerComponent={
                    <View style={{ flexDirection: "row", alignItems: "center", gap: 8 }}>
                        <CustomButton variant={isRunning ? "destructive" : isDark ? "default" : "secondary"} onPress={handleButtonPress} isLoading={isRunning} style={styles.button}>
                            {isRunning ? "Stop" : bsc.readyStatus ? "Start" : "Not Ready"}
                        </CustomButton>
                        {unsupportedReason ? (
                            <Tooltip delayDuration={150}>
                                <TooltipTrigger>
                                    <Animated.View style={{ transform: [{ scale: pulseAnim }] }}>
                                        <Ionicons name="alert-circle-outline" size={24} color={colors.warningBorder} />
                                    </Animated.View>
                                </TooltipTrigger>
                                <TooltipContent sideOffset={12} side="bottom" style={{ maxWidth: 350, backgroundColor: colors.warningBg, borderColor: colors.warningBorder, borderWidth: 1 }}>
                                    <Text
                                        style={{ color: colors.warningText }}
                                    >{`Current device display: ${deviceMetrics?.width}x${deviceMetrics?.height} DPI ${deviceMetrics?.dpi}.\n\nBe warned that performance will be degraded due to ${unsupportedReason}. Use 1080x1920 DPI 240 or 1080x2340 DPI 450 for optimal performance.`}</Text>
                                </TooltipContent>
                            </Tooltip>
                        ) : (
                            !bsc.readyStatus &&
                            !isRunning && (
                                <Tooltip delayDuration={150}>
                                    <TooltipTrigger>
                                        <Ionicons name="information-circle-outline" size={24} color={colors.foreground} />
                                    </TooltipTrigger>
                                    <TooltipContent sideOffset={12} side="bottom">
                                        <Text>Select a Scenario in Settings to start</Text>
                                    </TooltipContent>
                                </Tooltip>
                            )
                        )}
                    </View>
                }
            />

            <View style={styles.contentContainer}>
                <MessageLog />
            </View>

            <AlertDialog open={showNotReadyDialog} onOpenChange={setShowNotReadyDialog}>
                <AlertDialogContent onDismiss={() => setShowNotReadyDialog(false)}>
                    <AlertDialogHeader>
                        <AlertDialogTitle>Not Ready</AlertDialogTitle>
                        <AlertDialogDescription>A scenario must be selected before starting the bot. Please go to Settings to select a scenario.</AlertDialogDescription>
                    </AlertDialogHeader>
                    <AlertDialogFooter>
                        <AlertDialogAction onPress={() => setShowNotReadyDialog(false)}>
                            <Text>OK</Text>
                        </AlertDialogAction>
                    </AlertDialogFooter>
                </AlertDialogContent>
            </AlertDialog>

            <Snackbar
                visible={snackbarOpen}
                onDismiss={() => setSnackbarOpen(false)}
                action={{
                    label: "Close",
                    onPress: () => {
                        setSnackbarOpen(false)
                    },
                }}
                style={{ backgroundColor: "red", borderRadius: 10 }}
            >
                {snackbarMessage}
            </Snackbar>
        </View>
    )
}

export default Home
