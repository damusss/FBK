package moe.kabii.command.commands.audio

import discord4j.core.`object`.entity.channel.VoiceChannel
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import moe.kabii.command.Command
import moe.kabii.discord.audio.AudioManager
import moe.kabii.discord.util.Embeds
import moe.kabii.util.extensions.tryAwait

object BotState : AudioCommandContainer {
    object BotSummon : Command("join") {
        override val wikiPath = "Music-Player#playing-audio"

        init {
            discord {
                val voice = AudioStateUtil.checkAndJoinVoice(this)
                if(voice is AudioStateUtil.VoiceValidation.Failure) {
                    ereply(Embeds.error(voice.error)).awaitSingle()
                }
            }
        }
    }
}