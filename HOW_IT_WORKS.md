# How It Works

*Last updated: 2026-04-11*

A comprehensive guide to the inner workings of the app. This document explains what the bot does at each step of a campaign, how it makes decisions, and how each scenario differs.

## Table of Contents

- [1. Architecture Overview](#1-architecture-overview)
- [2. The Turn System](#2-the-turn-system)
- [3. The Main Loop](#3-the-main-loop)
- [4. A Turn in Detail](#4-a-turn-in-detail)
- [5. Decision Engine](#5-decision-engine)
- [6. Training System](#6-training-system)
- [7. Racing System](#7-racing-system)
- [8. Training Events](#8-training-events)
- [9. Scenario: URA Finale](#9-scenario-ura-finale)
- [10. Scenario: Unity Cup](#10-scenario-unity-cup)
- [11. Scenario: Trackblazer](#11-scenario-trackblazer)

---

## 1. Architecture Overview

The bot is an Android app built with a **React Native** frontend (settings UI, message log) and a **Kotlin** backend (automation engine). It uses the [Android CV Automation Library](https://github.com/steve1316/android-cv-automation-library) framework to interact with the game.

**How it sees the screen:** A `MediaProjectionService` captures the device screen. The bot then uses **OpenCV template matching** (TM_CCOEFF_NORMED with multi-scale search) to detect buttons, icons, and dialogs, **OCR** (Google ML Kit + Tesseract) to read text like stat values, event names, and race names, and optionally **YOLOv8 object detection** (via ONNX Runtime) to detect training stat gain digits with higher accuracy than template matching.

**How it interacts:** An `AccessibilityService` performs tap and swipe gestures on the device.

**How it decides:** The bot runs a `process()` loop that is called repeatedly by the `Game` class. Each call handles one "tick" — detecting which screen the game is on and taking the appropriate action.

```mermaid
classDiagram
    class Campaign {
        +process() TaskResult?
        +handleMainScreen() Boolean
        +decideNextAction() MainScreenAction
        +executeAction() Boolean
    }
    Campaign <|-- UraFinale
    Campaign <|-- UnityCup
    Campaign <|-- Trackblazer
    Campaign *-- Racing
    Campaign *-- Training
    Campaign *-- TrainingEvent
    Campaign *-- SkillPlan
    Campaign *-- Trainee
```

The `Game` class instantiates the correct scenario subclass (`UraFinale`, `UnityCup`, or `Trackblazer`) based on the user's selection, then calls `process()` in a loop until the campaign ends or the bot is stopped.

---

## 2. The Turn System

A full campaign spans **75 turns** across 3 years plus a finale season:

| Year | Turns | Months |
|------|-------|--------|
| **Junior** | 1–24 | Pre-Debut (1–11), Debut Race (12), Post-Debut (13–24) |
| **Classic** | 25–48 | Regular (25–36), Summer (37–40), Regular (41–48) |
| **Senior** | 49–72 | Regular (49–60), Summer (61–64), Regular (65–72) |
| **Finale** | 73–75 | Qualifier (73), Semi-Final (74), Finals (75) |

Each year has 12 months with 2 phases each (Early and Late), totaling 24 turns per year. Months run from January through December.

```mermaid
gantt
    title Campaign Timeline (75 Turns)
    dateFormat X
    axisFormat %s
    section Junior Year
    Pre-Debut (1-11)     :a1, 1, 11
    Debut Race (12)      :milestone, m1, 12, 12
    Post-Debut (13-24)   :a2, 13, 24
    section Classic Year
    Regular (25-36)      :a3, 25, 36
    Summer (37-40)       :crit, a4, 37, 40
    Regular (41-48)      :a5, 41, 48
    section Senior Year
    Regular (49-60)      :a6, 49, 60
    Summer (61-64)       :crit, a7, 61, 64
    Regular (65-72)      :a8, 65, 72
    section Finale
    Qualifier (73)       :crit, a9, 73, 73
    Semi-Final (74)      :crit, a10, 74, 74
    Finals (75)          :crit, a11, 75, 75
```

> [!NOTE]
> Sections highlighted in red are **special periods** where normal gameplay rules change — Summer blocks racing entirely, and Finale forces mandatory back-to-back races.

**Key periods:**
- **Pre-Debut (turns 1–11):** No races are available yet. The bot focuses on training and building relationships.
- **Summer (turns 37–40 and 61–64):** Training-only period. No races can be entered (unless using the in-game race agenda override).
- **Finale (turns 73–75):** Three mandatory back-to-back races. Injury and consecutive race checks are skipped.

**Date detection:** The bot reads the date string from the screen via OCR (e.g., "Classic Year Early Feb") and converts it to an internal turn number. During Pre-Debut, it reads a "turns remaining" countdown instead. During Finale, it detects the goal text or Trackblazer's "X/3" indicator.

---

## 3. The Main Loop

Every tick of the bot calls `process()`, which checks the current screen and dispatches to the appropriate handler:

```mermaid
flowchart TD
    Start["process() called"] --> Dialogs{"Any dialogs\ndetected?"}
    Dialogs -->|Yes| HandleDialog["Handle dialog\n(close, confirm, etc.)"]
    HandleDialog --> Return["Return null\n(continue loop)"]
    Dialogs -->|No| MainScreen{"On the\nMain Screen?"}
    MainScreen -->|Yes| HandleMain["handleMainScreen()\n(full turn logic)"]
    HandleMain --> Return
    MainScreen -->|No| TrainingEvent{"Training Event\nscreen?"}
    TrainingEvent -->|Yes| HandleEvent["handleTrainingEvent()\n(select reward option)"]
    HandleEvent --> Return
    TrainingEvent -->|No| MandatoryRace{"Mandatory Race\nPrep screen?"}
    MandatoryRace -->|Yes| HandleMandatory["handleRaceEvents()\n(enter mandatory race)"]
    HandleMandatory --> Return
    MandatoryRace -->|No| RacingScreen{"Already on\nRacing screen?"}
    RacingScreen -->|Yes| HandleRace["handleStandaloneRace()\n(complete the race)"]
    HandleRace --> Return
    RacingScreen -->|No| EndScreen{"Career End\nscreen?"}
    EndScreen -->|Yes| FinalUpdate["Purchase career-end skills\nRead final fan count\nLog final stats"]
    FinalUpdate --> Complete["Return Success\n(bot stops)"]
    EndScreen -->|No| CampaignSpecific{"Campaign-specific\ncondition?"}
    CampaignSpecific -->|Yes| HandleCampaign["Handle scenario logic\n(e.g. Unity Cup race)"]
    HandleCampaign --> Return
    CampaignSpecific -->|No| Inheritance{"Inheritance\nevent?"}
    Inheritance -->|Yes| HandleInherit["Accept inheritance"]
    HandleInherit --> Return
    Inheritance -->|No| Misc["Perform misc checks\nor tap to progress"]
    Misc --> Return
```

**Key points:**
- **Dialogs are always checked first.** Any popup (confirmation, warning, tutorial) is handled before any other logic runs.
- **Main Screen handling** is where the core turn logic lives — stat updates, decision-making, and action execution all happen here.
- **Training Events** appear after a training or race completes and offer reward choices.
- **Campaign-specific conditions** allow each scenario to inject custom screen detection (e.g., Unity Cup's opponent selection screen).
- If no known screen is detected, the bot taps the screen to try to progress past any intermediate animation or transition.

> [!TIP]
> The main loop is designed to be **idempotent** — each call to `process()` handles exactly one screen transition. If the game is between screens or in an animation, the bot simply taps and waits for the next tick.

---

## 4. A Turn in Detail

When the bot detects it is on the Main Screen, `handleMainScreen()` orchestrates the full turn:

```mermaid
sequenceDiagram
    participant Bot as Campaign
    participant OCR as OCR Engine
    participant State as Game State

    Bot->>Bot: onBeforeMainScreenUpdate()
    Note over Bot: Scenario hook (e.g. Trackblazer shop check)

    Bot->>OCR: Read date string from screen
    OCR-->>Bot: "Classic Year Early Feb"
    Bot->>State: updateDate() → turn 27

    Note over Bot: Date changed → reset daily flags

    par 9 parallel OCR threads (10s timeout)
        Bot->>OCR: Read Speed stat
        Bot->>OCR: Read Stamina stat
        Bot->>OCR: Read Power stat
        Bot->>OCR: Read Guts stat
        Bot->>OCR: Read Wit stat
        Bot->>OCR: Read Skill Points
        Bot->>OCR: Read Mood
        Bot->>OCR: Read Energy
        Bot->>OCR: Check racing requirements
    end

    Bot->>Bot: Update aptitudes (first time only)
    Bot->>Bot: Update fan count (if needed)
    Bot->>Bot: performGlobalChecks()
    Bot->>Bot: onMainScreenEntry()
    Note over Bot: Scenario hook (e.g. Trackblazer item usage)
    Bot->>Bot: decideNextAction()
    Bot->>Bot: executeAction()
```

### 4.1 Parallel Turn-Start Updates

Every time the date changes, the bot reads the trainee's current state using **9 parallel OCR threads** coordinated by a `CountDownLatch` with a **10-second timeout**:

| Thread | Reads | Method |
|--------|-------|--------|
| 1–5 | Speed, Stamina, Power, Guts, Wit | `trainee.updateStats()` |
| 6 | Skill Points | `trainee.updateSkillPoints()` |
| 7 | Mood (icon-based detection) | `trainee.updateMood()` |
| 8 | Racing requirements (fans/trophies) | `racing.checkRacingRequirements()` |
| 9 | Energy (bar position) | `trainee.updateEnergy()` |

Thread 8 (racing requirements) is **skipped during summer** since no races are available. Logging output is temporarily disabled during parallel reads to avoid garbled messages, then re-enabled after all threads complete.

> [!WARNING]
> If any thread fails to complete within the 10-second timeout, the bot logs an error and continues with whatever data it managed to read. Stat values that timed out retain their previous values.

### 4.2 Global Checks

After stat updates, the bot performs several global checks that can stop or pause the campaign:

1. **Pre-Finals Skill Shopping (turn 72):** If the `preFinals` skill plan is enabled, the bot opens the skill shop and purchases skills before entering the finale.
2. **Skill Point Threshold:** If skill points reach the configured threshold, the bot either runs the `skillPointCheck` skill plan or stops entirely.
3. **Stop Before Finals:** If `enableStopBeforeFinals` is on and the bot reaches turn 72, it stops so the user can take over for the finals.
4. **Stop at Date:** If the current date matches any user-configured stop dates, the bot stops.

### 4.3 Scenario Hooks

Each scenario can override these hooks to inject custom logic at specific points in the turn:

| Hook | When Called | Example Usage |
|------|-----------|---------------|
| `onBeforeMainScreenUpdate()` | Before date detection | Trackblazer: check if shop visit is needed |
| `onAfterTurnStartUpdates()` | After parallel OCR reads | Additional post-update logic |
| `onMainScreenEntry()` | Before decision-making | Trackblazer: use training items |
| `onScheduledRacePrepScreen()` | On the Race Prep screen before a scheduled or mandatory race | Trackblazer: use race items (hammers, glow sticks) |
| `handleRaceEventFallback()` | When a race attempt fails (e.g. consecutive race limit) | Trackblazer: back out and train instead (non-mandatory races only) |
| `resetDailyFlags()` | When date changes | Reset scenario-specific per-turn flags |

---

## 5. Decision Engine

The `decideNextAction()` method determines what the bot should do this turn. It follows a strict **priority waterfall** — the first matching condition wins:

```mermaid
flowchart TD
    Start["decideNextAction()"] --> A{"Mandatory or\nScheduled Race?"}
    A -->|Yes| RACE["→ RACE"]
    A -->|No| B{"Racing popup\nencountered?"}
    B -->|Yes| RACE
    B -->|No| C{"Force Racing\nenabled?"}
    C -->|Yes| RACE
    C -->|No| D{"Maiden race\nnot completed?"}
    D -->|Yes| RACE
    D -->|No| E{"Pre-Summer prep?\n(June Late, Classic/Senior)"}
    E -->|Yes| PreSummer{"Energy < 70%?"}
    PreSummer -->|Yes| REST["→ REST"]
    PreSummer -->|No| MoodCheck{"Mood < Great?"}
    MoodCheck -->|Yes| RECOVER["→ RECOVER_MOOD"]
    MoodCheck -->|No| WIT["→ TRAIN (forced Wit)"]
    E -->|No| F{"Fan or Trophy\nrequirement active?"}
    F -->|Yes| RACE
    F -->|No| G{"Injury detected?\n(skipped in Finale)"}
    G -->|Yes| NONE["→ NONE\n(injury handled internally)"]
    G -->|No| H{"Mood recovery\nneeded?"}
    H -->|Yes| RECOVER
    H -->|No| I{"Eligible for\nextra racing?"}
    I -->|Yes| RACE
    I -->|No| TRAIN["→ TRAIN"]
```

**Priority explanations:**

1. **Mandatory/Scheduled Race:** If the game shows a race ribbon or scheduled race label, the bot must race. No choice here.
2. **Racing popup:** If a previous race selection triggered a popup that wasn't fully resolved, continue with racing.
3. **Force Racing:** User setting that bypasses all other logic and forces racing every turn.
4. **Maiden Race:** The first race of the campaign must be completed before regular training resumes.
5. **Pre-Summer Prep (June Late):** On the last turn before Summer training, the bot ensures energy is high (≥70%) and mood is Great. If energy is low, it rests. If mood is low, it recovers mood. If both are fine, it trains Wit (which recovers some energy in preparation for Summer Training).[^1]

[^1]: Wit is chosen as the "throwaway" training because it recovers some energy, helping the trainee enter Summer Training in better condition.
6. **Fan/Trophy Requirements:** If the game requires a minimum fan count or trophy count, the bot prioritizes racing to meet it.
7. **Injury Check:** If an injury is detected, the bot handles it (usually by resting). This check is **skipped during Finale turns** since those races are mandatory.
8. **Mood Recovery:** If mood has dropped to Normal or below, the bot recovers before training (bad mood penalizes training gains).
9. **Extra Racing:** If the bot is eligible for extra races (based on farming fans, racing plan, or smart racing logic), it races.
10. **Default: Train.** If nothing else applies, the bot trains.

> [!NOTE]
> **Trackblazer override:** Before calling the base decision logic, Trackblazer's `decideNextAction()` first checks for **Irregular Training** — evaluating whether a high-value training opportunity exists that's worth skipping a race for. See [Section 11.6](#116-irregular-training) for details.

---

## 6. Training System

The training system analyzes all 5 training options (Speed, Stamina, Power, Guts, Wit), scores them, and selects the best one.

### 6.1 Training Analysis Pipeline

When `analyzeTrainings()` is called:

1. **Iterate all 5 stats:** For each stat, the bot clicks the corresponding training tab button.
2. **Stat gain detection per training:**
   - Main stat gain and sub-stat gains (detected via template matching or optionally **YOLO** — see below)
   - Failure chance percentage
   - Relationship bar colors (blue, green, orange) for support card characters present
   - Rainbow count (number of rainbow indicators)
   - Skill hints available
3. **Results are cached** for the current turn in `cachedAnalysisResults` to avoid re-reading if the training screen is visited multiple times.
4. **Filtering:** Trainings exceeding the maximum failure chance threshold (default 20%) are excluded, unless risky training mode or Good-Luck Charm overrides are active.

**YOLO Stat Detection:** When `enableYoloStatDetection` is enabled, stat gain digits are detected using a **YOLOv8 nano** model (`best.onnx`) instead of template matching. The model is trained to detect 11 classes (digits 0–9 and the '+' symbol) in small 130x50 pixel crop regions for each stat. It runs via ONNX Runtime with a confidence threshold of 0.8 and IoU threshold of 0.45 for NMS. The `YoloDetector` is loaded once as a singleton and kept in memory. Both detection methods coexist — the setting controls which one is used at runtime. The YOLO training pipeline and model export tools live in the [yolo/](yolo/) directory.

### 6.2 Scoring Algorithm

Each training option receives a weighted score from `calculateRawTrainingScore()`:

$$\text{Score} = \bigl(\text{StatEfficiency} \times w_{\text{stat}} + \text{Relationship} \times w_{\text{rel}} + \text{Misc} \times w_{\text{misc}}\bigr) \times \text{RainbowMultiplier}$$

| Component | Weight (with relationships) | Weight (without) | What It Measures |
|-----------|---------------------------|-------------------|-----------------|
| Stat Efficiency | 60% | 70% | How much the stat gain moves toward the target for the trainee's distance |
| Relationship | 10% | 0% | Support card relationship bar progress (blue = 2.5, green = 1.0, orange = 0.0) |
| Misc | 30% | 30% | Mood gain, bond progress, skill hints, and other bonuses |

**Rainbow Multiplier:**
- If rainbow training bonus is enabled: **2.0x**
- If rainbow training bonus is disabled but rainbows are present: **1.5x**
- No rainbows: **1.0x**

Rainbow training is heavily favored because it improves overall stat ratio balance. Applied only from Classic Year onward.

> [!IMPORTANT]
> **Stat Cap Awareness:** If a stat is at or above the effective cap (absolute cap - 100 buffer), training for that stat scores **0** and is skipped. The one exception is a **one-time rainbow allowance** — a stat can be trained past the buffer if it's a rainbow training and that stat hasn't used this allowance yet.

### 6.3 Special Training Modes

<details>
<summary><strong>Risky Training</strong></summary>

When enabled, the bot will accept trainings with higher failure chances if the stat gain is large enough:
- **Minimum stat gain:** Configurable (default 20)
- **Maximum failure chance:** Configurable (default 30%)

This overrides the normal failure chance filter for trainings that meet both thresholds.
</details>

<details>
<summary><strong>Rainbow Training Bonus</strong></summary>

When enabled, rainbow trainings receive a 2.0x score multiplier instead of 1.5x. This makes the bot more aggressively pursue rainbow training opportunities, which provide balanced stat gains across multiple categories.
</details>

<details>
<summary><strong>Train Wit During Finale</strong></summary>

During Finale turns (73–75), if the trainee's energy is too low for optimal training, the bot normally rests. With this setting enabled, it **trains Wit instead of resting**, since:
- Energy recovery is less valuable when only 1–3 turns remain
- Wit training typically has low failure chance
- On turn 75 (the final turn), resting is completely pointless, so Wit is always forced
</details>

<details>
<summary><strong>Skill Hint Prioritization</strong></summary>

When enabled, the bot adds bonus weight to trainings that offer skill hints, making it more likely to choose trainings where support cards are offering learnable skills.
</details>

### 6.4 Training Configuration Summary

| Setting | Default | Effect |
|---------|---------|--------|
| Stat Prioritization | Speed, Stamina, Power, Guts, Wit | Order determines scoring weight for stat gains |
| Training Blacklist | (empty) | Stats in this list are never selected |
| Max Failure Chance | 20% | Trainings above this are filtered out |
| Disable on Maxed Stat | true | Skip training for stats at/above buffer |
| Rainbow Training Bonus | false | 2.0x multiplier for rainbow trainings |
| Train Wit During Finale | false | Wit training instead of resting during finale |
| Risky Training | false | Accept higher failure for larger gains |

---

## 7. Racing System

The racing system handles race detection, selection, execution, and result processing.

### 7.1 Race Types

| Type | Detection | When |
|------|-----------|------|
| **Mandatory** | `IconRaceDayRibbon` or `IconGoalRibbon` | Game-forced races (Debut, Finale, goal races) |
| **Scheduled** | `LabelScheduledRace` | Races from the user's in-game agenda |
| **Extra** | Eligibility check | Fan farming, racing plan, or smart racing |
| **Maiden** | First race flag | Must be completed once before regular training |

### 7.2 Extra Race Eligibility

The bot determines if extra races should be run via `checkEligibilityToStartExtraRacingProcess()`:

- **Force Racing:** Always race if the setting is enabled.
- **Fan/Trophy Requirements:** Race to meet minimum thresholds shown on screen.
- **Racing Plan:** User-defined schedule of specific races to enter on specific turns.
- **In-Game Race Agenda:** Follows the agenda set within the game itself.
- **Fan Farming:** Enters races based on a configurable interval (`daysToRunExtraRaces`).
- **Smart Racing / Look-Ahead:** Checks upcoming turns for higher-quality races and may defer racing to a better opportunity.

> [!IMPORTANT]
> **Trackblazer** bypasses smart racing logic entirely and races as aggressively as possible, only stopping for summer, finals, or when the consecutive race limit is reached.

### 7.3 Race Selection

When the bot decides to race:

1. **Open the race list** and scan available races.
2. **Database lookup:** Each detected race name is matched against an internal race database keyed by turn number. The database contains grade, fan reward, surface, and distance information.
3. **Grade priority:** G1 > G2 > G3 > OP > Pre-OP. Higher-grade races are always preferred.
4. **Filtering:** Races can be filtered by minimum fan threshold, preferred terrain, preferred grades, and preferred distances.
5. **Selection:** The highest-priority race that passes all filters is selected.

### 7.4 Race Execution

Once a race is selected:

1. **Strategy Selection:** The bot selects a running strategy (Front Runner, Stalker, Betweener, or Chaser) based on the trainee's aptitudes.
2. **Skip or Manual:** If the "skip" button is available, the bot skips the race animation. Otherwise, it watches and fast-forwards.
3. **Retries:** If a race is lost and retries are enabled, the bot can retry the race (free retry available once per campaign if enabled).
4. **Complete Career on Failure:** If a mandatory race is lost and this setting is enabled, the bot continues the campaign anyway rather than stopping.

> [!CAUTION]
> Losing a mandatory race without `enableCompleteCareerOnFailure` will **stop the bot entirely**. If you want fully unattended runs, make sure this setting is enabled.

---

## 8. Training Events

Training events are popup screens that appear after training or racing, offering the player a choice between 2+ reward options.

### 8.1 Event Detection

1. The bot detects the training event screen via template matching (`IconTrainingEventHorseshoe`).
2. **OCR reads** the event title and the character or support card name.
3. **Fuzzy string matching** (Jaro-Winkler algorithm) compares the detected text against the event database to identify which event this is and what each option rewards.

### 8.2 Override System

The bot checks for overrides in this priority order:

| Priority | Override Type | Description |
|----------|--------------|-------------|
| 1 | **Special Event** | Hardcoded overrides for game-critical events (New Year's, Shrine Visit, etc.) |
| 2 | **Character Event** | User-configured choice for a specific character's events |
| 3 | **Support Event** | User-configured choice for a specific support card's events |
| 4 | **Scenario Event** | User-configured choice for scenario-specific events |
| 5 | **Default Scoring** | Weighted algorithm (see below) |

If any override matches, its configured option is selected immediately without scoring.

### 8.3 Default Scoring

When no override applies, each option receives a weight score based on its rewards:

| Reward Type | Weight | Notes |
|-------------|--------|-------|
| "Can start dating" | +1000 | Extremely high priority — unlocks dating events |
| "Event chain ended" | -300 | Penalty — ending an event chain loses future rewards |
| "(Random)" | -10 | Small penalty for uncertain outcomes |
| "Randomly" | +50 | Mild bonus for partially random outcomes |
| Energy gain | value × multiplier | Multiplier scales with current energy[^2] (4x at <30%, 3x at <50%, 2x at <70%, 0x at ≥90%). If "Prioritize Energy" is enabled, multiplier is 100x |
| Mood gain | 80–150 | Higher weight when mood is lower (150 at Awful, 0 at Great). Mood loss: -150 |
| Bond gain | +20 | Bond loss: -20 |
| Skill hint | +25 | Learning a new skill |
| Positive status | +25 | Gaining a beneficial condition |
| Negative status | -25 | Gaining a harmful condition |
| Stat gain (priority stat) | value + 10–50 bonus | Bonus based on stat priority rank (1st: +50, 2nd: +40, 3rd: +30, 4th: +20, 5th: +10) |
| Stat gain (other) | raw value | No priority bonus |
| Skill points | raw value | Direct skill point gains |

[^2]: The energy multiplier is intentionally aggressive — at low energy, even small energy gains receive high scores because training at low energy carries significant failure risk.

The option with the **highest total weight** is selected.

> [!TIP]
> You can override the bot's event choices for specific characters, support cards, or scenario events in the **Training Event Settings** page. Overrides take priority over the scoring algorithm, letting you force a specific option for events you know are better than what the bot would calculate.

---

## 9. Scenario: URA Finale

URA Finale is the **simplest scenario** — it uses almost entirely the base `Campaign` logic with only one minor override:

- **`openFansDialog()`:** Uses a different button location (`ButtonHomeFansInfo` in the top half of the screen) to open the fans information panel. This is the only code difference from the base campaign.

Everything else — decision logic, training, racing, events, and finale handling — uses the standard base implementation described in sections 3–8.

> [!TIP]
> If you're new to the bot, URA Finale is the best scenario to start with since its behavior is entirely described by the shared systems in sections 3–8 with no scenario-specific complexity.

**Finale behavior (turns 73–75):**
- All 3 finale races (Qualifier, Semi-Final, Finals) are **mandatory**.
- **Injury checks are skipped** during the finale since the races must be run regardless.
- **Consecutive race warnings** are automatically confirmed.
- If `trainWitDuringFinale` is enabled, the bot trains Wit instead of resting between finale races.
- If `enableStopBeforeFinals` is enabled, the bot stops at turn 72 so the user can manually handle skill purchases or other preparations.
- If the `preFinals` skill plan is enabled, the bot automatically purchases skills on turn 72 before entering the finale.

---

## 10. Scenario: Unity Cup

Unity Cup adds a unique opponent selection and race system on top of the base campaign.

### 10.1 Tutorial Handling

The first time a Training Event screen appears, the bot checks for the Unity Cup tutorial header (`IconUnityCupTutorialHeader`). If detected, it selects the second option to close it and sets a flag to skip this check on subsequent turns.

### 10.2 Opponent Selection

When a Unity Cup race is triggered, the bot enters an opponent selection screen with 3 opponents to choose from:

```mermaid
stateDiagram-v2
    [*] --> TapOpponent1
    TapOpponent1 --> AnalyzePredictions1: Confirmation dialog opens
    AnalyzePredictions1 --> RaceConfirmed: ≥3 double circles ✓
    AnalyzePredictions1 --> TapOpponent2: < 3 double circles ✗

    TapOpponent2 --> AnalyzePredictions2: Confirmation dialog opens
    AnalyzePredictions2 --> RaceConfirmed: ≥3 double circles ✓
    AnalyzePredictions2 --> TapOpponent3: < 3 double circles ✗

    TapOpponent3 --> AnalyzePredictions3: Confirmation dialog opens
    AnalyzePredictions3 --> RaceConfirmed: ≥3 double circles ✓
    AnalyzePredictions3 --> ForceFallback: < 3 double circles ✗

    ForceFallback --> RaceConfirmed: Force select Opponent 2
    RaceConfirmed --> [*]
```

**How it works:**

1. The bot detects 3 opponent positions via `LabelUnityCupOpponentSelectionLaurel`.
2. Starting with Opponent 1, it taps the opponent and then the "Select Opponent" button.
3. A confirmation dialog opens showing race predictions. The bot counts **double circle icons** (`IconDoubleCircle`) in the middle region of the screen.
4. If **3 or more double circles** are found → the matchup is favorable. The bot confirms the selection.
5. If fewer than 3 → the bot closes the dialog and tries the next opponent.
6. **Fallback:** If all 3 opponents fail the threshold, the bot **forces selection of Opponent 2** as a compromise.

> [!CAUTION]
> The fallback always picks Opponent 2 regardless of prediction quality. If all opponents are unfavorable, the race may be lost.

### 10.3 Race Execution

After selecting an opponent:

- The bot checks if the "See All Race Results" button is **locked** (via `checkDisabled()`).
  - **Locked:** The bot clicks "Watch Main Race" and runs the race manually with retries.
  - **Unlocked:** The bot clicks the skip button to instantly see results.
- The race sequence ends when `IconUnityCupRaceEndLogo` is detected, at which point the bot clicks "Next" to return to the main screen.
- **Finals race** (`ButtonUnityCupRaceFinal`): When racing Team Zenith in the finals, the bot sets `bIsFinals = true` which auto-confirms the opponent dialog without prediction analysis.

### 10.4 Training Scoring

Unity Cup uses a modified training scoring mode during Junior and Classic years that factors in the **Spirit Gauge** mechanic:

- **Spirit Burst Bonus:** +800 base + 400 per additional gauge ready to burst
- **Facility Preference:** +200 for Speed/Wit facilities; conditional for Stamina/Power/Guts
- **Gauge Fill Bonus:** +300 base + 100 per fillable gauge, with +200 bonus in the early game
- **Relationship:** 1.5x scaled relationship score

From Senior Year onward, Unity Cup switches to the standard stat efficiency scoring described in [Section 6.2](#62-scoring-algorithm).

---

## 11. Scenario: Trackblazer

Trackblazer is the **most complex scenario**, adding a shop system, item management, consecutive race tracking, irregular training evaluation, and custom race selection.

### 11.1 Overview and Flow Differences

Trackblazer overrides the decision engine to add several scenario-specific checks before falling through to the base logic:

```mermaid
flowchart TD
    Start["Trackblazer\ndecideNextAction()"] --> Summer{"Is it\nSummer?"}
    Summer -->|Yes| TRAIN["→ TRAIN\n(Summer training)"]
    Summer -->|No| Finale{"Finale turns\n73-75?"}
    Finale -->|Yes| TRAIN2["→ TRAIN\n(Finale training)"]
    Finale -->|No| EnergyGuard{"Energy ≤ 10% AND\n3+ consecutive races\nAND no Charm?"}
    EnergyGuard -->|Yes| REST["→ REST\n(avoid -30 stat penalty)"]
    EnergyGuard -->|No| Irregular{"Irregular Training\nenabled + not checked?"}
    Irregular -->|Yes| EvalTraining["Open training screen\nAnalyze all 5 trainings"]
    EvalTraining --> ValidFound{"High-value training\nfound?"}
    ValidFound -->|Yes| TRAIN3["→ TRAIN\n(irregular training)"]
    ValidFound -->|No| BackOut["Close training screen\nMark as checked"]
    BackOut --> BaseDecision["super.decideNextAction()\n(base priority waterfall)"]
    Irregular -->|No| BaseDecision
```

> [!IMPORTANT]
> **Key difference from base Campaign:** During Finale, Trackblazer **trains** instead of racing. The 3 finale races are still mandatory, but between them the bot prioritizes training over rest (unlike URA Finale which follows the standard logic).

**Race fallback behavior:** If a non-mandatory race attempt fails (e.g. the consecutive race limit is reached after selecting a race), Trackblazer backs out of the race dialogs and falls back to training for the turn instead of erroring out. Mandatory races are not affected — those always proceed normally.

### 11.2 Shop System

The Trackblazer shop allows purchasing items with coins earned from races. The bot visits the shop periodically and buys items according to a priority list.

#### Shop Visit Triggers

- **After qualifying races:** When a race of the configured grade (default: G1, G2, G3) is completed and the shop check frequency counter is reached.
- **Shop check frequency:** Configurable (default 3). The bot visits the shop every N turns after the first qualifying race, not after every single race.
- **First-time check:** The bot performs an initial shop check the first time it has the opportunity.

#### Buying Priority List

Items are purchased in strict priority order. The bot buys the highest-priority affordable item first, then moves down the list:

| Tier | Items | Purpose |
|------|-------|---------|
| **1. Critical** | Good-Luck Charm, Master Cleat Hammer, Artisan Cleat Hammer, Glow Sticks, Royal Kale Juice, Grilled Carrots, Rich Hand Cream, Miracle Cure | Core race/training items + emergency heals |
| **2. Stats** | Speed/Stamina/Power/Guts/Wit Scrolls (+15), then Manuals (+7) | Direct stat boosts |
| **3. Energy + Mood** | Vita 65, Vita 40, Vita 20, Berry Sweet Cupcake, Plain Cupcake | Energy restoration + mood recovery |
| **4. Training Effects** | Empowering/Motivating Megaphone, Ankle Weights (top 3 stats), Coaching Megaphone, Reset Whistle | Training bonuses |
| **5. Bad Condition Heals** | Fluffy Pillow, Pocket Planner, Smart Scale, Aroma Diffuser, Practice Drills DVD | Heal negative statuses |
| **6. Training Facilities** | Training Applications (top 3 stats) | Facility level boosts |
| **7. Other Energy** | Energy Drink MAX, Energy Drink MAX EX | Additional energy items |
| **8. Good Conditions** | Pretty Mirror, Reporter's Binoculars, Master Practice Guide, Scholar's Hat | Positive status effects |

**Inventory limits:** Most items are capped at 5 copies. Condition-related items (good/bad) are typically capped at 1 (except Rich Hand Cream and Miracle Cure at 5).

> [!WARNING]
> **OCR coin reading:** The bot reads the shop coin count via OCR. If OCR reads 0 coins (likely an OCR error), the bot enters a "force purchase" mode where it attempts purchases anyway. This prevents a misread from blocking all shop activity for the rest of the run.

### 11.3 Item Usage System

**Items are only available from turn 13 onward** (after Pre-Debut). The item dialog is not accessible before that point. All item usage described below is gated on `date.day >= 13`.

The bot opens the Training Items dialog when **any** of these conditions are met:

| Trigger Condition | Why |
|-------------------|-----|
| First inventory sync not yet performed | Need to scan the full item list to populate the internal inventory cache |
| Energy ≤ threshold (default 40%) and energy items exist | Low energy hurts training and race performance |
| Mood ≤ Normal and energy < 70% and cupcakes exist | Low mood penalizes training gains |
| Bad condition active and heal items exist | Bad conditions block certain actions |
| Stat items (Scrolls/Manuals/Notepads) exist | Direct stat gains — always used when available |
| Megaphone exists, none currently active, and a training is selected | Training bonus multiplier |
| Ankle Weights exist for the selected training stat | Training stat bonus |
| Good-Luck Charm exists, not used today, failure chance ≥ 20%, and a training is selected | Prevent training failure |

If **none** of these conditions are met and the inventory has already been synced, the bot skips opening the dialog entirely to save time.

Once the dialog is open, the bot scrolls through the full item list, performing **inventory sync** and **inline item usage** in a single pass. Each item encountered is evaluated against the rules below. If the cached inventory already accounts for every item of interest, the scan exits early.

> [!TIP]
> The single-pass design means the bot opens the Training Items dialog **at most once per turn** (plus once for race items if racing). After the first full scan, subsequent turns use the cached inventory to skip items that aren't needed, enabling early exit from the scroll loop.

#### Complete Item Reference

Below is every item in the Trackblazer shop, organized by category. For each item: what it does, when the bot uses it, and when it does not.

---

<details>
<summary><strong>Stats — Notepads, Manuals, and Scrolls</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| Speed/Stamina/Power/Guts/Wit **Notepad** | 10 coins | +3 to the respective stat |
| Speed/Stamina/Power/Guts/Wit **Manual** | 15 coins | +7 to the respective stat |
| Speed/Stamina/Power/Guts/Wit **Scroll** | 30 coins | +15 to the respective stat |

**When used:** Immediately on sight during the inventory scan pass, every turn. The bot clicks the "+" button up to **5 times per item** (consuming up to 5 copies in one pass). These are "quick-use" items — no conditional logic is needed.

**When NOT used:**
- The stat is already at its cap.
- Turn is before 13 (Pre-Debut).

**Shop priority:** Scrolls are purchased before Manuals. Notepads are **not** included in the default buy priority list — they are only purchased if the bot happens to have leftover coins after everything else. However, if the user already has Notepads in inventory, they will still be used.

</details>

<details>
<summary><strong>Energy — Vita 20, Vita 40, Vita 65</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **Vita 20** | 35 coins | Energy +20 |
| **Vita 40** | 55 coins | Energy +40 |
| **Vita 65** | 75 coins | Energy +65 |

**When used:** Only when **all** of these conditions are true:
1. Energy is at or below the energy threshold (default 40%)
2. A **Good-Luck Charm is NOT being used this turn** (see [Charm interaction](#good-luck-charm--energy-item-interaction) below)
3. The item is the **optimal choice** according to the greedy energy algorithm

**The greedy energy algorithm (`isBestEnergyItemToUse()`):**
1. Collect all available energy items (from inventory + items not yet scanned in this pass).
2. Sort by gain descending (65 → 40 → 20).
3. Greedily pick items that fit without exceeding 100% energy.
4. If the current item was in the picked set → use it. Otherwise → skip it.

**Example:** Trainee has 35% energy with Vita 65, Vita 40, and Vita 20 available.
- 35 + 65 = 100 → pick Vita 65. Remaining space: 0.
- Vita 40 and Vita 20 don't fit → skip them.
- Result: Use only Vita 65.

**Example:** Trainee has 50% energy with Vita 65, Vita 40, and Vita 20 available.
- 50 + 65 = 115 → exceeds 100, skip Vita 65.
- 50 + 40 = 90 → pick Vita 40. Remaining space: 10.
- 90 + 20 = 110 → exceeds 100, skip Vita 20.
- Result: Use only Vita 40.

**When NOT used:**
- Energy is above the threshold (default 40%).
- A Good-Luck Charm is being used this turn (Charm sets failure to 0%, making energy irrelevant for training — using energy items would waste them since the energy cost is deducted after training).
- Using this item would overshoot 100% when a smaller item would be more efficient.

**Special Royal Kale Juice priority:** When energy ≤ 20%, the bot checks if Royal Kale Juice is available. If it is, all Vita items are skipped in favor of Kale Juice, since any Vita used first would be partially wasted by the Kale Juice's full restore.

</details>

<details>
<summary><strong>Energy — Royal Kale Juice</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **Royal Kale Juice** | 70 coins | Energy set to 100%, Motivation -1 |

Royal Kale Juice is handled separately from Vita items because of its mood penalty.

**When used:** Only when **all** of these conditions are true:
1. A **Good-Luck Charm is NOT being used this turn**
2. The greedy energy algorithm selects it as the best choice
3. **AND** at least one of these "mood safety" conditions is met:
   - Energy is critically low (≤ 20%) — used as a **last resort** regardless of mood
   - Mood recovery items (Cupcakes) are available in inventory to offset the -1 mood
   - Mood is already Awful (can't get worse)

**When NOT used:**
- Energy is above 20% and no cupcakes are available and mood is not Awful (the -1 mood penalty has no safety net).
- A Good-Luck Charm is being used this turn.
- A Vita item is more efficient (e.g., at 60% energy, Vita 40 gives exactly what's needed without a mood penalty).

**Side effects:** After use, the trainee's mood is decremented by 1 level (e.g., Great → Good). The bot tracks this internally.

</details>

<details>
<summary><strong>Energy — Energy Drink MAX and Energy Drink MAX EX</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **Energy Drink MAX** | 30 coins | Maximum energy +4, Energy +5 |
| **Energy Drink MAX EX** | 50 coins | Maximum energy +8 |

**When used:** These are marked as **quick-use** items. They are used immediately on sight during the inventory scan, every turn they are available. Energy Drink MAX also adds +5 to current energy as a side effect.

**When NOT used:**
- Turn is before 13.

**Shop priority:** These are in Tier 7 (low priority) — purchased only after most other items. The max energy increase is a long-term investment that pays off over many turns.

</details>

<details>
<summary><strong>Mood — Berry Sweet Cupcake and Plain Cupcake</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **Berry Sweet Cupcake** | 55 coins | Motivation +2 |
| **Plain Cupcake** | 30 coins | Motivation +1 |

**When used:** Only when **all** of these conditions are true:
1. Mood is Normal or below (≤ Normal)
2. Energy is below 70% (if energy is high enough, the bot prefers to train without mood recovery)

The first cupcake encountered during the scan is used. Berry Sweet Cupcake raises mood to Good; Plain Cupcake raises it to Normal (from the decremented state).

**When NOT used:**
- Mood is Good or Great.
- Energy is ≥ 70% (high energy means training will succeed well enough despite mood).

**Note — Interaction with Royal Kale Juice:** Cupcakes serve as a "safety net" for Kale Juice usage. The bot checks for cupcake availability before using Kale Juice at moderate energy levels (21–40%) because the Kale Juice would drop mood by 1. If cupcakes are available to compensate, Kale Juice is considered safe to use.

</details>

<details>
<summary><strong>Bond — Yummy Cat Food and Grilled Carrots</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **Yummy Cat Food** | 10 coins | Yayoi Akikawa's bond +5 |
| **Grilled Carrots** | 40 coins | All support card bonds +5 |

**When used:** These are marked as **quick-use** items. Used immediately on sight during the inventory scan, every turn.

**When NOT used:**
- Bond is already maxed for all relevant characters.

**Shop priority:** Grilled Carrots is in Tier 1 (critical) because +5 bond to all support cards is extremely valuable early. Yummy Cat Food is not in the default priority list.

</details>

<details>
<summary><strong>Good Conditions — Pretty Mirror, Reporter's Binoculars, Master Practice Guide, Scholar's Hat</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **Pretty Mirror** | 150 coins | Gain "Charming ○" status |
| **Reporter's Binoculars** | 150 coins | Gain "Hot Topic" status |
| **Master Practice Guide** | 150 coins | Gain "Practice Perfect ○" status |
| **Scholar's Hat** | 280 coins | Gain "Fast Learner" status |

**When used:** These are marked as **quick-use** items. Used immediately on sight during the inventory scan.

**When NOT used:**
- The status effect is already active.

**Shop priority:** Tier 8 (lowest priority). These are expensive and only purchased after all other categories are covered. The bot caps inventory at 1 copy each since each status effect can only be active once.

</details>

<details>
<summary><strong>Heal Bad Conditions — Fluffy Pillow, Pocket Planner, Rich Hand Cream, Smart Scale, Aroma Diffuser, Practice Drills DVD</strong></summary>

| Item | Price | Heals |
|------|-------|-------|
| **Fluffy Pillow** | 15 coins | Night Owl |
| **Pocket Planner** | 15 coins | Slacker |
| **Rich Hand Cream** | 15 coins | Skin Outbreak |
| **Smart Scale** | 15 coins | Slow Metabolism |
| **Aroma Diffuser** | 15 coins | Migraine |
| **Practice Drills DVD** | 15 coins | Practice Poor |

**When used:** During the inventory scan, if the trainee currently has **any negative status** and the corresponding heal item is encountered, it is used.

**When NOT used:**
- The trainee has no negative statuses.
- The specific negative status that this item heals is not currently active.

**Shop priority:** Rich Hand Cream is in Tier 1 (critical) because Skin Outbreak prevents the trainee from entering races, which is devastating in Trackblazer's race-heavy strategy. All other condition heals are in Tier 5. Inventory limit is 1 copy each (except Rich Hand Cream at 5 copies due to its critical nature).

</details>

<details>
<summary><strong>Heal Bad Conditions — Miracle Cure</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **Miracle Cure** | 40 coins | Heal all negative status effects |

**When used:** Same conditions as individual heal items — used when the trainee has any negative status. This is a quick-use item so it's used on sight if any negative status is active.

**When NOT used:**
- The trainee has no negative statuses.

**Shop priority:** Tier 1 (critical). Inventory limit is 5 copies. The bot buys Miracle Cures as general-purpose insurance against bad conditions.

</details>

<details>
<summary><strong>Training Effects — Megaphones (Empowering, Motivating, Coaching)</strong></summary>

| Item | Price | Effect | Duration |
|------|-------|--------|----------|
| **Empowering Megaphone** | 70 coins | Training bonus +60% | 2 turns |
| **Motivating Megaphone** | 55 coins | Training bonus +40% | 3 turns |
| **Coaching Megaphone** | 40 coins | Training bonus +20% | 4 turns |

**When used:** Only when **all** of these conditions are true:
1. No megaphone is currently active (`megaphoneTurnCounter == 0`)
2. A training has been selected for this turn (`trainingSelected != null`)
3. No **better** megaphone is available in inventory

**Megaphone priority logic:** The bot always uses the **best available** megaphone, not just the first one encountered during scanning. When it encounters a megaphone:
- It checks if a higher-tier megaphone exists in inventory that hasn't been scanned yet or is known to be enabled.
- For Motivating Megaphone: skips if Empowering exists.
- For Coaching Megaphone: skips if Empowering or Motivating exists.
- Empowering is always used immediately since nothing is better.

**When NOT used:**
- A megaphone effect is already active (turns remaining > 0). The bot decrements the counter each turn after an action is taken.
- No training is selected this turn (e.g., the bot is racing or resting).
- A better megaphone is available in inventory.

**Duration tracking:** After use, the bot sets `megaphoneTurnCounter` to 2/3/4 depending on the megaphone type. This counter is decremented by 1 at the end of each turn where an action was taken.

</details>

<details>
<summary><strong>Training Effects — Ankle Weights (Speed, Stamina, Power, Guts, Wit)</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **[Stat] Ankle Weights** | 50 coins each | Training bonus +50% for that stat, Energy consumption +20% (one turn) |

**When used:** Only when **all** of these conditions are true:
1. A training has been selected for this turn
2. The Ankle Weights match the **selected training stat** (e.g., Speed Ankle Weights are only used when Speed training is selected)

**When NOT used:**
- No training is selected this turn.
- The Ankle Weights are for a different stat than the selected training.
- Wit Ankle Weights: technically exist in the shop but are **never purchased** by the default priority list (only Speed/Stamina/Power/Guts weights for the top 3 prioritized stats are bought).

> **Warning:** Ankle Weights increase energy consumption by 20% for that turn. The bot does not factor this into the energy threshold check — if the trainee is at low energy and Ankle Weights are used, the training may consume more energy than expected.

**Shop priority:** Tier 4. Only purchased for the top 3 stats in the user's stat prioritization order. For example, if stat priority is Speed > Power > Stamina > Guts > Wit, the bot buys Speed, Power, and Stamina Ankle Weights but not Guts or Wit.

</details>

<details>
<summary><strong>Training Effects — Good-Luck Charm</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **Good-Luck Charm** | 40 coins | Training failure rate set to 0% (one turn) |

**When used:** Only when **all** of these conditions are true:
1. A training has been selected for this turn
2. The selected training's failure chance is **≥ 20%**
3. A Charm has **not already been used** this turn (`bUsedCharmToday == false`)

**When NOT used:**
- No training is selected this turn.
- The training's failure chance is < 20% (not risky enough to warrant a Charm).
- A Charm was already used this turn (only 1 per turn).

<h4 id="good-luck-charm--energy-item-interaction">Good-Luck Charm / Energy Item Interaction</h4>

> **Caution:** This is a critical interaction: **when a Good-Luck Charm is being used (or will be used) this turn, all energy items (Vita 20/40/65 and Royal Kale Juice) are skipped.**

**Why:** The Charm sets training failure to 0%, making the trainee's energy level irrelevant for training success. Energy is deducted *after* training completes, so restoring it beforehand provides no benefit. Using energy items would waste them.

The bot checks for this interaction before evaluating any energy item. It considers a Charm "being used" if:
- A Charm has already been queued this turn, OR
- A Charm is available in inventory AND the current training's failure chance is ≥ 20% (meaning a Charm *will* be queued when the scan reaches it)

**Shop priority:** Tier 1 (critical). This is the **highest priority** purchase in the shop because it enables the bot to safely train high-risk options that would otherwise be filtered out.

**Irregular Training interaction:** When evaluating irregular training, the bot checks if a Charm is available. If so, it passes `ignoreFailureChance = true` to the training analysis, allowing high-failure trainings to be considered as candidates.

</details>

<details>
<summary><strong>Training Effects — Reset Whistle</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **Reset Whistle** | 20 coins | Shuffle support card distribution across training facilities |

**When used:** Only when **all** of these conditions are true:
1. Turn is ≥ 13
2. A Whistle has **not already been used** this turn (`bUsedWhistleToday == false`)
3. The training analysis found **no suitable training** (`trainingSelected == null`)
4. This is **not** an irregular training evaluation (whistles are blocked during irregular checks to prevent wasting them on opportunistic training)

**What happens after use:**
1. The bot confirms usage and closes the item dialog.
2. Support cards are reshuffled across the 5 training facilities.
3. The bot re-runs the full training analysis.
4. If `whistleForcesTraining` is enabled (default: true) and the re-analysis still finds no suitable training, the bot **forces the best available training** even if it doesn't meet normal thresholds.
5. After the whistle, a second item usage pass runs in case the new training recommendation changes which items should be used (e.g., different Ankle Weights).

**When NOT used:**
- A suitable training was already found (the whistle is only for "rescuing" bad turns).
- A Whistle was already used this turn.
- This is an irregular training evaluation (the whistle is too valuable to use on a speculative check).
- Energy recovery is needed (`needsEnergyRecovery` is true) — the problem is low energy, not bad training options, so reshuffling won't help.

**Shop priority:** Tier 4 (training effects). Relatively cheap at 20 coins and very useful as a safety net.

</details>

<details>
<summary><strong>Training Facilities — Training Applications</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **Speed Training Application** | 150 coins | Speed Training Level +1 |
| **Stamina Training Application** | 150 coins | Stamina Training Level +1 |
| **Power Training Application** | 150 coins | Power Training Level +1 |
| **Guts Training Application** | 150 coins | Guts Training Level +1 |
| **Wit Training Application** | 150 coins | Wit Training Level +1 |

**When used:** These are marked as **quick-use** items. Used immediately on sight during the inventory scan. Training level increases are permanent and improve all future training gains for that stat.

**When NOT used:**
- The facility is already at max level.

**Shop priority:** Tier 6. Only purchased for the top 3 stats in the user's stat prioritization order. At 150 coins each, they are expensive but provide a lasting benefit.

</details>

<details>
<summary><strong>Races — Master Cleat Hammer, Artisan Cleat Hammer, Glow Sticks</strong></summary>

| Item | Price | Effect |
|------|-------|--------|
| **Master Cleat Hammer** | 40 coins | Race bonus +35% (one turn) |
| **Artisan Cleat Hammer** | 25 coins | Race bonus +20% (one turn) |
| **Glow Sticks** | 15 coins | Race fan gain +50% (one turn) |

> **Important:** These items are **not** used during the normal training item pass. They have their own dedicated usage flow that triggers on the **Race Prep screen** before a race begins. This includes mandatory races (Finale turns 73–75) and scheduled races via the `onScheduledRacePrepScreen()` hook.

**Master Cleat Hammer — when used:**
- The upcoming race is **G1 grade**.
- The item is available in inventory.
- **Finale conservation:** During turns 73 and 74 (Qualifier and Semi-Final), the bot only uses this item if it has **2 or more copies**, saving the last one for turn 75 (Finals). On turn 75, all remaining copies are used freely.

**Artisan Cleat Hammer — when used:**
- The upcoming race is **G2 or G3 grade**.
- OR the race is G1 but no Master Cleat Hammer is available (fallback).
- The item is available in inventory.
- **Finale conservation:** Same 2-copy rule as Master Cleat Hammer during turns 73–74.

**Glow Sticks — when used:**
- The upcoming race is **G1 grade**.
- The race awards **≥ 20,000 fans**.
- The item is available in inventory.
- **Finale exception:** During all Finale turns (73–75), the 20,000 fan requirement is **waived** — Glow Sticks are used for any G1 race.
- **Finale conservation:** During turns 73 and 74, the bot only uses Glow Sticks if it has **2 or more copies**, saving the last one for turn 75 (Finals). On turn 75, all remaining copies are used freely.

**When NONE of these are used:**
- The race is OP or Pre-OP grade (no items for low-grade races).
- A race item (`bUsedHammerToday`) has already been used this turn.
- Turn is before 13.
- No matching items are available in inventory.

**Shop priority:** Master Cleat Hammer is Tier 1 (critical). Artisan Cleat Hammer is also Tier 1. Glow Sticks is also Tier 1. All three are among the first items the bot purchases.

</details>

### 11.5 Consecutive Race System

Trackblazer tracks how many races the trainee has performed consecutively:

- **Counter:** Incremented after each race. Reset to 0 when the bot rests or recovers mood.
- **Warning at 3+:** After 3 consecutive races, the game shows a warning about potential stat penalties.
- **Energy guard at 3+:** When the counter is ≥ 3 and energy is critically low (0–1%), racing is **blocked** regardless of the configured limit to avoid compounding the -30 stat penalty at zero energy.
- **Grade filtering at 3+:** When the counter is ≥ 3, the bot only accepts **G1, G2, or G3** races. Lower-grade races (OP, Pre-OP) are skipped to avoid wasting the consecutive race penalty on low-value races.
- **Hard limit (default 5):** The bot stops racing entirely when the consecutive count reaches the configured limit (plus 1), unless it's the final turn.
- **OCR tracking:** The bot reads the consecutive race count from the warning dialog via OCR to stay synchronized with the game.

> [!IMPORTANT]
> The counter resets to 0 when the bot **rests** or **recovers mood**, not after training. If the bot trains between races, the consecutive count continues to climb.

### 11.6 Irregular Training

Irregular Training is an optional feature that evaluates whether a high-value training opportunity is worth skipping a race for:

1. **When checked:** On non-mandatory, non-scheduled race days during Classic and Senior years. **Skipped entirely** when energy is ≤ 10% with 3+ consecutive races and no Good-Luck Charm available — the bot rests instead to avoid the -30 stat penalty (see [11.1 flowchart](#111-overview-and-flow-differences)).
2. **Process:**
   - The bot opens the training screen and runs a full analysis of all 5 training options.
   - If a Good-Luck Charm is available, failure chance is ignored during evaluation.
   - The analysis uses an `isIrregularEvaluation = true` flag which applies a higher minimum stat gain threshold (configurable, default 30).
3. **If a valid training is found:** The bot closes the training screen, sets `bIsIrregularTraining = true`, and returns `TRAIN` — effectively "hijacking" a race turn for training.
4. **If no valid training is found:** The bot closes the training screen and falls through to the normal decision logic (which will likely result in racing).
5. **Once per turn:** The check is performed at most once per turn to prevent infinite loops.

> [!TIP]
> Irregular Training pairs well with the **Good-Luck Charm** — with a Charm in inventory, the bot can consider high-failure trainings during irregular evaluation that it would normally skip, unlocking more opportunities to "hijack" race turns.

### 11.7 Race Selection

Trackblazer uses a specialized race selection algorithm (`findSuitableTrackblazerRace()`) that scans the entire race list:

1. **Scan the full list:** Uses `ScrollList` to paginate through all available races across multiple pages.
2. **Identify candidates:** For each race, the bot looks for **double-star prediction icons** (`IconRaceListPredictionDoubleStar`) indicating favorable matchups.
3. **For each double-star race:**
   - Extract the race name via OCR
   - Look up the race in the database by turn number
   - Check for **Rival status** via template matching (`LabelRivalRacer`)
   - Filter by grade based on the current consecutive race count (see [11.5](#115-consecutive-race-system))
4. **Selection priority:**
   - **Rival races first** (these offer bonus rewards)
   - Then by **grade:** G1 > G2 > G3 > OP > Pre-OP
5. **Second pass:** After selecting the winner, the bot scrolls back through the list to find the winner's current screen position and taps it.
6. **Fallback:** If `ScrollList` creation fails, the bot falls back to single-page detection.
