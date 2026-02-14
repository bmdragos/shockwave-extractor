# AI Agent Pipeline for Game Preservation

This document describes how to use shockwave-extractor as the first stage of an automated pipeline that ports dead Shockwave/Director games to HTML5.

## Overview

The pipeline has three phases:

```
.dcr binary ──→ [EXTRACT] ──→ [REBUILD] ──→ [VERIFY] ──→ playable HTML5 game
                (automated)   (LLM-driven)  (automated)
```

**Extract** is solved by this toolkit. **Rebuild** is driven by an LLM reading the extracted scripts and assets. **Verify** is automated analysis that catches bugs before a human ever plays.

## Phase 1: Extract

```bash
docker run --rm -v /path:/data shockwave-extractor /data/game.dcr /data/output
```

This produces everything an agent needs:

| Output | Purpose |
|--------|---------|
| `bitmaps/*.png` | All visual assets, correctly decoded |
| `sounds/*.wav` | All audio, headers fixed |
| `decompiled/*/scripts/*.ls` | Lingo source code (game logic) |
| `decompiled/*/scripts/*.lasm` | Lingo bytecode (when source is ambiguous) |
| `decompiled/*/*.json` | Chunk metadata (stage size, tempo, cast member properties) |

The decompiled Lingo scripts are the most important output. They're human-readable pseudocode that describes all game behavior — state machines, physics, collision detection, UI flow.

## Phase 2: Rebuild

Feed the extraction output into an LLM as context:

1. **All Lingo scripts** — these are the game's source code. Every behavior, every state transition, every calculation is here.
2. **Asset manifest** — the bitmap names and dimensions tell you what sprites exist and how big they are.
3. **Stage metadata** — JSON chunks contain the stage dimensions (e.g., 425x290), frame rate (e.g., 15fps), and cast member ordering.
4. **Target architecture** — for most Director games, HTML5 Canvas with a fixed-timestep game loop is the right target. Vanilla JS, single file, no frameworks.

### What the LLM needs to understand

Director games are structured differently from modern game engines:

- **Score-based timeline**: Sprites are placed in numbered channels at specific frame ranges. The Score is like a timeline in After Effects.
- **Behaviors attached to sprites**: Lingo scripts are attached to cast members or sprites. `on enterFrame` runs every frame, `on mouseUp` handles clicks.
- **Frame-based animation**: Everything runs at the movie's tempo (typically 12-30fps). Physics, animation, and game logic all advance per-frame, not per-second.
- **Global state via `the` properties**: `the clickOn`, `the mouseH`, `the mouseV`, `the frame` — these are Director globals the scripts reference constantly.
- **Cast members vs sprites**: A cast member is an asset (bitmap, sound, script). A sprite is an instance placed on stage. Multiple sprites can reference the same cast member.

### Practical prompt structure

```
You are porting a Macromedia Director 7 game to HTML5 Canvas.

Stage: {width}x{height}, {fps} fps
Assets: {count} bitmaps, {count} sounds (see manifest below)

The game's logic is defined by these Lingo scripts:
{all .ls files concatenated}

Rebuild this as a single HTML5 Canvas game using vanilla JavaScript.
Use a fixed timestep of {fps} fps. Load assets from assets/bitmaps/ and assets/sounds/.
Preserve the original game's behavior faithfully.
```

## Phase 3: Verify

This is where automated analysis prevents the bugs that would otherwise require human playtesting. The agent should perform these checks **before any human touches the game**.

### 3a: Visual comparison

If reference screenshots or video of the original game exist:

1. Run the HTML5 port in a headless browser
2. Navigate to key game states (splash screen, gameplay, win/lose screens)
3. Take screenshots at each state
4. Compare against reference frames using a vision model

Things a vision model catches immediately:
- Sprites positioned incorrectly
- Gauge needles pointing the wrong direction
- Buttons in the wrong order
- Missing or miscolored assets
- Wrong background for a given state

### 3b: Sensitivity analysis

For any game with interacting thresholds, timers, or physics — **sweep the input space and check for conflicts**. This is the single most valuable automated check.

#### What to analyze

Every game loop has variables that interact. Enumerate them:

```
Inputs:     speed (0-100), distance (5000→0), tach_angle (0→270+)
Thresholds: blown_engine (tach >= 270 for N frames)
            ramp_trigger (distance <= 0)
            collision    (car bounds overlap pile bounds)
Timers:     grace_period (frames since state entry)
```

#### Sweep methodology

For each combination of input values, simulate the game loop frame-by-frame and check:

1. **Threshold conflicts**: Can two mutually exclusive events trigger on the same frame? If `blown_engine` and `ramp_trigger` can fire simultaneously, which wins? Is that the intended behavior?

