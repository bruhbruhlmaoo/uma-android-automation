import { logWithTimestamp } from "./logger"

/**
 * Deep merges two objects, preserving nested structure.
 * @param target - The target object to merge into.
 * @param source - The source object to merge from.
 * @returns A new object with merged values from both target and source.
 */
export const deepMerge = <T extends Record<string, any>>(target: T, source: Partial<T>): T => {
    const output = { ...target }
    for (const key in source) {
        if (source[key] && typeof source[key] === "object" && !Array.isArray(source[key]) && source[key] !== null) {
            output[key] = deepMerge((target[key] || {}) as Record<string, any>, source[key] as any) as T[Extract<keyof T, string>]
        } else if (source[key] !== undefined) {
            output[key] = source[key] as T[Extract<keyof T, string>]
        }
    }
    return output
}

/**
 * Converts `Settings` object to database batch format.
 * @param settings - The `Settings` object to convert.
 * @returns An array of objects in the format `{ category: string; key: string; value: any }`.
 */
export const convertSettingsToBatch = (settings: Record<string, any>) => {
    const batch: Array<{ category: string; key: string; value: any }> = []

    Object.entries(settings).forEach(([category, categorySettings]) => {
        Object.entries(categorySettings).forEach(([key, value]) => {
            batch.push({ category, key, value })
        })
    })

    return batch
}

/**
 * Applies all registered migrations to the Settings object.
 * @param settings - The Settings object to apply migrations to.
 * @returns An object containing the migrated Settings object and a boolean indicating whether any migrations were applied.
 */
export const applyMigrations = (settings: any): { settings: any; anyMigrated: boolean } => {
    let anyMigrated = false
    let migratedSettings = settings

    // Migration: Move Training Event specific OCR settings to trainingEvent category.
    const ocr = (migratedSettings as any).ocr
    const debug = (migratedSettings as any).debug

    if (ocr?.ocrConfidence !== undefined) {
        migratedSettings.trainingEvent.ocrConfidence = ocr.ocrConfidence
        delete ocr.ocrConfidence
        anyMigrated = true
        logWithTimestamp("[SettingsManager] Migrated ocrConfidence to trainingEvent category.")
    }

    if (ocr?.enableAutomaticOCRRetry !== undefined) {
        migratedSettings.trainingEvent.enableAutomaticOCRRetry = ocr.enableAutomaticOCRRetry
        delete ocr.enableAutomaticOCRRetry
        anyMigrated = true
        logWithTimestamp("[SettingsManager] Migrated enableAutomaticOCRRetry to trainingEvent category.")
    }

    if (debug?.enableHideOCRComparisonResults !== undefined) {
        migratedSettings.trainingEvent.enableHideOCRComparisonResults = debug.enableHideOCRComparisonResults
        delete debug.enableHideOCRComparisonResults
        anyMigrated = true
        logWithTimestamp("[SettingsManager] Migrated enableHideOCRComparisonResults to trainingEvent category.")
    }

    if (ocr?.ocrThreshold !== undefined) {
        migratedSettings.debug.ocrThreshold = ocr.ocrThreshold
        delete ocr.ocrThreshold
        anyMigrated = true
        logWithTimestamp("[SettingsManager] Migrated ocrThreshold to debug category.")
    }

    // After moving all OCR settings, delete the empty ocr object.
    if (migratedSettings && (migratedSettings as any).ocr && Object.keys((migratedSettings as any).ocr).length === 0) {
        delete (migratedSettings as any).ocr
    }

    // Migration: Convert single stopAtDate string to stopAtDates array.
    const general = migratedSettings.general as any
    if (general?.stopAtDate !== undefined && typeof general.stopAtDate === "string") {
        migratedSettings.general.stopAtDates = [general.stopAtDate]
        delete general.stopAtDate
        anyMigrated = true
        logWithTimestamp("[SettingsManager] Migrated stopAtDate to stopAtDates array.")
    }

    return { settings: migratedSettings, anyMigrated }
}
