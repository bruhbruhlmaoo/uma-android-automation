import searchConfig from "../searchConfig"

describe("searchConfig validation", () => {
    it("has no duplicate IDs", () => {
        const ids = searchConfig.map((item) => item.id)
        const uniqueIds = new Set(ids)
        const duplicates = ids.filter((id, index) => ids.indexOf(id) !== index)
        expect(duplicates).toEqual([])
        expect(uniqueIds.size).toBe(ids.length)
    })

    it("all parentId references point to an existing id", () => {
        const allIds = new Set(searchConfig.map((item) => item.id))
        const orphanedParents: string[] = []

        for (const item of searchConfig) {
            if (item.parentId && !allIds.has(item.parentId)) {
                orphanedParents.push(`${item.id} references parentId="${item.parentId}" which does not exist`)
            }
        }

        expect(orphanedParents).toEqual([])
    })

    it("every entry has a non-empty id", () => {
        for (const item of searchConfig) {
            expect(item.id).toBeTruthy()
        }
    })

    it("every entry has a non-empty title", () => {
        for (const item of searchConfig) {
            expect(item.title).toBeTruthy()
        }
    })

    it("every entry has a non-empty page", () => {
        for (const item of searchConfig) {
            expect(item.page).toBeTruthy()
        }
    })

    it("config contains at least 50 entries", () => {
        // Sanity check: the config is substantial
        expect(searchConfig.length).toBeGreaterThanOrEqual(50)
    })
})
