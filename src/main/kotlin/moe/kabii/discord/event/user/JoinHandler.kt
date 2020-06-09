package moe.kabii.discord.event.user

import discord4j.core.`object`.entity.Member
import discord4j.core.`object`.entity.channel.TextChannel
import discord4j.core.event.domain.guild.MemberJoinEvent
import discord4j.rest.http.client.ClientException
import discord4j.rest.util.Color
import moe.kabii.data.mongodb.*
import moe.kabii.discord.event.EventListener
import moe.kabii.discord.invite.InviteWatcher
import moe.kabii.rusty.Err
import moe.kabii.structure.snowflake
import moe.kabii.structure.success
import moe.kabii.structure.tryBlock
import reactor.kotlin.core.publisher.toFlux

object JoinHandler {
    object JoinListener : EventListener<MemberJoinEvent>(MemberJoinEvent::class) {
        override suspend fun handle(event: MemberJoinEvent) = handleJoin(event.member)
    }

    fun handleJoin(member: Member, online: Boolean = true) {
        val config = GuildConfigurations.getOrCreateGuild(member.guildId.asLong())

        // create user log
        val memberID = member.id.asLong()
        val log = config.userLog.users.find { it.userID == memberID }
        if (log == null) {
            val member = GuildMember(true, memberID)
            config.userLog.users.add(member)
            config.save()
        } else if (!log.current) {
            log.current = true
            config.save()
        }

        var error = ""

        // if we can determine the invite used, we can apply specific autoroles
        val invite = if(online) {
            val invites = InviteWatcher.updateGuild(member.guild.block())
            invites.singleOrNull()
        } else null

        // reassign roles if the feature is enabled and the user rejoined. otherwise assign normal joinroles
        // currently intentional that users being reassigned roles don't get the autoroles
        // always remove saved roles on rejoin so nothing is stale in the long run
        val reassign = config.autoRoles.rejoinRoles.remove(memberID)
        val failedRoles = if(reassign != null && config.guildSettings.reassignRoles) {
            reassign.filter { roleID ->
                !member.addRole(roleID.snowflake, "Reassigned roles").success().block()
            }
        } else {
            val configs = config.autoRoles.joinConfigurations.toList()

            if(configs.any { cfg -> cfg.inviteTarget != null && !config.guildSettings.utilizeInvites }) {
                // error if any invite-specific configurations exist but we got 403'd for MANAGE_SERVER
                error += " (A invite-specific role is configured but I am missing permissions to view invite information (Manage Server permission)"
            }

            configs
                .filter { joinConfig ->
                    joinConfig.inviteTarget?.equals(invite) != false // find autoroles for this invite or for all users
                }.filter { joinConfig ->
                    val addedRole = member.addRole(joinConfig.role.snowflake, "Automatic user join role").thenReturn(Unit).tryBlock()
                    if(addedRole is Err) {
                        val error = addedRole.value as? ClientException
                        when(error?.status?.code()) {
                            403 -> return@filter true
                            404 -> config.autoRoles.joinConfigurations.remove(joinConfig) // role deleted,
                        }
                    }
                    false
                }.map(JoinConfiguration::role)
        }

        if(failedRoles.isNotEmpty()) error += " (Bot is missing permissions to add roles: ${failedRoles.joinToString(", ")})"

        config.options.featureChannels.values.toList().toFlux()
            .filter(FeatureChannel::logChannel)
            .map(FeatureChannel::logSettings)
            .filter(LogSettings::joinLog)
            .flatMap { joinLog ->
                member.client.getChannelById(joinLog.channelID.snowflake)
                    .ofType(TextChannel::class.java)
                    .flatMap { channel ->
                        val formatted = UserEventFormatter(member)
                            .formatJoin(joinLog.joinFormat, invite)
                        channel.createEmbed { embed ->
                            embed.setDescription("$formatted$error")
                            embed.setColor(Color.of(6750056))
                            if(joinLog.joinFormat.contains("&avatar")) {
                                embed.setImage(member.avatarUrl)
                            }
                        }
                    }
            }.subscribe()
    }
}