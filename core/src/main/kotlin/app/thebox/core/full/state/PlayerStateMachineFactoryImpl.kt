package app.thebox.core.full.state

import app.thebox.core.full.PlayerEffects
import app.thebox.core.full.PlayerStateMachine
import app.thebox.core.full.PlayerStateMachineFactory
import app.thebox.core.guide.GuideRows

/** docs/design/m4-player-ux.md §1.2, §2.7: bound in `InputModule`. */
class PlayerStateMachineFactoryImpl : PlayerStateMachineFactory {
    override fun create(effects: PlayerEffects, listRows: GuideRows, nowMs: () -> Long): PlayerStateMachine =
        PlayerStateMachineImpl(effects, listRows, nowMs)
}
