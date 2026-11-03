# Open Arcade

A pocket-sized retro game console for Android. It has 16 fully playable classic, puzzle, arcade and
board games, all with the same 8-bit pixel-art look and an original chiptune soundtrack. It works
**100% offline**: no ads, no accounts and no subscriptions. The only network use is an optional,
Wi-Fi-only check for app updates on GitHub (see [Automatic updates](#automatic-updates)).

## Games

| Puzzle & Logic | Arcade Classics | Board & Strategy |
|---|---|---|
| Minesweeper | Tetromino (falling blocks) | Chess |
| 2048 | Snake | Checkers |
| Sudoku | Maze Muncher (maze chase) | |
| Meowdoku (cat logic puzzle) | Brick Breaker | |
| Nonogram (Picross) | Paddle Ball (table tennis) | |
| Flow Link (connect the dots) | Alien Invasion (fixed shooter) | |
| Unblock (sliding blocks) | | |
| Lights Out | | |

Some games have original names, mazes, sprites and sounds instead of the trademarked originals. The
rules and the gameplay feel stay faithful to the classics.

Highlights:

- **Fresh puzzles every time.** Sudoku puzzles are generated on the device and each one is
  checked to have exactly one solution. Flow Link puzzles are built from a random path that covers
  the whole board, so every one can be solved. Unblock boards are solved by a breadth-first search,
  which tells you the true minimum number of moves (PAR).
- **Meowdoku.** Place one cat in every row, column and colored region, and cats may never touch,
  not even diagonally. Every puzzle has a unique solution that a built-in step-by-step logic solver
  can reach without guessing. That solver also rates difficulty and powers the explained hints.
  Boards grow from 5x5 to 9x9 as you win. You get 3 hearts, X marking (tap or drag), auto-X, undo
  and clear, and an interactive tutorial.
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

## Phones and tablets

Layouts are measured in "art pixels" of about 2.4dp (20% larger on tablets), so buttons and text
keep a comfortable physical size on any screen. Wide screens (tablets, phones in landscape) get
side-by-side layouts: two-column home, game menus, stats and awards; tutorials with the
explanation next to the playable demo; and game boards centered with their controls beside them.

## Automatic updates

Installed copies keep themselves up to date from this repository's GitHub releases. The code is in
`app/src/main/java/com/openarcade/update/`, and its core has no dependency on the game.

- **When it checks:** on each app launch, and only on Wi-Fi. There are no checks or downloads on
  mobile data or offline, and no background services. Players can turn the automatic check off,
  or check by hand, in **Settings → Updates**.
- **How it finds updates:** it calls the GitHub Releases API (`releases/latest`) and compares the
  tag (`v1.2.0`) with the installed `versionName`. GitHub rate limits are respected.
- **Never mid-game:** an available update is offered on the home screen. The dialog shows the
  installed version, the latest version, the changelog (from `CHANGELOG.md`), download progress
  and install instructions.
- **Downloads:** they go to app-private cache storage. Interrupted downloads resume, and they stop
  if Wi-Fi drops.
- **Checks before installing:** an APK must pass every one of these, or it's deleted:
  - its SHA-256 matches GitHub's asset digest or the published `.sha256` file
  - it's the same package
  - its versionCode is newer
  - it's compatible with the device's SDK
  - it's signed with the same certificate as the installed app and with the pinned release
    certificate
- **Installing:** Android's `PackageInstaller` shows its own confirmation screen. The first time,
  Android also asks the player to allow installs from Open Arcade. Nothing bypasses Play Protect
  or any system prompt. Old downloads and abandoned install sessions are cleaned up on launch.

To reuse it in another app, change `AppUpdateConfig` (owner, repository, APK asset pattern,
certificate fingerprint) and keep the release convention:
- a tag `v<versionName>`
- an asset `<Name>-v<version>.apk` plus `.sha256`
- release notes taken from `CHANGELOG.md`

The `Release APK` workflow produces exactly this. It also fails if the tag and `versionName`
disagree.

### Releasing a new version

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add a `## [x.y.z]` section to `CHANGELOG.md`.
3. Push, then run **Actions → Release APK** with tag `vx.y.z`, or push that tag.

## Building

Requirements: JDK 17+ and the Android SDK (API 34).

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # rule-engine unit tests (chess perft, generators, ...)
```

You can also open the project in Android Studio and press Run. The minimum supported version is
Android 5.0 (API 21).

Release signing: the `Release APK` workflow signs with the keystore given by the repository
secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and
`ANDROID_KEY_PASSWORD`. If they are missing, it falls back to a debug key. The CI workflow also
renders screenshots of every screen on phone and tablet sizes and attaches them to the
`ci-screenshots` pre-release.

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
