import { clamp, getIndicatorPositionStyle } from "../helpers"

// ===========================================================================
// clamp
// ===========================================================================

describe("clamp", () => {
    it("returns max when value exceeds max", () => {
        expect(clamp(120, 0, 100)).toBe(100)
    })

    it("returns min when value is below min", () => {
        expect(clamp(-10, 0, 100)).toBe(0)
    })

    it("returns value when within range", () => {
        expect(clamp(50, 0, 100)).toBe(50)
    })

    it("returns min when value equals min", () => {
        expect(clamp(0, 0, 100)).toBe(0)
    })

    it("returns max when value equals max", () => {
        expect(clamp(100, 0, 100)).toBe(100)
    })

    it("works with negative range", () => {
        expect(clamp(-5, -10, -1)).toBe(-5)
    })

    it("clamps negative value to negative min", () => {
        expect(clamp(-20, -10, -1)).toBe(-10)
    })
})

// ===========================================================================
// getIndicatorPositionStyle
// ===========================================================================

describe("getIndicatorPositionStyle", () => {
    // -----------------------------------------------------------------------
    // Horizontal mode
    // -----------------------------------------------------------------------

    it("horizontal + 'top' returns { top: 0 }", () => {
        expect(getIndicatorPositionStyle(true, "top", 100, 6)).toEqual({ top: 0 })
    })

    it("horizontal + 'bottom' returns { bottom: 0 }", () => {
        expect(getIndicatorPositionStyle(true, "bottom", 100, 6)).toEqual({ bottom: 0 })
    })

    it("horizontal + numeric 50 returns correct offset", () => {
        // offset = clamp((50/100)*100 - 6/2, 0, 100-6) = clamp(47, 0, 94) = 47
        expect(getIndicatorPositionStyle(true, 50, 100, 6)).toEqual({ top: 47 })
    })

    it("horizontal + numeric 0 clamps offset to 0", () => {
        // offset = clamp((0/100)*100 - 6/2, 0, 94) = clamp(-3, 0, 94) = 0
        expect(getIndicatorPositionStyle(true, 0, 100, 6)).toEqual({ top: 0 })
    })

    it("horizontal + numeric 100 clamps offset to max", () => {
        // offset = clamp((100/100)*100 - 6/2, 0, 94) = clamp(97, 0, 94) = 94
        expect(getIndicatorPositionStyle(true, 100, 100, 6)).toEqual({ top: 94 })
    })

    it("horizontal + invalid string throws error", () => {
        expect(() => getIndicatorPositionStyle(true, "left", 100, 6)).toThrow()
    })

    // -----------------------------------------------------------------------
    // Vertical mode
    // -----------------------------------------------------------------------

    it("vertical + 'left' returns { left: 0 }", () => {
        expect(getIndicatorPositionStyle(false, "left", 200, 8)).toEqual({ left: 0 })
    })

    it("vertical + 'right' returns { right: 0 }", () => {
        expect(getIndicatorPositionStyle(false, "right", 200, 8)).toEqual({ right: 0 })
    })

    it("vertical + numeric 20 returns correct offset", () => {
        // offset = clamp((20/100)*200 - 8/2, 0, 200-8) = clamp(36, 0, 192) = 36
        expect(getIndicatorPositionStyle(false, 20, 200, 8)).toEqual({ left: 36 })
    })

    it("vertical + invalid string throws error", () => {
        expect(() => getIndicatorPositionStyle(false, "top", 200, 8)).toThrow()
    })
})
