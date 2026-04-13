import { getTimestamp, logWithTimestamp, logErrorWithTimestamp, logWarningWithTimestamp } from "../logger"
import { PerformanceLogger } from "../performanceLogger"

describe("logger", () => {
    beforeEach(() => {
        jest.spyOn(console, "log").mockImplementation(() => {})
        jest.spyOn(console, "error").mockImplementation(() => {})
        jest.spyOn(console, "warn").mockImplementation(() => {})
    })

    afterEach(() => {
        jest.restoreAllMocks()
        PerformanceLogger.SUPPRESS_LOGGING = true
    })

    describe("getTimestamp", () => {
        it("returns a formatted timestamp string", () => {
            const ts = getTimestamp()
            // Should match "YYYY-MM-DD HH:MM:SS.mmm" format (23 chars)
            expect(ts).toHaveLength(23)
            expect(ts).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}$/)
        })
    })

    describe("logWithTimestamp", () => {
        it("does not log when SUPPRESS_LOGGING is true", () => {
            PerformanceLogger.SUPPRESS_LOGGING = true
            logWithTimestamp("test message")
            expect(console.log).not.toHaveBeenCalled()
        })

        it("logs with timestamp when SUPPRESS_LOGGING is false", () => {
            PerformanceLogger.SUPPRESS_LOGGING = false
            logWithTimestamp("hello world")
            expect(console.log).toHaveBeenCalledTimes(1)
            expect((console.log as jest.Mock).mock.calls[0][0]).toContain("hello world")
            expect((console.log as jest.Mock).mock.calls[0][0]).toMatch(/^\[.+\] hello world$/)
        })
    })

    describe("logErrorWithTimestamp", () => {
        it("logs error with timestamp", () => {
            logErrorWithTimestamp("error occurred")
            expect(console.error).toHaveBeenCalledTimes(1)
            expect((console.error as jest.Mock).mock.calls[0][0]).toContain("error occurred")
        })

        it("logs error with optional error object", () => {
            const err = new Error("something broke")
            logErrorWithTimestamp("failed", err)
            expect(console.error).toHaveBeenCalledWith(expect.stringContaining("failed"), err)
        })
    })

    describe("logWarningWithTimestamp", () => {
        it("logs warning with timestamp", () => {
            logWarningWithTimestamp("watch out")
            expect(console.warn).toHaveBeenCalledTimes(1)
            expect((console.warn as jest.Mock).mock.calls[0][0]).toContain("watch out")
        })
    })
})
