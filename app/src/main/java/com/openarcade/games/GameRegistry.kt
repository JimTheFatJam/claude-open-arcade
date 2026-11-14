package com.openarcade.games

/**
 * The cartridge slot list. To add a game, create its [GameDef] and append it here — the home
 * screen, library, stats and awards pick it up automatically.
 */
object GameRegistry {
    val all: List<GameDef> by lazy {
        listOf(
            com.openarcade.games.minesweeper.MinesweeperGame,
            com.openarcade.games.g2048.Game2048,
            com.openarcade.games.sudoku.SudokuGame,
            com.openarcade.games.meowdoku.MeowdokuGame,
            com.openarcade.games.nonogram.NonogramGame,
            com.openarcade.games.flow.FlowLinkGame,
            com.openarcade.games.unblock.UnblockGame,
            com.openarcade.games.lightsout.LightsOutGame,
            com.openarcade.games.tetromino.TetrominoGame,
            com.openarcade.games.snake.SnakeGame,
            com.openarcade.games.maze.MazeMuncherGame,
            com.openarcade.games.brick.BrickBreakerGame,
            com.openarcade.games.pong.PaddleBallGame,
            com.openarcade.games.invaders.AlienInvasionGame,
            com.openarcade.games.chess.ChessGame,
            com.openarcade.games.checkers.CheckersGame,
            com.openarcade.games.rollblock.RollBlockGame,
            com.openarcade.games.blockfit.BlockFitGame,
            com.openarcade.games.crate.CratePushGame,
            com.openarcade.games.pipe.PipeRushGame,
        )
    }

    fun byId(id: String): GameDef? = all.firstOrNull { it.id == id }
}
