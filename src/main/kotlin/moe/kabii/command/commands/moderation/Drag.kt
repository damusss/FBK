package moe.kabii.command.commands.moderation

import discord4j.core.`object`.VoiceState
import discord4j.rest.util.Permission
import kotlinx.coroutines.reactive.awaitSingle
import moe.kabii.command.Command
import moe.kabii.command.commands.audio.AudioStateUtil
import moe.kabii.command.verify
import moe.kabii.data.TempStates
import moe.kabii.data.mongodb.MessageInfo
import moe.kabii.discord.audio.AudioManager
import moe.kabii.discord.pagination.ReactionInfo
import moe.kabii.discord.pagination.ReactionListener
import moe.kabii.discord.util.Embeds
import moe.kabii.util.constants.EmojiCharacters
import reactor.core.publisher.Mono

object Drag : Command("drag", "move", "pull") {
    override val wikiPath = "Moderation-Commands#mass-drag-users-in-voice-channels"

    init {
        discord {
            when (args.getOrNull(0)) {
                // drag all pulls all users into user channel
                "all" -> {
                    member.verify(Permission.MANAGE_CHANNELS)
                    val targetChannel = member.voiceState.flatMap(VoiceState::getChannel).awaitSingle()
                    if (targetChannel != null) {
                        send(Embeds.fbk("Moving all users to **${targetChannel.name}**.")).subscribe()
                        target.voiceStates
                                .flatMap(VoiceState::getUser)
                                .flatMap { user -> user.asMember(target.id) }
                                .flatMap { member ->
                                    member.edit().withNewVoiceChannelOrNull(targetChannel.id)
                                }.onErrorResume { _ -> Mono.empty() }
                                .blockLast()
                    } else {
                        send(Embeds.error("**drag all** moves ALL users in this server's voice channels to your current voice channel. You must be in a voice channel that I can move users into, to use the command.")).awaitSingle()
                    }
                }
                // normal drag command pulls users along with the bot when it moves
                else -> {
                    member.verify(Permission.MOVE_MEMBERS)
                    with (TempStates.dragGuilds) {
                        if (contains(target.id)) {
                            remove(target.id)
                            send(Embeds.fbk("Drag operation cancelled.")).awaitSingle()
                        } else {
                            val audio = AudioManager.getGuildAudio(target.id.asLong())
                            val vc = AudioStateUtil.checkAndJoinVoice(this@discord)
                            if(vc is AudioStateUtil.VoiceValidation.Failure) {
                                send(Embeds.error(vc.error)).awaitSingle()
                                return@discord
                            }
                            audio.discord.startTimeout()

                            add(target.id)
                            val message = send(Embeds.fbk("Ready! Move me to drag any users in my voice channel along with me.")).awaitSingle()
                            ReactionListener(
                                    MessageInfo.of(message),
                                    listOf(
                                            ReactionInfo(EmojiCharacters.cancel, "cancel")
                                    ),
                                    author.id.asLong(),
                                    event.client
                            ) { _, _, _ ->
                                if (contains(target.id)) {
                                    remove(target.id)
                                    message.delete().subscribe()
                                    send(Embeds.error("Drag operation cancelled.")).subscribe()
                                }
                                true
                            }.create(message, add = true)
                            Unit
                        }
                    }
                }
            }
        }
    }
}