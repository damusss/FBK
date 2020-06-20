package moe.kabii.discord.event.bot

import discord4j.core.`object`.entity.channel.TextChannel
import discord4j.core.event.domain.message.MessageCreateEvent
import discord4j.rest.http.client.ClientException
import discord4j.rest.util.Permission
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.mono
import moe.kabii.LOG
import moe.kabii.data.mongodb.FeatureChannel
import moe.kabii.data.mongodb.GuildConfigurations
import moe.kabii.data.relational.MessageHistory
import moe.kabii.discord.command.*
import moe.kabii.discord.conversation.Conversation
import moe.kabii.CommandManager
import moe.kabii.rusty.Err
import moe.kabii.rusty.Ok
import moe.kabii.structure.*
import org.jetbrains.exposed.sql.transactions.transaction

class MessageHandler(val manager: CommandManager) {
    val mention: Regex by lazy {
        val id = DiscordBot.selfId.long
        Regex("<@!?$id>")
    }

    fun handle(event: MessageCreateEvent) = mono(manager.context + CoroutineName("MessageHandler") + SupervisorJob()) {
        // ignore bots
        if(event.message.author.orNull()?.isBot ?: true) return@mono
        var content = event.message.content

        // only embeds, files, skip any further processing at this time
        if (content.isBlank()) return@mono

            val config = event.guildId.map { id -> GuildConfigurations.getOrCreateGuild(id.asLong()) }.orNull() // null if pm
        val msgArgs = content.split(" ")

        if (config != null) {
            // log messages if this server has an edit/delete log
            val log = config.logChannels()
                .map(FeatureChannel::logSettings)
                .filter { chan -> chan.editLog || chan.deleteLog }
                .any()
            if(log) {
                transaction {
                    MessageHistory.Message.new(event.guildId.get().asLong(), event.message)
                }
            }
        }

        // DISCORD COMMAND HANDLER
        // this sets the prefix for PMs, as long as the guild is legitimate the default prefix is set in GuildConfiguration
        val prefix = config?.prefix ?: ";"
        val suffix = config?.suffix ?: "desu"
        val cmdStr = when {
            msgArgs[msgArgs.size - 1].equals(suffix, ignoreCase = true) -> {
                content = content.substring(0, content.length - suffix.length)
                msgArgs[0]
            }
            msgArgs[0].startsWith(prefix) -> msgArgs[0].substring(prefix.length)
            mention.matches(msgArgs[0]) -> {
                content = content.substring(msgArgs[0].length)
                msgArgs.getOrNull(1)
            }
            else -> null
        }

        val author = event.message.author.orNull() ?: return@mono
        if (cmdStr != null) {
            if(config != null) {
                // dummy command listener
                config.commands.commands.find { dummy -> dummy.command == cmdStr.toLowerCase() }?.run {
                    if (!restrict || event.member.get().hasPermissions(Permission.MANAGE_MESSAGES)) {
                        event.message.channel
                            .flatMap { chan -> chan.createMessage(response) }
                            .awaitSingle()
                    }
                }

                // role command listener
                config.selfRoles.roleCommands.toMap().entries.find { (command, _) -> cmdStr.toLowerCase() == command }
                    ?.also { (command, role) ->
                        val guildRole =
                            event.guild.flatMap { guild -> guild.getRoleById(role.snowflake) }.tryAwait()
                        when(guildRole) {
                            is Err -> {
                                if(guildRole.value is ClientException) {
                                    config.selfRoles.roleCommands.remove(command)
                                    config.save()
                                }
                            }
                            is Ok -> {
                                val member = event.member.get()
                                member.addRole(role.snowflake).success().awaitSingle()
                                event.message.channel.flatMap { chan ->
                                    chan.createEmbed { spec ->
                                        fbkColor(spec)
                                        spec.setAuthor("${member.username}#${member.discriminator}", null, member.avatarUrl)
                                        spec.setDescription("You have been given the **${guildRole.value.name}** role.")
                                    }
                                }.awaitSingle()
                            }
                        }
                }
            }

            val command = manager.commandsDiscord.find { it.aliases.contains(cmdStr.toLowerCase()) }
            if (command != null) {
                val isPM = !event.guildId.isPresent
                // command parameters
                val enabled = if(isPM) true else config!!.commandFilter.isCommandEnabled(command)
                if(!enabled) return@mono
                val guild = event.guild.awaitFirstOrNull()
                val targetID = (guild?.id ?: author.id).asLong()
                val username = author.username
                val guildName = guild?.name ?: username
                val context = if (isPM) "Private" else "Guild"
                LOG.debug("${context}Message#${event.message.id.asLong()}:\t$guildName:\t$username:\t$content")
                val cmdArgs = content.split(" ").filter(String::isNotBlank)
                val args = cmdArgs
                    .drop(1)
                    .filterNot { it.equals("@everyone", ignoreCase = true) }
                val noCmd = args.joinToString(" ")
                LOG.info("Executing command ${command.baseName} on ${Thread.currentThread().name}")
                val chan = event.message.channel.awaitSingle()
                val param = DiscordParameters(this@MessageHandler, event, chan, guild, author, isPM, noCmd, args, command, cmdStr)

                try {
                    if (command.executeDiscord != null)
                        command.executeDiscord!!(param)
                } catch (parse: GuildTargetInvalidException) {
                    param.error("${parse.string} Execute this command while in a guild channel or first use the **setguild** command to set your desired guild target.").subscribe()
                } catch (perms: MemberPermissionsException) {
                    val s = if(perms.perms.size > 1) "s" else ""
                    val reqs = perms.perms.joinToString(", ")
                    param.error("The **${param.alias}** command is restricted. (Requires the **$reqs** permission$s).").subscribe()
                } catch (feat: FeatureDisabledException) {
                    val serverMod = feat.origin.member.basePermissions.map { perms -> perms.contains(Permission.MANAGE_CHANNELS) }.tryAwait().orNull() == true
                    val enableNotice = if(serverMod) " Server moderators+ can enable this feature using **${prefix}config ${feat.feature} enable**." else ""
                    param.error("The **${feat.feature}** feature is not enabled in this channel.$enableNotice").subscribe()
                } catch (ce: ClientException) {
                    // bot is missing permissions
                    when (ce.status.code()) {
                        403 -> {
                            LOG.debug("403: ${ce.message}")
                            if (config == null || chan !is TextChannel) return@mono
                            if (ce.errorResponse.orNull()?.fields?.get("string")?.equals("Missing Permissions") != true) return@mono
                            val botPermissions = chan.getEffectivePermissions(DiscordBot.selfId).awaitSingle()
                            val listMissing = command.discordReqs
                                    .filterNot(botPermissions::contains)
                                    .joinToString("\n")
                            author.privateChannel
                                    .flatMap { pm ->
                                        pm.createEmbed { spec ->
                                            spec.setDescription("Configuration alert: you tried to use **${command.baseName}** in channel ${chan.mention} but I am missing required permissions\n\n**$listMissing\n**")
                                        }
                                    }.subscribe()
                        }
                        else -> {
                            LOG.error("Uncaught client exception in command ${command.baseName} on guild $targetID: ${ce.message}")
                            LOG.debug(ce.stackTraceString) // these can be relatively normal - deleted channels and other weirdness
                        }
                    }
                } catch (e: Exception) {
                    LOG.error("\nUncaught (non-discord) exception in command ${command.baseName} on guild $targetID: ${e.message}\nErroring command: $content")
                    LOG.warn(e.stackTraceString)
                }
            }
            return@mono
        }
        // DISCORD CONVERSATION CALLBACKS
        Conversation.conversations.find { conversation ->
            conversation.criteria.channel == event.message.channelId.asLong()
                    &&  conversation.criteria.user == author.id.asLong()
        }?.test(content)
    }
}