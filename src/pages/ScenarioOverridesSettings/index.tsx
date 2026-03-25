import { useMemo, useContext, useRef } from "react"
import { View, Text, ScrollView, StyleSheet } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import { BotStateContext } from "../../context/BotStateContext"
import { SearchPageProvider } from "../../context/SearchPageContext"
import CustomSlider from "../../components/CustomSlider"
import CustomCheckbox from "../../components/CustomCheckbox"
import CustomTitle from "../../components/CustomTitle"
import PageHeader from "../../components/PageHeader"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"

/**
 * The Scenario Overrides Settings page.
 * Provides configuration for scenario-specific behavior overrides.
 */
const ScenarioOverridesSettings = () => {
    usePerformanceLogging("ScenarioOverridesSettings")
    const { colors } = useTheme()
    const bsc = useContext(BotStateContext)
    const scrollViewRef = useRef<ScrollView>(null)

    const { settings, setSettings } = bsc
    const { scenarioOverrides } = settings

    /**
     * Update a scenario override setting.
     * @param key The key of the setting to update.
     * @param value The value to set the setting to.
     */
    const updateOverrideSetting = (key: keyof typeof scenarioOverrides, value: any) => {
        setSettings({
            ...bsc.settings,
            scenarioOverrides: {
                ...bsc.settings.scenarioOverrides,
                [key]: value,
            },
        })
    }

    const styles = useMemo(
        () =>
            StyleSheet.create({
                root: {
                    flex: 1,
                    flexDirection: "column",
                    justifyContent: "center",
                    margin: 10,
                    backgroundColor: colors.background,
                },
                section: {
                    marginBottom: 8,
                },
            }),
        [colors]
    )

    return (
        <View style={styles.root}>
            <PageHeader title="Scenario Overrides Settings" />

            <SearchPageProvider page="ScenarioOverridesSettings" scrollViewRef={scrollViewRef}>
                <ScrollView ref={scrollViewRef} nestedScrollEnabled={true} showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} contentContainerStyle={{ flexGrow: 1 }}>
                    <View className="m-1">
                        <CustomTitle title="Trackblazer Overrides" description="Specific overrides for the Trackblazer scenario." />

                        <View style={styles.section}>
                            <CustomSlider
                                searchId="trackblazer-consecutive-races-limit"
                                value={scenarioOverrides.trackblazerConsecutiveRacesLimit}
                                placeholder={bsc.defaultSettings.scenarioOverrides.trackblazerConsecutiveRacesLimit}
                                onValueChange={(value) => updateOverrideSetting("trackblazerConsecutiveRacesLimit", value)}
                                onSlidingComplete={(value) => updateOverrideSetting("trackblazerConsecutiveRacesLimit", value)}
                                min={3}
                                max={30}
                                step={1}
                                label="Consecutive Races Limit"
                                labelUnit=""
                                showValue={true}
                                showLabels={true}
                                description="Sets the maximum number of consecutive races the bot is allowed to run in the Trackblazer scenario before stopping. Note that a -30 stat penalty can apply starting from 3 consecutive races."
                            />
                        </View>

                        <View style={styles.section}>
                            <CustomSlider
                                searchId="trackblazer-energy-threshold"
                                value={scenarioOverrides.trackblazerEnergyThreshold}
                                placeholder={bsc.defaultSettings.scenarioOverrides.trackblazerEnergyThreshold}
                                onValueChange={(value) => updateOverrideSetting("trackblazerEnergyThreshold", value)}
                                onSlidingComplete={(value) => updateOverrideSetting("trackblazerEnergyThreshold", value)}
                                min={0}
                                max={100}
                                step={5}
                                label="Energy Threshold to use Energy Items"
                                labelUnit=""
                                showValue={true}
                                showLabels={true}
                                description="The energy level below which the bot will attempt to use energy-restoring items in the Trackblazer scenario."
                            />
                        </View>

                        <View style={styles.section}>
                            <CustomSlider
                                searchId="trackblazer-max-retries-per-race"
                                value={scenarioOverrides.trackblazerMaxRetriesPerRace}
                                placeholder={bsc.defaultSettings.scenarioOverrides.trackblazerMaxRetriesPerRace}
                                onValueChange={(value) => updateOverrideSetting("trackblazerMaxRetriesPerRace", value)}
                                onSlidingComplete={(value) => updateOverrideSetting("trackblazerMaxRetriesPerRace", value)}
                                min={0}
                                max={5}
                                step={1}
                                label="Max Retries per Race"
                                labelUnit=""
                                showValue={true}
                                showLabels={true}
                                description="The maximum number of times the bot will attempt to retry a failed race in the Trackblazer scenario."
                            />
                        </View>

                        <View style={styles.section}>
                            <CustomSlider
                                searchId="trackblazer-min-stat-gain-for-charm"
                                value={scenarioOverrides.trackblazerMinStatGainForCharm}
                                placeholder={bsc.defaultSettings.scenarioOverrides.trackblazerMinStatGainForCharm}
                                onValueChange={(value) => updateOverrideSetting("trackblazerMinStatGainForCharm", value)}
                                onSlidingComplete={(value) => updateOverrideSetting("trackblazerMinStatGainForCharm", value)}
                                min={20}
                                max={100}
                                step={5}
                                label="Minimum Main Stat Gain for Good-Luck Charm"
                                labelUnit=""
                                showValue={true}
                                showLabels={true}
                                description="The minimum expected gain for the main training stat required to use a Good-Luck Charm instead of skipping training."
                            />
                        </View>

                        <View style={styles.section}>
                            <CustomCheckbox
                                searchId="trackblazer-whistle-forces-training"
                                checked={scenarioOverrides.trackblazerWhistleForcesTraining}
                                onCheckedChange={(checked) => updateOverrideSetting("trackblazerWhistleForcesTraining", checked)}
                                label="Reset Whistle Forces Training"
                                description="Whether or not using a Reset Whistle means it can ignore the failure chance thresholds in the Training Settings page. If enabled, the bot will pick the best available training after usage even if it's risky."
                            />
                        </View>

                        <View style={styles.section}>
                            <Text style={{ fontSize: 16, color: colors.foreground, marginBottom: 8 }}>Race Grades to check Shop Afterwards</Text>
                            <Text style={{ fontSize: 14, color: colors.foreground, opacity: 0.7, marginBottom: 12 }}>
                                Select which race grades should trigger a shop check after the race in the Trackblazer scenario.
                            </Text>
                            <View style={{ flexDirection: "row", flexWrap: "wrap" }}>
                                {["G1", "G2", "G3"].map((grade) => (
                                    <View
                                        key={grade}
                                        style={{
                                            padding: 10,
                                            borderRadius: 8,
                                            marginRight: 8,
                                            marginBottom: 8,
                                            backgroundColor: scenarioOverrides.trackblazerShopCheckGrades.includes(grade) ? colors.primary : colors.card,
                                        }}
                                        onTouchEnd={() => {
                                            const currentGrades = scenarioOverrides.trackblazerShopCheckGrades
                                            if (currentGrades.includes(grade)) {
                                                updateOverrideSetting(
                                                    "trackblazerShopCheckGrades",
                                                    currentGrades.filter((g) => g !== grade)
                                                )
                                            } else {
                                                updateOverrideSetting("trackblazerShopCheckGrades", [...currentGrades, grade])
                                            }
                                        }}
                                    >
                                        <Text
                                            style={{
                                                fontSize: 14,
                                                fontWeight: "600",
                                                color: scenarioOverrides.trackblazerShopCheckGrades.includes(grade) ? colors.background : colors.foreground,
                                            }}
                                        >
                                            {grade}
                                        </Text>
                                    </View>
                                ))}
                            </View>
                        </View>
                    </View>
                </ScrollView>
            </SearchPageProvider>
        </View>
    )
}

export default ScenarioOverridesSettings
