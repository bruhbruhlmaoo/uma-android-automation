module.exports = {
    testMatch: ["<rootDir>/src/**/*.test.ts", "<rootDir>/src/**/*.test.tsx"],
    moduleNameMapper: {
        "^@/(.*)$": "<rootDir>/$1",
    },
    modulePaths: ["<rootDir>/src"],
    transform: {
        "^.+\\.(ts|tsx)$": [
            "babel-jest",
            {
                presets: [
                    ["@babel/preset-env", { targets: { node: "current" } }],
                    "@babel/preset-typescript",
                ],
            },
        ],
    },
    collectCoverageFrom: [
        "src/lib/eventLogParser.ts",
        "src/lib/settingsUtils.ts",
        "src/lib/logger.ts",
        "src/lib/performanceLogger.ts",
        "src/components/**/helpers.ts",
        "src/data/searchConfig.ts",
    ],
}