2. **Grace period coverage**: If there's a grace period (e.g., collision immunity for N frames after spawning), verify it covers all speeds. Simulate: at speed S, what is the player's position at frame N? Does it clear the obstacle?

   ```
   For speed in [5, 10, 20, 40, 60, 80, 100]:
       Simulate frame-by-frame from state entry
       At each frame, check collision bounds
       Find: minimum frames needed to clear obstacle at this speed
       Assert: grace_period >= minimum frames for ALL speeds
   ```

3. **Accumulator races**: If two values accumulate at different rates (e.g., tach rises 27°/frame but drops 15°/frame), check whether the asymmetry creates unwinnable states. Simulate: at speed S, can the player reach the goal before the accumulator hits its failure threshold?

4. **Boundary conditions**: For any `>=` or `<=` comparison, check the boundary value. Off-by-one in collision detection is the most common bug in game ports.

5. **Jump/projectile physics**: If the game has a jump or projectile, verify the parabola:
   ```
   For each launch_speed:
       Simulate the parabola: y = y0 + vy*t + 0.5*g*t²
       Find: max_height, landing_x, landing_y
       Check: does it clear the obstacles?
       Check: does it land within the safe zone?
       Check: do the distance thresholds for scoring match the actual landing positions?
   ```

#### Example: Junkyard Jump sensitivity analysis

This is the actual analysis that caught real bugs during our port:

```
Problem: Blown engine fires on the same frame as ramp trigger
Analysis:
  At speed 80, tach rises 27°/frame
  Run distance is 5000 units at acceleration 1, deceleration 0.5
  Tach hits 270° at frame ~10
  Distance hits 0 at frame ~112 (at speed 80)
  → No conflict at speed 80 (tach blows long before ramp)

  But at speed 60 with careful throttle management:
  Tach can be at 265° when distance hits 0
  If both checks run in the same frame, code ordering determines winner

Fix: Check ramp trigger BEFORE blown engine. Reward clutch play.

Problem: Collision grace period too short
Analysis:
  Car bounds: {x: carX-30, y: carY-25, w: 60, h: 40}
  Pile bounds: {x: 130+i*65, y: groundY-75, w: 60, h: 70}

  At frame 5 after landing:
    speed 60: carY = 160, pile top = 153 → overlap 7px
    speed 40: carY = 148, pile top = 153 → clear by 5px
    speed 80: carY = 165, pile top = 153 → overlap 12px

  At frame 6 after landing:
    speed 60: carY = 150, pile top = 153 → clear by 3px
    speed 80: carY = 155, pile top = 153 → overlap 2px ← still clips!

  At frame 7 after landing:
    speed 80: carY = 145, pile top = 153 → clear by 8px ← safe

Fix: Grace period must be >= 7 frames to cover speed 80+.
```

### 3c: State machine completeness

Director games are state machines. Extract all states and transitions from the Lingo scripts:

```
States: splash, help, selection, assembly, driving, jump, safe, crash, blown, win
```

Verify:
- Every state has an exit transition
- No state is unreachable
- Win/lose conditions are achievable (simulate: can the player actually reach the win state?)
- Edge states are handled (what happens at 0 money? At max upgrades? After the final level?)

### 3d: Asset coverage

Cross-reference the rebuilt game's asset loading against the extracted assets:

- Every loaded asset path maps to an actual file
- Every extracted asset is used (or explicitly documented as unused)
- Sound files play without distortion (automated: check for DC offset, clipping, silence)
- Bitmap dimensions in code match actual file dimensions

## Putting It Together

A complete automated pipeline:

```bash
#!/bin/bash
# 1. Extract
docker run --rm -v .:/data shockwave-extractor /data/game.dcr /data/extracted

# 2. Rebuild (LLM call with extracted scripts + assets as context)
# → produces game.js + index.html

# 3. Verify
# 3a. Visual comparison (headless browser + vision model)
# 3b. Sensitivity analysis (sweep all threshold interactions)
# 3c. State machine completeness check
# 3d. Asset coverage check

# 4. Iterate on failures (feed verify results back to LLM)
```

The extraction step is deterministic and reliable. The rebuild step is where the LLM does creative work. The verify step catches most bugs that would otherwise need human playtesting. The remaining gap is subjective feel — difficulty tuning, animation timing, audio mixing — which still benefits from human feedback.

## Reference Implementation

[Junkyard Jump](https://github.com/bmdragos/junkyard-jump) was ported using this pipeline (with a human in the loop). The original was a Macromedia Director 7 game from Fox Kids (2000). The extraction produced 59 bitmaps, 10 sounds, and 53 Lingo scripts. The HTML5 rebuild is a single `game.js` file (~2000 lines) running on a 425x290 canvas at 15fps.

See [FINDINGS.md](https://github.com/bmdragos/junkyard-jump/blob/main/FINDINGS.md) in the Junkyard Jump repo for the full reverse engineering walkthrough.
