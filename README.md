# Open Arcade

A pocket-sized retro game console for Android. It has 16 fully playable classic, puzzle, arcade and
board games, all with the same 8-bit pixel-art look and an original chiptune soundtrack. It works
**100% offline**: no ads, no accounts, no subscriptions, and no internet permission.

## Games

| Puzzle & Logic | Arcade Classics | Board & Strategy |
|---|---|---|
| Minesweeper | Tetromino (falling blocks) | Chess |
| 2048 | Snake | Checkers |
| Sudoku | Maze Muncher (maze chase) | |
| Meowdoku (cat Sudoku) | Brick Breaker | |
| Nonogram (Picross) | Paddle Ball (table tennis) | |
| Flow Link (connect the dots) | Alien Invasion (fixed shooter) | |
| Unblock (sliding blocks) | | |
| Lights Out | | |

Some games have original names, mazes, sprites and sounds instead of the trademarked originals. The
rules and the gameplay feel stay faithful to the classics.

Highlights:

- **Fresh puzzles every time.** Sudoku and Meowdoku puzzles are generated on the device, and each
  one is checked to have exactly one solution. Flow Link puzzles are built from a random path that
  covers the whole board, so every one can be solved. Unblock boards are solved by a breadth-first
  search, which tells you the true minimum number of moves (PAR).
- **Real opponents.** Chess has a complete rules engine (castling, en passant, promotion,
  stalemate, threefold repetition, the 50-move rule and insufficient material). Its move generator
  is checked against the standard perft reference counts. The computer opponent uses alpha-beta
  search. Checkers enforces compulsory captures, multi-jumps and kings.
- **Arcade feel.** Tetromino uses SRS rotation with wall kicks, a hold slot, a ghost piece, lock
  delay and a 7-bag randomiser. In Maze Muncher each of the four ghosts chases you in its own way,
  with scatter and chase phases.

## Console features

- **Home screen:** featured games (they change daily), continue/recently played, favorites, and
  quick links.
- **Library:** search with an on-screen pixel keyboard (a hardware keyboard also works), plus
  filters for All, Puzzle, Arcade, Board and Favorites.
- **Per-game menu:** difficulty choice, Continue (saved progress), the top-5 local high scores or
  best times, stats and the game's awards.
- **In game:** pause, resume, restart, how-to-play, settings, and quit (your progress is saved).
  Real-time games pause automatically when the app goes to the background.
- **Tutorials:** an illustrated, swipeable introduction appears before your first round. You can
  reopen it any time from the game menu, the pause menu or the `?` button.
- **Progress:** statistics, high scores, best times, win streaks and 60 achievements (8 for the
  arcade overall and 3-4 per game).
- **Audio:** five original chiptune songs that loop seamlessly, plus synthesised sound effects.
  Music and sound-effect toggles sit in the top bar of every screen, including in-game. Separate
  volume controls are in Settings. Your choices are saved.
- **Display:** portrait and landscape layouts (or lock one in Settings), optional CRT scanlines,
  vibration feedback, and a pixel dissolve between screens.

## Building

Requirements: JDK 17+ and the Android SDK (API 34).

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # rule-engine unit tests (chess perft, generators, ...)
```

You can also open the project in Android Studio and press Run. The minimum supported version is
Android 5.0 (API 21).

The app has **no runtime dependencies**. Graphics, the bitmap font, music and sound effects are all
built on the Android framework, so the APK stays small and nothing ever needs downloading.

## Architecture

```
app/src/main/java/com/openarcade/
├── MainActivity.kt / ArcadeView.kt   single Activity + single View render loop
├── core/      ArcadeApp (screen stack, top bar, dialogs, toasts, transitions),
│              Progress (stats & achievements), Gestures
├── gfx/       Pal (palette), PixelFont (5x7 bitmap font), PixelArt (char-map sprites),
│              Gfx (pixel panels, bevels, text), Icons
├── audio/     Synth (chip oscillators), Music (tracker-notation songs), Sfx, AudioEngine (mixer thread)
├── data/      Storage (SharedPreferences: settings, favorites, stats, saves...)
├── ui/        Screen base class, Button/Scroller widgets, GameCard, screens/*
└── games/     Game.kt (GameDef / GameSession / GameHost API), GameRegistry, one package per game
```

Every game is a `GameSession` drawn into a rectangle supplied by the host `GameScreen`. The host
handles the HUD, the action buttons, pause/restart, saving and loading, the results screen,
statistics and tutorials. Games only implement their own rules, input and drawing, using the shared
pixel helpers so they look like part of one console.

### Adding a new game

1. Create `games/yourgame/YourGame.kt` with a `GameSession` subclass:
   - `start(saved)` sets up a new round, or restores one from `saved`.
   - `update(dt)` advances the game, `draw(canvas)` renders it, and `onTouch(e)` handles input.
   - `save()` returns the round's state as JSON (optional).
   - `hud()` and `actions()` are optional extra HUD values and buttons.
   - Call `win(...)` or `lose(...)` to end the round. Call `host.unlock("id")` for achievements.
2. Declare a `GameDef` with its title, category, icon (pixel art), accent colour, music, tutorial
   pages, achievements, and a factory `{ YourSession(it) }`.
3. Add it to `GameRegistry.all`.

That's it. The home screen, library, search, stats, awards, saves and tutorials pick it up
automatically. Adding a new genre is one entry in the `Category` enum.
