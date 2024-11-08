package moe.kabii.trackers.posts

import discord4j.common.util.Snowflake
import discord4j.core.`object`.entity.Role
import discord4j.core.`object`.entity.channel.GuildChannel
import discord4j.core.`object`.entity.channel.MessageChannel
import discord4j.rest.http.client.ClientException
import kotlinx.coroutines.*
import kotlinx.coroutines.reactor.awaitSingle
import moe.kabii.LOG
import moe.kabii.data.mongodb.GuildConfigurations
import moe.kabii.data.mongodb.guilds.PostsSettings
import moe.kabii.data.relational.posts.TrackedSocialFeeds
import moe.kabii.discord.tasks.DiscordTaskPool
import moe.kabii.instances.DiscordInstances
import moe.kabii.rusty.Err
import moe.kabii.rusty.Ok
import moe.kabii.util.extensions.*
import reactor.kotlin.core.publisher.toMono
import kotlin.reflect.KProperty1

abstract class PostWatcher(val instances: DiscordInstances) {

    protected val taskScope = CoroutineScope(DiscordTaskPool.socialThreads + CoroutineName("PostWatcher") + SupervisorJob())
    protected val notifyScope = CoroutineScope(DiscordTaskPool.notifyThreads + CoroutineName("PostWatcher-Notify") + SupervisorJob())

    /**
     * Object to hold information about a tracked target from the database - resolving references to reduce transactions later
     */
    data class TrackedSocialTarget(
        val db: Int,
        val discordClient: Int,
        val dbFeed: Int,
        val username: String,
        val discordChannel: Snowflake,
        val discordGuild: Snowflake?,
        val discordUser: Snowflake
    ) {
        @RequiresExposedContext fun findDbTarget() = TrackedSocialFeeds.SocialTarget.findById(db)!!
    }

    fun <T> discordTask(timeoutMillis: Long = 6_000L, block: suspend() -> T) = taskScope.launch {
        withTimeout(timeoutMillis) {
            block()
        }
    }

    @RequiresExposedContext
    suspend fun loadTarget(target: TrackedSocialFeeds.SocialTarget) = with(target.socialFeed.feedInfo()) {
        TrackedSocialTarget(
            target.id.value,
            target.discordClient,
            target.socialFeed.id.value,
            displayName,
            target.discordChannel.channelID.snowflake,
            target.discordChannel.guild?.guildID?.snowflake,
            target.tracker.userID.snowflake
        )
    }

    @CreatesExposedContext
    suspend fun getActiveTargets(feed: TrackedSocialFeeds.SocialFeed): List<TrackedSocialTarget>? {
        val targets = propagateTransaction {
            feed.targets.map { t -> loadTarget(t) }
        }
        val existingTargets = targets
            .filter { target ->
                val discord = instances[target.discordClient].client
                // untrack target if discord channel is deleted
                if (target.discordGuild != null) {
                    try {
                        discord.getChannelById(target.discordChannel).awaitSingle()
                    } catch (e: Exception) {
                        if(e is ClientException) {
                            if(e.status.code() == 401) return emptyList()
                            if(e.status.code() == 404) {
                                val feedInfo = feed.feedInfo()
                                LOG.info("Untracking ${feedInfo.site.full} feed '${feedInfo.displayName}' in ${target.discordChannel} as the channel seems to be deleted.")
                                propagateTransaction {
                                    target.findDbTarget().delete()
                                }
                            }
                        }
                        return@filter false
                    }
                }
                true
            }
        return if (existingTargets.isNotEmpty()) {
            existingTargets.filter { target ->
                // ignore, but do not untrack targets with feature disabled
                val clientId = instances[target.discordClient].clientId
                val guildId = target.discordGuild?.asLong() ?: return@filter true // DM do not have channel features
                val featureChannel = GuildConfigurations.getOrCreateGuild(clientId, guildId).getOrCreateFeatures(target.discordChannel.asLong())
                featureChannel.postsTargetChannel
            }
        } else {
            val feedInfo = feed.feedInfo()
            LOG.info("${feedInfo.site.full} feed ${feedInfo.displayName} returned NO active targets.")
            return null // disable auto-untracking for now, just notify
            propagateTransaction {
                feed.delete()
            }
            LOG.info("Untracking ${feedInfo.site.full} feed ${feedInfo.displayName} as it has no targets.")
            null
        }
    }

    data class SocialMentionRole(val db: TrackedSocialFeeds.SocialTargetMention, val discord: Role?)
    @CreatesExposedContext
    suspend fun getMentionRoleFor(dbTarget: TrackedSocialTarget, targetChannel: MessageChannel, postCfg: PostsSettings, mentionOption: KProperty1<PostsSettings, Boolean>): SocialMentionRole? {
        // do not return ping if not configured for channel/tweet type
        if(!postCfg.mentionRoles) return null
        if(!mentionOption(postCfg)) return null

        val dbMentionRole = propagateTransaction {
            dbTarget.findDbTarget().mention()
        } ?: return null
        val dRole = if(dbMentionRole.mentionRole != null) {
            targetChannel.toMono()
                .ofType(GuildChannel::class.java)
                .flatMap(GuildChannel::getGuild)
                .flatMap { guild -> guild.getRoleById(dbMentionRole.mentionRole!!.snowflake) }
                .tryAwait()
        } else null
        val discordRole = when(dRole) {
            is Ok -> dRole.value
            is Err -> {
                val err = dRole.value
                if(err is ClientException && err.status.code() == 404) {
                    // role has been deleted, remove configuration
                    propagateTransaction {
                        if (dbMentionRole.mentionText != null) {
                            // don't delete if mentionrole still has text component
                            dbMentionRole.mentionRole = null
                        } else {
                            dbMentionRole.delete()
                        }
                    }
                }
                null
            }
            null -> null
        }
        return SocialMentionRole(dbMentionRole, discordRole)
    }
}