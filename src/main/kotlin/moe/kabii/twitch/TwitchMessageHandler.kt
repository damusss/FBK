package moe.kabii.twitch

import com.github.twitch4j.chat.events.channel.ChannelMessageEvent
import com.github.twitch4j.common.enums.CommandPermission
import kotlinx.coroutines.launch
import moe.kabii.LOG
import moe.kabii.command.CommandManager
import moe.kabii.command.params.TwitchParameters
import moe.kabii.data.TempStates
import moe.kabii.data.mongodb.GuildConfigurations
import moe.kabii.data.mongodb.guilds.TwitchConfig
import moe.kabii.structure.extensions.reply
import moe.kabii.structure.extensions.stackTraceString

class TwitchMessageHandler(val manager: CommandManager) {
    fun handle(event: ChannelMessageEvent) {
        if(event.user.name.toLowerCase() == "fbkbot") return
        // getGuild discord guild

        manager.context.launch {
            val msgArgs = event.message.split(" ").filterNot { it.isBlank() }
            val isMod = event.permissions.contains(CommandPermission.MODERATOR)

            LOG.debug("TwitchMessage#${event.channel.name}:\t${event.user.name}:\t${event.message}")

            // discord-twitch verification- check all messages even if not linked guild
            val verification = TempStates.twitchVerify.entries.find { (id, _) ->
                id == event.channel.id.toLong()
            }
            if (verification != null) {
                if (event.message.trim().toLowerCase().startsWith(";verify") && event.permissions.contains(CommandPermission.MODERATOR)) {
                    val targetConfig = verification.value
                    targetConfig.options.linkedTwitchChannel =
                        TwitchConfig(verification.key)
                    targetConfig.save()
                    TempStates.twitchVerify.remove(verification.key)
                    event.reply("Chat linked! Hello :)")
                }
                // always return, don't process normal commands if server is not verified
                return@launch
            }

            val guild = GuildConfigurations.getGuildForTwitch(event.channel.id.toLong())
            if (guild == null) return@launch // shouldn't happen but if we are in non-verified channel, ignore the message

            // custom command handling
            guild.customCommands.commands.find { it.command == msgArgs[0] }?.run {
                if (!restrict || isMod) {
                    event.reply(response)
                }
            }

            // twitch command handling
            // if the discord guild has a custom prefix we use that
            val prefix = guild.prefix
            val cmdStr = if (msgArgs[0].startsWith(prefix)) {
                msgArgs[0].substring(prefix.length)
            } else null
            if (cmdStr != null) {
                val command = manager.commandsTwitch.find { it.aliases.contains(cmdStr.toLowerCase()) }
                if (command != null) {
                    val noCmd = event.message.substring(msgArgs[0].length).trim()
                    val args = noCmd.split(" ").filter { it.isNotBlank() }
                    val param = TwitchParameters(event, noCmd, guild, isMod, args)
                    try {
                        if (command.executeTwitch != null) {
                            command.executeTwitch!!(param)
                        }
                    } catch (e: Exception) {
                        LOG.error("Uncaught exception in Twitch command ${command.baseName}\n\"Erroring command: ${event.message}\"")
                        LOG.warn(e.stackTraceString)
                    }
                }
            }
        }
    }
}