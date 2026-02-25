import { useEffect, useState } from "react"
import { View } from "react-native"
import { NavigationContainer } from "@react-navigation/native"
import { createDrawerNavigator } from "@react-navigation/drawer"
import { createNativeStackNavigator } from "@react-navigation/native-stack"
import { PortalHost } from "@rn-primitives/portal"
import { StatusBar } from "expo-status-bar"
import { SafeAreaView, SafeAreaProvider } from "react-native-safe-area-context"
import { BotStateProvider } from "./context/BotStateContext"
import { MessageLogProvider } from "./context/MessageLogContext"
import { SettingsProvider } from "./context/SettingsContext"
import { ThemeProvider, useTheme } from "./context/ThemeContext"
import { SearchProvider, useSearchRegistry } from "./context/SearchRegistryContext"
import { ProfileProvider } from "./context/ProfileContext"
import { useBootstrap } from "./hooks/useBootstrap"
import Home from "./pages/Home"
import Settings from "./pages/Settings"
import TrainingSettings from "./pages/TrainingSettings"
import TrainingEventSettings from "./pages/TrainingEventSettings"
import OCRSettings from "./pages/OCRSettings"
import RacingSettings from "./pages/RacingSettings"
import RacingPlanSettings from "./pages/RacingPlanSettings"
import SkillSettings from "./pages/SkillSettings"
import SkillPlanSettings, { skillPlanSettingsPages } from "./pages/SkillPlanSettings"
import EventLogVisualizer from "./pages/EventLogVisualizer"
import ImportSettingsPreview from "./pages/ImportSettingsPreview"
import DebugSettings from "./pages/DebugSettings"
import DrawerContent from "./components/DrawerContent"
import { NAV_THEME } from "./lib/theme"

export const Tag = "UAA"

const Drawer = createDrawerNavigator()
const Stack = createNativeStackNavigator()

/**
 * Stack navigator for Settings and all sub-pages.
 * This enables proper back button navigation that respects the navigation history.
 */
function SettingsStack() {
    return (
        <Stack.Navigator screenOptions={{ headerShown: false }}>
            <Stack.Screen name="SettingsMain" component={Settings} />
            <Stack.Screen name="TrainingSettings" component={TrainingSettings} />
            <Stack.Screen name="TrainingEventSettings" component={TrainingEventSettings} />
            <Stack.Screen name="OCRSettings" component={OCRSettings} />
            <Stack.Screen name="RacingSettings" component={RacingSettings} />
            <Stack.Screen name="RacingPlanSettings" component={RacingPlanSettings} />
            <Stack.Screen name="SkillSettings" component={SkillSettings} />
            {Object.entries(skillPlanSettingsPages).map(([key, config]) => (
                <Stack.Screen name={config.name}>
                    {(props) => (
                        <SkillPlanSettings
                            {...props}
                            planKey={config.planKey}
                            name={config.name}
                            title={config.title}
                            description={config.description}
                        />
                    )}
                </Stack.Screen>
            ))}
            <Stack.Screen name="EventLogVisualizer" component={EventLogVisualizer} />
            <Stack.Screen name="ImportSettingsPreview" component={ImportSettingsPreview} />
            <Stack.Screen name="DebugSettings" component={DebugSettings} />
        </Stack.Navigator>
    )
}

function MainDrawer() {
    const { colors } = useTheme()

    return (
        <Drawer.Navigator
            drawerContent={(props) => <DrawerContent {...props} />}
            screenOptions={{
                headerShown: false,
                drawerType: "front",
                drawerStyle: {
                    width: 280,
                    backgroundColor: colors.card,
                },
                drawerActiveTintColor: colors.primary,
                drawerInactiveTintColor: colors.foreground,
                overlayColor: "rgba(0, 0, 0, 0.5)",
            }}
        >
            <Drawer.Screen name="Home" component={Home} />
            <Drawer.Screen name="Settings" component={SettingsStack} />
        </Drawer.Navigator>
    )
}

function AppWithBootstrap({ theme, colors }: { theme: string; colors: any }) {
    // Initialize app with bootstrap logic.
    useBootstrap()

    return (
        <SafeAreaView edges={["top"]} style={{ flex: 1, backgroundColor: colors.background }}>
            <NavigationContainer theme={NAV_THEME[theme as "light" | "dark"]}>
                <StatusBar style={theme === "light" ? "dark" : "light"} />
                <MainDrawer />
                <PortalHost />
                <HeadlessRenderer />
            </NavigationContainer>
        </SafeAreaView>
    )
}

/**
 * Renders all settings pages in an invisible view on app boot.
 * This allows SearchableItem components to mount and register their metadata
 * into the global search index without user interaction.
 */
function HeadlessRenderer() {
    const { searchIndex, registerItem } = useSearchRegistry()
    const [shouldRender, setShouldRender] = useState(false)
    const [mounted, setMounted] = useState(true)

    useEffect(() => {
        let unmountTimer: NodeJS.Timeout
        // Delay before mounting to ensure app boot and database initialization are finished.
        const mountTimer = setTimeout(() => {
            setShouldRender(true)

            // Unmount the headless renderer after enough time for all pages to register.
            unmountTimer = setTimeout(() => {
                setMounted(false)
            }, 1000)
        }, 1500)

        return () => {
            clearTimeout(mountTimer)
            if (unmountTimer) {
                clearTimeout(unmountTimer)
            }
        }
    }, [])

    if (!shouldRender || !mounted) return null

    return (
        <View style={{ display: "none", opacity: 0, position: "absolute", width: 0, height: 0 }}>
            <SearchProvider isHeadlessRender={true} overrideIndex={searchIndex} overrideRegister={registerItem}>
                <Settings key="settings" />
                <TrainingSettings key="training-settings" />
                <TrainingEventSettings key="training-event-settings" />
                <OCRSettings key="ocr-settings" />
                <RacingSettings key="racing-settings" />
                <RacingPlanSettings key="racing-plan-settings" />
                <SkillSettings key="skill-settings" />
                {Object.entries(skillPlanSettingsPages).map(([key, config]) => (
                    <SkillPlanSettings key={key} planKey={(config as any).planKey} name={(config as any).name} title={(config as any).title} description={(config as any).description} />
                ))}
                <DebugSettings key="debug-settings" />
            </SearchProvider>
        </View>
    )
}

function AppContent() {
    const { theme, colors } = useTheme()

    return (
        <SearchProvider>
            <BotStateProvider>
                <ProfileProvider>
                    <MessageLogProvider>
                        <SettingsProvider>
                            <AppWithBootstrap theme={theme} colors={colors} />
                        </SettingsProvider>
                    </MessageLogProvider>
                </ProfileProvider>
            </BotStateProvider>
        </SearchProvider>
    )
}

function App() {
    return (
        <SafeAreaProvider>
            <ThemeProvider>
                <AppContent />
            </ThemeProvider>
        </SafeAreaProvider>
    )
}

export default App
